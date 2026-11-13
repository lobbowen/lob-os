package lobos.services.supply

import android.content.Context
import android.system.Os
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import lobos.services.log.RuntimeDiagnostics
import lobos.kernel.layout.SystemDirs
import org.json.JSONObject
import lobos.kernel.elf.ExecBits

object SupplyProvisioner {

    private const val CHANNEL_ASSET = "supply/channel.json"
    private const val FETCH_TIMEOUT_MS = 30000
    internal const val MAX_FETCH_BYTES = 256 * 1024 * 1024

    internal const val ENTRY_READ_MAX_BYTES = 4096
    internal const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    private const val SYMLINK_HEAD_MAX = 4096

    fun etcDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.etc(ctx)

    fun versionDir(ctx: Context, id: String, version: String): File =
        File(pieceDir(ctx, id), version)

    fun pieceDir(ctx: Context, id: String): File = lobos.kernel.layout.SystemDirs.pieceDir(ctx, id)

    fun entryLink(ctx: Context, name: String): File = lobos.kernel.layout.SystemDirs.bin(ctx).let { File(it, name) }

    private fun selectedFile(ctx: Context): File = File(etcDir(ctx), "installed.json")

    fun selectedVersion(ctx: Context, id: String, fallback: String = ""): String {
        val f = selectedFile(ctx)
        if (!f.isFile) return fallback
        return runCatching {
            JSONObject(f.readText()).optString(id, "")
        }.getOrNull().orEmpty().ifBlank { fallback }
    }

    fun selectVersion(ctx: Context, id: String, version: String) {
        val f = selectedFile(ctx)
        val cur = runCatching {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        }.getOrNull() ?: JSONObject()
        cur.put(id, version)
        f.parentFile?.mkdirs()
        f.writeText(cur.toString())
    }

    internal fun manifestDir(ctx: Context): String? {
        val o = channelAnchor(ctx) ?: return null
        val base = o.optString("baseUrl", "").trimEnd('/')
        if (base.isEmpty()) return null
        return base + "/component-" + o.optString("channel", "canary")
    }

    internal fun manifestNameOf(ctx: Context, channel: String): String? {
        val o = channelAnchor(ctx) ?: return null
        val m = o.optJSONObject("manifests")?.optJSONObject(channel)
        if (m != null) return m.optString("name", "").ifBlank { null }
        if (channel == "component") return o.optString("manifestName", "").ifBlank { null }
        return null
    }

    internal fun manifestSigNameOf(ctx: Context, channel: String): String? {
        val o = channelAnchor(ctx) ?: return null
        val m = o.optJSONObject("manifests")?.optJSONObject(channel)
        if (m != null) return m.optString("sigName", "").ifBlank { null }
        if (channel == "component") return o.optString("sigName", "").ifBlank { null }
        return null
    }

    internal fun uncached(url: String): String =
        url + (if (url.indexOf('?') >= 0) "&" else "?") + "t=" + System.currentTimeMillis()

    private fun openFollowingRedirects(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Pair<HttpURLConnection, Int> {
        var target = url
        var hops = 0
        while (true) {
            val c = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank() || hops >= 8) {
                    throw IllegalStateException("HTTP " + code + " " + target)
                }
                target = URL(URL(target), loc).toString()
                hops++
                continue
            }
            return Pair(c, code)
        }
    }

    internal fun httpGet(url: String, maxBytes: Int = MAX_FETCH_BYTES): ByteArray {
        var current: HttpURLConnection? = null

        return try {

            val (conn, code) = openFollowingRedirects(url, FETCH_TIMEOUT_MS, FETCH_TIMEOUT_MS)
            current = conn
            if (code !in 200..299) throw IllegalStateException("HTTP " + code + " " + url)
            val declared = conn.getHeaderFieldLong("Content-Length", -1L)
            if (declared > maxBytes.toLong()) {
                throw IllegalStateException("响应声明 " + declared + " 字节，超上限 " + maxBytes + "：" + url)
            }
            conn.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) {
                        throw IllegalStateException("响应超上限 " + maxBytes + " 字节：" + url)
                    }
                }
                out.toByteArray()
            }
        } finally {
            runCatching { current?.disconnect() }
        }
    }

    internal fun channelAnchor(ctx: Context): JSONObject? = try {
        val t = ctx.assets.open(CHANNEL_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(t)
    } catch (e: Throwable) { null }

    internal fun anchorName(ctx: Context, field: String): String? =
        channelAnchor(ctx)?.optString(field, "")?.ifBlank { null }

    internal fun verifyEd25519(pubPem: String, data: ByteArray, sig: ByteArray): Boolean =
        lobos.kernel.crypto.Crypto.verifyEd25519(pubPem, data, sig)

    internal fun httpGetToFile(url: String, dest: File): Long {
        dest.parentFile?.mkdirs()
        var current: HttpURLConnection? = null
        try {

            val (c, code) = openFollowingRedirects(url, 15_000, 300_000)
            current = c
            if (code !in 200..299) {
                throw IllegalStateException("件下载 HTTP " + code)
            }
            dest.outputStream().buffered().use { out ->
                c.inputStream.use { it.copyTo(out, 64 * 1024) }
            }
            return dest.length()
        } finally {
            runCatching { current?.disconnect() }
        }
    }

    internal fun sha256HexFile(f: File): String = lobos.kernel.crypto.Crypto.sha256HexFile(f)

    internal fun unzipFromFile(pkg: File, dest: File, probeCtx: android.content.Context? = null) {
        val form = containerFormOf(pkg)

        reportContainerForm(probeCtx, pkg, form)
        when (form) {
            CONTAINER_ZIP -> unzip(pkg, dest, probeCtx)
            CONTAINER_GZIP -> untarFromFile(pkg, dest)
            CONTAINER_TAR -> untarFromFile(pkg, dest)
            else -> {

                unzip(pkg, dest, probeCtx)
            }
        }
    }

    private fun reportContainerForm(
        ctx: android.content.Context?,
        pkg: File,
        form: String,
    ) {
        val c = ctx ?: return
        val magic = try {
            pkg.inputStream().use { ins ->
                val b = ByteArray(4)
                var got = 0
                while (got < 4) {
                    val n = ins.read(b, got, 4 - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < 4) "" else b.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Throwable) {
            ""
        }
        lobos.services.log.RuntimeDiagnostics.append(
            c, "container-form", true,
            "容器形态 " + form,
            "文件=" + pkg.name + " 档位=" + form + " 头四字节=" + magic +
                " （一档逐字是「这一份实际上是什么容器」）",
        )
    }

    const val CONTAINER_ZIP = "zip"
    const val CONTAINER_GZIP = "gzip"
    const val CONTAINER_TAR = "tar"
    const val CONTAINER_UNKNOWN = "unknown"

    internal fun containerFormOf(pkg: File): String {
        if (!pkg.isFile || pkg.length() <= 0L) return CONTAINER_UNKNOWN
        val head = try {
            pkg.inputStream().use { ins ->
                val b = ByteArray(4)
                var got = 0
                while (got < 4) {
                    val n = ins.read(b, got, 4 - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < 4) return CONTAINER_UNKNOWN
                b
            }
        } catch (e: Throwable) {
            return CONTAINER_UNKNOWN
        }
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        val b2 = head[2].toInt() and 0xFF
        val b3 = head[3].toInt() and 0xFF
        return when {
            b0 == 0x50 && b1 == 0x4b -> CONTAINER_ZIP
            b0 == 0x1f && b1 == 0x8b -> CONTAINER_GZIP
            else -> {

                val tarOk = try {
                    val pb = ProcessBuilder("tar", "tf", pkg.absolutePath)
                    pb.redirectErrorStream(true)
                    pb.start().waitFor() == 0
                } catch (e: Throwable) {
                    false
                }
                if (tarOk) CONTAINER_TAR else CONTAINER_UNKNOWN
            }
        }
    }

    private fun untarFromFile(pkg: File, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(
                pkg.inputStream().buffered()
            )
        ).use { tin ->
            while (true) {
                val e = tin.nextEntry ?: break
                val name = e.name
                if (name == "./" || name.isEmpty()) continue
                val out = File(dest, name).canonicalFile
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + name)
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> tin.copyTo(os) }
                    ExecBits.apply(out)
                }
            }
        }
    }

    private fun unzip(zip: File, dest: File, probeCtx: android.content.Context? = null) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        java.util.zip.ZipFile(zip).use { zf ->

            val entryNames = java.util.Collections.list(zf.entries()).map { it.name }.toSet()
            var entryCount = 0
            var byteCount = 0L
            var linkCount = 0
            for (e in java.util.Collections.list(zf.entries())) {
                val out = File(dest, e.name).canonicalFile
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + e.name)
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()

                    val head = zf.getInputStream(e).use { ins ->
                        val b = ByteArray(SYMLINK_HEAD_MAX)
                        val n = ins.read(b)
                        if (n <= 0) ByteArray(0) else b.copyOf(n)
                    }
                    val linkTarget = symlinkTargetOf(e.name, head, entryNames)
                    if (linkTarget != null) {
                        linkCount++

                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {

                        zf.getInputStream(e).use { ins ->
                            out.outputStream().use { os ->
                                val buf = ByteArray(1 shl 16)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    os.write(buf, 0, n)
                                }
                            }
                        }
                        entryCount++
                        byteCount += e.size
                        ExecBits.apply(out)
                    }
                }
            }

            if (probeCtx != null) {
                runCatching {
                    RuntimeDiagnostics.append(
                        probeCtx, "unzip-probe", true, "解包汇总 " + zip.name,
                        "条目=" + (entryCount + linkCount) +
                            " 文件=" + entryCount +
                            " 软链=" + linkCount +
                            " 字节=" + byteCount,
                    )
                }
            }
        }
    }

    private fun symlinkTargetOf(entryName: String, bytes: ByteArray, entryNames: Set<String>): String? {
        if (bytes.isEmpty() || bytes.size > 4096) return null
        if (bytes[0] == 0x7f.toByte() && bytes.size > 3 &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (text.isEmpty() || text.any { it == '\u0000' } || text.contains('\n')) return null
        val dir = entryName.substringBeforeLast('/', "")
        val resolved = java.nio.file.Paths.get(dir, text).normalize().toString().removePrefix("./")
        if (!entryNames.contains(resolved)) return null
        return text
    }

    private fun readEntryCapped(zin: ZipInputStream, name: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        var total = 0
        while (total <= ENTRY_READ_MAX_BYTES) {
            val n = zin.read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        val bytes = buf.toByteArray()
        if (bytes.size > ENTRY_READ_MAX_BYTES) {
            throw IllegalStateException(
                "那一个条目逐字太大：" + name + " 逐字 = " + bytes.size +
                    " 字节、读法逐字[supply.unzip]逐字只许 " + ENTRY_READ_MAX_BYTES +
                    "（按 C-05 报缺、**不**整读进那堆）"
            )
        }
        return bytes
    }

    internal fun unzipInto(zipBytes: ByteArray, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile

        val names = LinkedHashSet<String>()
        val bodies = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {

                if (!e.isDirectory) {
                    bodies[e.name] = readEntryCapped(zin, e.name)
                }
                names.add(e.name)
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        for ((name, bytes) in bodies) {
            val out = File(dest, name).canonicalFile
            if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + name)
            }
            out.parentFile?.mkdirs()
            val linkTarget = symlinkTargetOf(name, bytes, names)
            if (linkTarget != null) {
                java.nio.file.Files.deleteIfExists(out.toPath())
                java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
            } else {
                out.outputStream().use { it.write(bytes) }
                ExecBits.apply(out)
            }
        }
    }
}

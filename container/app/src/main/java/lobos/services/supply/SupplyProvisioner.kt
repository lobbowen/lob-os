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
    internal const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

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

    internal fun httpGet(url: String, maxBytes: Int = MAX_FETCH_BYTES): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = FETCH_TIMEOUT_MS
        conn.readTimeout = FETCH_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        return try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP " + conn.responseCode + " " + url)
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
            conn.disconnect()
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
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 300_000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("件下载 HTTP " + code)
            }
            dest.outputStream().buffered().use { out ->
                conn.inputStream.use { it.copyTo(out, 64 * 1024) }
            }
            return dest.length()
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun sha256HexFile(f: File): String = lobos.kernel.crypto.Crypto.sha256HexFile(f)

    internal fun unzipFromFile(pkg: File, dest: File) {
        val n = pkg.name.lowercase()
        when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> untarFromFile(pkg, dest)
        n.endsWith(".zip") -> unzip(pkg, dest)
            else -> unzip(pkg, dest)
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

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        java.util.zip.ZipFile(zip).use { zf ->
            // 包里有哪些条目 —— 判「这条是不是软链」要用它：
            // 软链条目在 zip 里存的是**目标路径字符串**（实测 bin/cat 的 20 字节 = "../lib/libbusybox.so"），
            // 而普通文件存的是内容。两个条件缺一不可：内容像一条路径，且按它解析出来的目标**在包里存在**。
            // （那三个 1 字节的 "x" 因此不会被误判：它解析成 bin/x，而包里没有 bin/x）
            val entryNames = java.util.Collections.list(zf.entries()).map { it.name }.toSet()
            for (e in java.util.Collections.list(zf.entries())) {
                val out = File(dest, e.name).canonicalFile
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    throw IllegalArgumentException("包条目路径越界（疑似目录穿越）: " + e.name)
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    val bytes = zf.getInputStream(e).use { ins ->
                        ins.readBytes()
                    }
                    val linkTarget = symlinkTargetOf(e.name, bytes, entryNames)
                    if (linkTarget != null) {
                        // C-102 **解包时把软链展平成字节副本** —— 原来这一支只按字节拷贝，
                        // 于是「一条命令入口」从软链变成一份独立的字节副本：
                        // 真机读数（07，base-busybox 那一版）：files/opt/busybox/1.36.1/bin/cat 与
                        // lib/libbusybox.so sha256 相同（87e32d67…b51e0） 同一份字节出现在 18 个名字下。
                        // 处置：重建一条软链，指向包里那个目标（相对路径，与打包时一致）。
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {
                        out.outputStream().use { it.write(bytes) }
                        ExecBits.apply(out)
                    }
                }
            }
        }
    }

    /** 这一条是不是软链条目；是的话返回它的目标（相对路径），不是则返回 null。
     *  判据两个条件缺一不可：内容能解成一条路径，且按它解析出来的目标**在包里存在**。
     *   而 1 字节的小文件（今天 dist/ 那三个 "x"）解析出来的目标不在包里  判为普通文件。 */
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

    /** 与 [unzip] 同形，**也要认软链条目** ——
     *   缺陷名（C-102）**第二处解包把软链展平成字节副本**：
     *    原来这一支只按字节拷贝（`zin.copyTo(it)`），而 [unzip] 那一支已经会重建软链
     *    （ 那是 64d40f0 建的） **同一件事有两个实现，而只有一个认软链**
     *     ** 走这一支的那条链**（ProgramInstaller.kt:110） **拿到的是展平后的字节**。
     *     而判据仍按「内容能解成一条路径 + 按它解析出来的目标在包里存在」两条件缺一不可。
     *   而 ZipInputStream 是**流式**的（拿不到全量条目名） 先把条目名与内容各读一遍。
     */
    internal fun unzipInto(zipBytes: ByteArray, dest: File) {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        // 先读一遍：条目名 + 每一项的内容（ZipInputStream 是流式的，判软链要「全量条目名」）。
        val names = LinkedHashSet<String>()
        val bodies = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) bodies[e.name] = zin.readBytes()
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

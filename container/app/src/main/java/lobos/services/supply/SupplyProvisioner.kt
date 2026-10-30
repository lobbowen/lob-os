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


    /** 那**软链**那一族逐字**只**看**那**头**这**几**个**字节**（=> 而而那**它**逐字**够**那**判**那**软链**用**）。 */
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

    /**
     * 打开一个 GET 连接并跟完重定向，返回（连接, 最终状态码）。
     *
     * GitHub 的 release 资产直链先给 302（跳到 objects.githubusercontent.com），
     * 跟随重定向之后才是 200。HttpURLConnection 遇到换协议的跳转不自动跟随，
     * 那种情形下 responseCode 会停在 3xx —— 所以这里既开 instanceFollowRedirects，
     * 也自己兜住 3xx + Location。
     *
     * 返回的 responseCode 是**重定向之后**那一跳的状态码。
     */
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
        //  缺陷名（C-102）**httpGet 的函数体是 try/finally、而 try 那一段没有 return**：
        //   重定向那一段抽成 helper 之后，这里从「return try { ... } finally { ... }」
        //   变成了「try { ... } finally { ... }」—— 而 Kotlin 那一族里
        //   函数体的最后一段是 try 时，它**不算**那个函数的返回值（编译器报
        //   Missing return statement）。=> 而于是这一处编译不过。
        //   现在：把 return 补回（与 httpGetToFile 那一支同形：try 里显式 return）。
        return try {
            //  与 httpGetToFile 走同一个 helper：跟随重定向，判的是重定向之后那一跳的码。
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
            //  与 httpGet 走同一个 helper：跟随重定向，并且判的是**重定向之后**那一跳的码。
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
        val n = pkg.name.lowercase()
        when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> untarFromFile(pkg, dest)
        n.endsWith(".zip") -> unzip(pkg, dest, probeCtx)
            else -> unzip(pkg, dest, probeCtx)
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
            // 包里有哪些条目 —— 判「这条是不是软链」要用它：
            // 软链条目在 zip 里存的是**目标路径字符串**（实测 bin/cat 的 20 字节 = "../lib/libbusybox.so"），
            // 而普通文件存的是内容。两个条件缺一不可：内容像一条路径，且按它解析出来的目标**在包里存在**。
            // （那三个 1 字节的 "x" 因此不会被误判：它解析成 bin/x，而包里没有 bin/x）
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
                    //  缺陷名（C-102）**那解包**那一族逐字**把**那**整个**的**大**条目**逐字**读**进**那**内存**里**。
                    //    => 而那**成因**：=> 而那 bytes 逐字**是**那 ins.readBytes()（=> 而而那**它**逐字**吃**那**整个**的**条目**）、
                    //      => 而而那**它**们**逐字是**那**两**件**事**：「那**要**的**是**什么**」与「那**吃**了**多少**」）。
                    //      => 而那 git **那一件**逐字**有**那「bin/scalar」**那字节 = 10946536**、那「bin/git-shell」**那字节 = 10773528**（=> 而而那**它们**逐字是**那**两**件**事**）。
                    //    => 而而那**后果**逐字 = 而那**解包**逐字**要**把**那 40 兆**逐字**吃**进**那**内存**里**、=> 而而那**它**们**逐字是**那**两**件**事**：「那**吃**的是**什么**」与「那**慢**的是**什么**」）。
                    //    => 而那**处置**：=> 而那**它**逐字**先**只**读**那**头**那**几**个**字节**（=> 而而那**它**逐字**判**那**软链**够不够**）、
                    //      => 而而那**它**逐字**不**是**那**软链**的**时候**才**把**那**整个**的**条目**逐字**流**到**那盘**上**（=> 而而那**它**们**逐字是**那**两**件**事**）。
                    val head = zf.getInputStream(e).use { ins ->
                        val b = ByteArray(SYMLINK_HEAD_MAX)
                        val n = ins.read(b)
                        if (n <= 0) ByteArray(0) else b.copyOf(n)
                    }
                    val linkTarget = symlinkTargetOf(e.name, head, entryNames)
                    if (linkTarget != null) {
                        linkCount++
                        // C-102 **解包时把软链展平成字节副本** —— 原来这一支只按字节拷贝，
                        // 于是「一条命令入口」从软链变成一份独立的字节副本：
                        // 真机读数（07，base-busybox 那一版）：files/opt/busybox/1.36.1/bin/cat 与
                        // lib/libbusybox.so sha256 相同（87e32d67…b51e0） 同一份字节出现在 18 个名字下。
                        // 处置：重建一条软链，指向包里那个目标（相对路径，与打包时一致）。
                        java.nio.file.Files.deleteIfExists(out.toPath())
                        java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget))
                    } else {
                        //  => 而那**普通**文件逐字**流**到**那盘**上**（=> 而而那**它**逐字**不**再**整个**读**进**那**内存**里**）。
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
            //  => 而那**逐件**逐条**的**读数**逐字**只**报**那**汇总**（=> 而那**条目**数、那**字节**数、那**软链**数）。
            //      => 而而那**它**逐字**不**许**逐条目**落盘**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**记**的**是**什么**」与「那**记**的**几**条**」）。
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

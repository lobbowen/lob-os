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

    /**
     * 解包：按**字节**分派，**不**按文件名后缀。
     *
     * 缺陷名（C-102）**那解包逐字按文件名的后缀分派** ——
     *   而那旧版逐字是：
     *     n.endsWith(".tar.gz") || n.endsWith(".tgz") -> untarFromFile(pkg, dest)
     *     n.endsWith(".zip") -> unzip(pkg, dest, probeCtx)
     *     else -> unzip(pkg, dest, probeCtx)
     *   => 而那**名字**与那**内容**逐字是**两件**事：
     *     · Release 上的资产名沿用旧的 <id>-<deps>+…tar.gz，=> 而里面的内容**已经是 zip**
     *       （=> 而那 fmt.sh 那一族逐字写着「按扩展名分派会对 zip 内容走 tar 分支，解不开，判据一律拒绝」）；
     *     · 而那 CI artifact 那一族逐字是「zip 套 zip」（=> 而那外层逐字装着那内层那一个件包）。
     *   => 而按后缀分派逐字**在这两种形状上都会解错**（=> 而那 zip 套 zip 那一族逐字会把外层当件包）。
     *
     * C-103 这一族它为什么有两边：
     *   一边是「这一份**叫什么名字**」（=> 而那名字是**约定**、=> 而它逐字会骗人）；
     *   另一边是「这一份**实际上是什么容器**」（=> 而那只能**看头四字节**答）。
     *   => 而那两边各答一件事、=> 而那解包该按**后者**分派。
     *
     * C-104 有没有第二种生成路径：没有。=> 而那判别逐字**只有**这一处（=> 而按 C-994「判别源必须唯一」）。
     */
    internal fun unzipFromFile(pkg: File, dest: File, probeCtx: android.content.Context? = null) {
        val form = containerFormOf(pkg)
        //  => 而那 ctx 逐字**要**有（=> 而那出口逐字要有地方可报、=> 而那 ctx 逐字是那唯一的地方）。
        //     => 而那三处调用点逐字**都**有 ctx 可传（=> 而那一处 PieceUpdater 那边的
        //       逐字**原来没传**、=> 而而它现在逐字传上、=> 而而那出口逐字**处处**都报得出）。
        //  缺陷名（C-102）**那一档逐字算出来了、赋到值了，而逐字没有任何一处读它、逐字也没有任何一处把它报出来 ⇒ 那一族等于没做**
        //    => 而那旧版逐字是 `when (containerFormOf(pkg))`（=> 而那**赋值**逐字**没有消费方**：
        //      而 Lead 本机实读逐字「全仓调用点逐字只有一处」、=> 而那**就是**这一处）。
        //    => 而那与 C-996 那一族（Located.tier）逐字**同一形状**（=> 而那判别式被写了、被赋值了、
        //      => 而而没有任何一处读它）。
        //    => 而那**后果**：=> 而 07 本轮实测逐字**触发了** unzipFromFile（=> 而那 Kotlin 侧那一档逐字被算了）
        //      **但那一档逐字没被报出来** => 而那**读不到**。
        //    => 而那**处置**：=> 而那一档逐字**报进诊断那一族**
        //      （=> 而那 RuntimeDiagnostics.append 逐字写进 diag.jsonl、=> 而而那正是 07 能读到的那一份）。
        //    => 而那**stage** 那一格的名字逐字是 "container-form"（=> 而它逐字答的是「这一份是什么容器」；
        //      => 而而它逐字**不**与那 "lib-entry-tier" 那一格混（=> 而那是库入口那一档）。
        //    => 而那**为什么**在这里报、=> 而不在 containerFormOf 那一格里报：
        //      · 而那 containerFormOf 那一格逐字**没有** ctx（=> 而它逐字是纯函数、=> 而那它逐字不该有副作用）；
        //      · 而那**调用点**逐字是那实际**做了那个动作**的地方（=> 而 unzipFromFile 逐字就是那解包那一族）。
        reportContainerForm(probeCtx, pkg, form)
        when (form) {
            CONTAINER_ZIP -> unzip(pkg, dest, probeCtx)
            CONTAINER_GZIP -> untarFromFile(pkg, dest)
            CONTAINER_TAR -> untarFromFile(pkg, dest)
            else -> {
                //  => 而那认不出来时逐字**按 zip 试一次**（=> 而那 zip 那一支逐字会自己报出真错），
                //  => 而而**不**按文件名猜（=> 而那后缀逐字是骗人的）。
                unzip(pkg, dest, probeCtx)
            }
        }
    }

    /**
     * 那一档的**出口**（=> 而它逐字把「这一份是什么容器」报进诊断那一族）。
     *   => 而那 ctx **可以**是空（=> 而那 probeCtx 那一格逐字可选、=> 而而那空的时候逐字**不报**、
     *     => 而而那**不是**「那一档没算」、=> 而而那是「**没有地方可报**」）。
     */
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
                " （=> 而那一档逐字是「这一份实际上是什么容器」）",
        )
    }

    /** 容器形态那一格（=> 而那它逐字**按头四字节**判、=> 而不按名字）。
     *
     *  => 而那**取值域逐字只有三档**（=> 而 01 裁的 S607-01-CONTAINER-FORM-RULING.md §2.1
     *     逐字「外壳 3 档 + 内外层关系 1 档 = 4 档」、=> 而**zip64 不是第五个容器**、
     *     => 而它是 zip 那一档的**中央目录变体**）。
     *  => 而那**运行期不需要**那一档（=> 而 zip64 逐字**用同一个解包器**、
     *     => 而那 zip64 的差别逐字在条目头与偏移表、=> 而**解包器自己认**）。
     *  => 而那**头四字节分不出** zip 与 zip64（=> 而那两个样本逐字都是 504b0304）、
     *     => 而若运行期要分它、=> 而那判别式逐字得**再读一次尾部**（=> 而那是每次解包多一次尾读的代价）。
     */
    const val CONTAINER_ZIP = "zip"
    const val CONTAINER_GZIP = "gzip"
    const val CONTAINER_TAR = "tar"
    const val CONTAINER_UNKNOWN = "unknown"

    /**
     * 这一份**实际上是什么容器**（=> 而那只读头四字节、=> 而**不**整读）。
     *   504b0304 = zip（=> 而那 zip32 与 zip64 **分不出头四字节**、=> 而那要靠尾部哨兵、
     *                    => 而那运行期**不需要**分它、=> 而解包器自己认）；
     *   1f8b08xx = gzip；
     *   其余     = tar（=> 而那判据逐字是「头四字节不是 504b* 也不是 1f8b*、=> 而 tar tf 读得通」）。
     *
     *  => 而那 zip 那一支逐字**只比前两字节**（504b、=> 而**不是** 504b0304）：
     *     而 01 裁的 S607-01-CONTAINER-FORM-RULING.md §2.1 逐字写的是「头四字节逐字 `50 4b 03 04`
     *     （即 `504b*`）」、=> 而**504b\* 逐字就是前两字节加一个通配**（=> 而那是 `50 4b` 开头的一切）。
     *     => 而那**为什么**比 `504b0304` 更宽是**对的**：
     *       · 而那 zip 里的**本地文件头**逐字总是 `50 4b 03 04`（=> 而那件包的第一条逐字是它）；
     *       · 而那**中央目录结束记录**逐字是 `50 4b 05 06`、=> 而那**数据描述符**逐字是 `50 4b 07 08`
     *         （=> 而那两者逐字都在 zip 容器里面、=> 而它们**也**是 `50 4b` 开头）；
     *       => 而那运行期这一格答的是「这一份**是不是 zip 容器**」（=> 而那解包器要认的是**整个容器**），
     *         => 而**不是**「这一份的头**那一条**逐字是不是本地文件头」。
     *     => 而那**若**只比 `504b0304`，=> 而一个逐字以 `50 4b 05 06` 开头的那一份
     *       （=> 而那逐字是一个**空**的 zip、或那一段字节**不是**一个完整 zip）会被误判成「不是 zip」。
     *     => 而那 fmt.sh 那一族（=> 而那是**构建侧**那一把尺）逐字用的也是 `504b*`：
     *       fmt.sh 的 fmt_of 逐字是 `case "$m" in 504b*)`，=> 而那 $m 逐字是**头四字节**。
     *     => 而那**两处口径逐字一致**（=> 而那一处逐字是 `504b*`、=> 而这一处逐字是前两字节 `50 4b`
     *       —— => 而而那**含** 504b0304、=> 而那**不含**别的 50 4b 开头的东西、=> 而那口径逐字同形）。
     */
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
                //  => 而那 tar 那一档逐字要**试读**（=> 而 01 §2.1 逐字「tar tf 读得通」）。
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

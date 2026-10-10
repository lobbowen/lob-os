package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs

/**
 * 从落位推导索引 —— 等价于 Linux 的 `ldconfig(8)`。
 *
 * **为什么有它**
 *
 * Linux 没有注册表。它靠三件事让系统「知道装了什么」：
 * 1. 落位在约定目录（`/etc/ld.so.conf` 列目录，`/usr/lib` 是 trusted）
 * 2. 形态与版本自带在名字里（`ldconfig` 只认 `lib*.so*`，
 *    "Other files will be ignored"；版本是 `libfoo.so.1.12` 的文件名）
 * 3. `ldconfig` 扫目录建软链 + 写 `ld.so.cache`
 *    —— 原文："creates the necessary links and cache to the most recent
 *    shared libraries found in the directories"
 *
 * **索引是推导出来的，不是安装器写出来的。**
 *
 * 我们此前是安装器手写索引（6 个类10 处 `upsert`），
 * 于是「系统里有什么」取决于每个安装路径都记得写登记 —— 漏一处就答不出来，
 * 只能退回探针去探测（322 处）。
 *
 * **这个机制做什么**
 *
 * 扫 `usr/lib/<id>/<版本>/` 与 `usr/bin/`，
 * 得出「有哪些件、什么版本、入口在哪」，与安装器无关。
 * 加一件新件 = 放文件进 `usr/lib/<id>/` + 跑一次 [rebuild]，
 * 不改代码、不改表结构、不写登记。
 *
 * **它不管什么**
 *
 * 「要不要跑」「怎么跑」不是推导出来的 —— 那是 `UnitEntry` 里
 * `desired` / `restart` / `env` / `httpPort` 这些字段的事，
 * 由 `ProgramManager` 维护。`ldconfig` 只管「装了什么」。
 */
object PieceScan {
    /** 落位形状决定的形态 —— 照抄 ldconfig "checks the header and filenames" */
    const val EXEC = "exec"

    /** 命令解释器那件（bash）。不是形态 —— bash 落位里有 bin/，role 就是 exec */
    const val SHELL = "shell"

    /** multi-command：一个二进制提供多个命令（它自己在说明里声明） */
    const val MULTI_COMMAND = "multi-command"
    const val LIBRARY = "library"
    const val HEADERS = "headers"

    /** 扫出来的结果：这件是什么、什么版本、落在哪 */
    /**
     * 扫到的一件 —— 字段来自**落位形状 + 件自带的说明**。
     *
     * meta 是与件同目录的 component-meta.json（照抄 deb-control(5)：
     * 每个包自带 control）。内核不预置任何一件的清单，它只读落位。
     */
    data class Found(
        val id: String,
        val version: String,
        val dir: File,
        val entry: String,
        val role: String,
        val sha256: String,
        val meta: JSONObject? = null,
        /**
         * 这一件铺了哪些文件 + 每个文件的校验值 —— dpkg 的 db-fsys:Files 与 .deb 的 md5sums。
         *
         * dpkg -V 拿的就是这份与实际文件比对（"comparing information from the files
         * installed by a package with the files metadata information stored in the dpkg
         * database"）。没有它就答不出：删这个件该删哪些文件 · 某个文件被换过没有。
         * 路径相对件目录（usr/lib/<id>/<版本>/），换存储位置不用重记。
         */
        val files: List<UnitEntry.FileRec> = emptyList(),
    ) {

        /**
         * 是不是**必需**的件（缺了系统起不来）—— 照 deb-control(5) 的 Essential: yes/no。
         *
         * 事实来源只有一个：随件落地的 component-meta.json 里的 essential 字段
         * （由 scripts/recipes/gen-component-meta.js 从 component-verify.json 的
         * required 派生写进去）。所以这里是**读出来**，不是另存一份 ——
         * 另存就多了一个可能与 meta 不一致的副本。
         *
         * 此前 PieceProvisioner 直接写 exe.required，而 Found 没有这个字段，
         * 编译报「Unresolved reference (receiver of type Found)」。加这个派生属性，
         * 那 4 处（allRequiredReady / failedRequired / toJson / 那行日志 tag）
         * 就与事实源一致了。
         */
        val required: Boolean get() = meta?.optBoolean("essential", false) ?: false

        /**
         * 件内入口文件名（如 libfoo.so.1.3.2）—— entry 去掉目录前缀。
         *
         * 事实来源是 entry，不另存一份：entry 本就是 component-meta.json 里
         * 的那一条，与校验用的是同一个字段。PieceProvisioner 有两处自己写
         * substringAfterLast("/")（一处塞进 JSON 的 libName、一处拼诊断行），
         * 两处都按它调 Found，却在 Found 上没有这个成员。
         */
        val libName: String get() = entry.substringAfterLast('/', "")
    }
    /**
     * 若目录里两者都有，以 `bin/` 为准（它是全局入口，`$PREFIX/bin` 在 PATH 里）。
     */
    fun scan(ctx: Context): List<Found> {
        val lib = SystemDirs.lib(ctx)
        val kids = lib.listFiles() ?: return emptyList()
        val out = mutableListOf<Found>()
        for (pieceDir in kids) {
            if (!pieceDir.isDirectory) continue
            val id = pieceDir.name
            // 头文件集那件不是「可执行/库」，它提供 include/ —— 形态是 headers
            if (File(pieceDir, "include").isDirectory) {
                val hf = filesOf(pieceDir)
                out += Found(id, "from-layout", pieceDir, "include", HEADERS,
                    sha256Of(pieceDir, hf), metaOf(pieceDir), hf)
                continue
            }
            for (verDir in pieceDir.listFiles() ?: emptyArray()) {
                if (!verDir.isDirectory) continue
                val version = verDir.name
                val entry = entryOf(verDir) ?: continue
                val files = filesOf(verDir)
                out += Found(
                    id = id,
                    version = version,
                    dir = verDir,
                    entry = entry,
                    role = roleOf(verDir),
                    sha256 = sha256Of(verDir, files),
                    meta = metaOf(verDir),
                    files = files,
                )
            }
        }
        return out.sortedBy { it.id }
    }

    /** 入口：优先 `bin/`（全局入口），否则第一个 `.so` */
    private fun entryOf(verDir: File): String? {
        val bin = File(verDir, "bin")
        if (bin.isDirectory) {
            bin.listFiles()?.firstOrNull { it.isFile }?.let { return "bin/" + it.name }
        }
        verDir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.endsWith(".so") }
            ?.let { return it.relativeTo(verDir).path }
        return null
    }

    /** 形态：只看落位形状，不看字段声明（照抄 ldconfig "checks the header and filenames"） */
    private fun roleOf(verDir: File): String {
        if (File(verDir, "bin").isDirectory) return EXEC
        return LIBRARY
    }

    /** 字节身份：对入口文件实算（与 ldconfig "checks the header" 同理，看真实内容） */
    /**
     * 这一件铺了哪些文件 —— dpkg 的 db-fsys:Files。
     *
     * 收件目录下的普通文件；软链不收（软链指向同目录的另一个文件，
     * 记它等于记两遍）。说明文件自己不算「铺出来的内容」——
     * 它是元数据，不是件的一部分。
     */
    /**
     * 读一件随落的说明 —— component-meta.json（照抄 deb-control(5)：
     * 每个包自带 control，内核不预置任何一件的清单）。
     *
     * 读法与 ProgramInstallPipeline.pieceMetaOf 一致（同一份文件、同一种解析），
     * 不另立一套 —— 两处各写一遍就会漂。
     * 解析不了当没有：meta 是补充信息，缺它不该让整个扫描失败。
     */
    private fun metaOf(verDir: File): JSONObject? {
        val f = File(verDir, "component-meta.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }


    private fun filesOf(verDir: File): List<UnitEntry.FileRec> {
        val base = verDir.absolutePath
        val out = mutableListOf<UnitEntry.FileRec>()
        verDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name == "component-meta.json") return@forEach
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isEmpty()) return@forEach
            out += UnitEntry.FileRec(rel, SupplySha.sha256(f))
        }
        return out.sortedBy { it.path }
    }

    /**
     * 整件的字节身份 —— 覆盖它铺出的全部文件。
     *
     * 原来只算第一个 .so：一件里有 5 个文件、被换了一个，那个值不变，
     * 查不出来（dpkg 用 md5sums 逐文件正是为了这个）。
     * 现在把所有文件的校验值按路径序串起来再哈希 —— 任何一个变了，整件的值就变。
     */
    private fun sha256Of(verDir: File, files: List<UnitEntry.FileRec>): String {
        if (files.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (fr in files) {
            md.update(fr.path.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(fr.sha256.toByteArray(Charsets.UTF_8))
            md.update(10)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 重建索引里「装了什么」那部分。
     *
     * **只覆盖推导得出的字段**（`version` / `stateDir` / `assetEntry` / `role` / `sha256`），
     * 「要不要跑」那些字段保留原值 —— 那是 `ProgramManager` 的事，
     * `ldconfig` 不管进程起不起。
     *
     * 扫不到（文件被删了）的条目**移除**：落位没了就是没装了。
     */
    fun rebuild(ctx: Context): Int {
        val found = scan(ctx)
        if (found.isEmpty()) return 0
        val cur = ProgramIndex.all(ctx).associateBy { it.id }
        var changed = 0
        for (f in found) {
            val prev = cur[f.id]
            val entry = (prev ?: ProgramIndex.empty(f.id, Level.PIECE)).edited(
                version = f.version,
                stateDir = f.dir.absolutePath,
                assetEntry = f.entry,
                role = f.role,
                sha256 = f.sha256,
                required = f.meta?.optBoolean("essential", false) ?: false,
                files = f.files,
            )
            if (prev != entry) {
                ProgramIndex.upsert(ctx, entry)
                changed++
            }
        }
        return changed
    }

    //
    // 下面的查询一律**读注册表**，不扫落位 ——
    // 照抄 dpkg-query(1)：「-s, --status … This just displays the entry in the
    // installed package status database」。查状态是读库，不是看磁盘。
    //
    // 扫落位只有两个时机：
    //   ① provision()  铺完之后 —— 那时要算出「铺了哪些文件」写进注册表
    //   ② verify()     校验时 —— 拿注册表里记的与实际文件比（dpkg -V）
    //

    /**
     * 一件的结果：哪些文件与登记不符。
     *
     * 照抄 dpkg -V 的返回形状（一个不合格项一行）。
     */
    data class Verdict(val ok: Boolean, val checked: Int, val mismatched: List<String>)

    /**
     * 校验：**拿注册表里记的与磁盘上实际的比** —— dpkg -V。
     *
     * 与 [rebuild] 的区别：rebuild 是「扫落位、写进注册表」（装完之后做一次）；
     * verify 是「读注册表、与磁盘比」（任何时候都能做，查有没有被换过）。
     *
     * 比对依据是 files 里那份清单（相对件目录的路径 + sha256）。
     * 落在件目录之外的文件不在清单里 —— 那是别人的事。
     */
    fun verify(ctx: Context, id: String): Verdict {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return Verdict(false, 0, emptyList())
        if (e.stateDir.isBlank()) return Verdict(false, 0, emptyList())
        if (e.files.isEmpty()) return Verdict(false, 0, emptyList())
        val base = File(e.stateDir)
        val bad = mutableListOf<String>()
        for (fr in e.files) {
            val f = File(base, fr.path)
            when {
                !f.isFile -> bad += "缺：${fr.path}"
                SupplySha.sha256(f) != fr.sha256 -> bad += "被换过：${fr.path}"
            }
        }
        // 登记里有、现在没有的（被人删了）
        return Verdict(bad.isEmpty(), e.files.size, bad)
    }

    /** 全仓校验 —— 返回不合格的件 id 列表（dpkg -C 的形状） */
    fun verifyAll(ctx: Context): Map<String, Verdict> {
        val out = linkedMapOf<String, Verdict>()
        for (e in ProgramIndex.all(ctx)) {
            out[e.id] = verify(ctx, e.id)
        }
        return out
    }
    /** 某个 id 落位在哪 —— 读注册表（dpkg -s） */
    fun pieceDir(ctx: Context, id: String): File? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank()) return null
        return File(e.stateDir)
    }

    /** 某个 id 的入口文件（命令）或库文件 —— 读注册表 */
    fun pieceFile(ctx: Context, id: String): File? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank() || e.assetEntry.isBlank()) return null
        return File(File(e.stateDir), e.assetEntry)
    }

    /** 某个 id 落位那一件的说明（件自带，deb-control 的做法） */
    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank()) return null
        return metaOf(File(e.stateDir))
    }

    /**
     * 命令解释器 —— 落位里能当 sh 用的那个可执行体。
     *
     * 判据是**落位形状**：bin/ 下有可执行、且不是库的那个。role 只有两个值
     * （exec / library，由 roleOf 从 bin/ 与否推出），此前这里按 role == "shell"
     * 和 role == "multi-command" 找——那两个值 roleOf 从不产出，两条链恒空。
     *
     * 优先件自己的说明里声明了命令解释器的那一件（bash），没有再退到
     * multi-command 件（busybox）—— 前者语义专一，后者是万金油。
     */
    fun shellBin(ctx: Context): File? {
        // 两条独立的查找链：先命令解释器那件，没有再退到 multi-command 的。
        fun landOf(e: lobos.os.UnitEntry): File? =
            if (e.stateDir.isBlank() || e.assetEntry.isBlank()) null
            else File(File(e.stateDir), e.assetEntry).takeIf { it.isFile }
        val pieces = ProgramIndex.all(ctx).filter { ProgramIndex.isPiece(it) }
        val shell = pieces.firstOrNull { it.role == SHELL || it.id == "bash" }
        if (shell != null) return landOf(shell)
        val multi = pieces.firstOrNull { it.role == MULTI_COMMAND || it.id == "busybox" }
        return multi?.let { landOf(it) }
    }

}


internal object SupplySha {
    fun sha256(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}

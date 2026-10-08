package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONObject

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
 * 「要不要跑」「怎么跑」不是推导出来的 —— 那是 `IndexEntry` 里
 * `desired` / `restart` / `env` / `httpPort` 这些字段的事，
 * 由 `ProgramManager` 维护。`ldconfig` 只管「装了什么」。
 */
object PieceScan {

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
    ) {
        val required: Boolean get() = meta?.optBoolean("required", false) ?: false
        val provides: List<String>
            get() = meta?.optJSONArray("provides")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList()
    }

// 读与件同目录的说明
private fun metaOf(verDir: File): JSONObject? =
    runCatching { JSONObject(File(verDir, "component-meta.json").readText()) }.getOrNull()

    /**
     * 扫 `usr/lib/<id>/<版本>/` —— 与 `ldconfig` 扫 trusted 目录同构。
     *
     * 形态由**落位位置**决定（照抄 Linux：形态由位置与文件名决定，不由字段声明）：
     *   `usr/lib/<id>/<版本>/lib<name>.so` → 库（role=library）
     *   `usr/lib/<id>/<版本>/bin/<name>`   → 命令（role=exec）
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
                out += Found(id, "from-layout", pieceDir, "include", SystemRoles.HEADERS, "",
                    metaOf(pieceDir))
                continue
            }
            for (verDir in pieceDir.listFiles() ?: emptyArray()) {
                if (!verDir.isDirectory) continue
                val version = verDir.name
                val entry = entryOf(verDir) ?: continue
                out += Found(
                    id = id,
                    version = version,
                    dir = verDir,
                    entry = entry,
                    role = roleOf(verDir),
                    sha256 = sha256Of(verDir),
                    meta = metaOf(verDir),
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
        if (File(verDir, "bin").isDirectory) return SystemRoles.EXEC
        return SystemRoles.LIBRARY
    }

    /** 字节身份：对入口文件实算（与 ldconfig "checks the header" 同理，看真实内容） */
    private fun sha256Of(verDir: File): String {
        val f = verDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".so") } ?: return ""
        return SupplySha.sha256(f)
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
            val entry = (prev ?: ProgramIndex.empty(
                f.id,
                if (f.role == SystemRoles.HEADERS) Level.PIECE else Level.PIECE,
            )).copy(
                version = f.version,
                stateDir = f.dir.absolutePath,
                assetEntry = f.entry,
                role = f.role,
                sha256 = f.sha256,
            )
            if (prev != entry) {
                ProgramIndex.upsert(ctx, entry)
                changed++
            }
        }
        return changed
    }
}

/** sha256 单独放这，避免 PieceScan 直接依赖写文件的模块 */
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
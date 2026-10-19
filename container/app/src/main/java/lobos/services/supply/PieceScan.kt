package lobos.services.supply

import android.content.Context
import java.io.File
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitEntry
import lobos.services.reg.Level
import lobos.services.reg.edited

object PieceScan {
    const val EXEC = "exec"

    const val SHELL = "shell"

    const val MULTI_COMMAND = "multi-command"
    const val LIBRARY = "library"
    const val HEADERS = "headers"

    data class Found(
        val id: String,
        val version: String,
        val dir: File,
        val entry: String,
        val role: String,
        val sha256: String,
        val meta: JSONObject? = null,
        val files: List<UnitEntry.FileRec> = emptyList(),
    ) {

        val required: Boolean get() = meta?.optBoolean("essential", false) ?: false

        val libName: String get() = entry.substringAfterLast('/', "")
    }
    fun scan(ctx: Context): List<Found> {
        val lib = SystemDirs.opt(ctx)
        val kids = lib.listFiles() ?: return emptyList()
        val out = mutableListOf<Found>()
        for (pieceDir in kids) {
            if (!pieceDir.isDirectory) continue
            val id = pieceDir.name
            if (File(pieceDir, "include").isDirectory) {
                val hf = filesOf(pieceDir)
                out += Found(id, "from-layout", pieceDir, "include", HEADERS,
                    sha256Of(pieceDir, hf), metaOf(pieceDir), hf)
                continue
            }
            for (verDir in pieceDir.listFiles() ?: emptyArray()) {
                if (!verDir.isDirectory) continue
                val version = verDir.name
                // 入口有效性由那份字节自己回答（§20.2:618）；无效则**报缺**：continue（不登记），
                // 而**不**兜底到别的目录去猜一个（§20.2:625）。
                val declared = metaOf(verDir)?.optString("entry", "").orEmpty()
                val entry = entryOf(verDir, declared) ?: continue
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

    /** 扫盘时登记的入口。**主语是那份字节**，不是目录名（§20.2:618 / §10.1:128）。
     *  ★ 而 `declared` 是清单里的 entry 声明；它今天能不能被当程序调起由 [usableEntryOf] 回答。
     *  ★ 缺陷名（C-102）：原来这里先看 `bin/` 下有没有文件、再看有没有 `.so` —���
     *    那是「看目录名」，§10.1:128 刚把它判为无效。 */
    private fun entryOf(verDir: File, declared: String): String? =
        usableEntryOf(verDir, declared)

    /** role 由**这一份字节**的形态回答（01 今天的措辞，§10.1:119）：
     *  ET_EXEC 或 ET_DYN+PT_INTERP ⇒ 程序；ET_DYN 无 PT_INTERP ⇒ 库；带 include/ 的头目录 ⇒ 头。
     *
     *  ★ **缺陷名（C-102）判据读错了主语**：原来 [runnableEntryOf] 只看 `bin/` 这一个目录，
     *    而 §10.1:128 自己写着「同一个目录名下两种形态并存」—— 目录名是我们自己起的。
     *    所以现在**全目录**找那份有可执行形态的字节，不看它在哪一层。
     *  ★ 而执行位与形态**分成两把尺**：形态答「它是什么」，执行位答「今天能不能调起它」。 */
    private fun roleOf(verDir: File): String {
        if (File(verDir, "include").isDirectory) return HEADERS
        runnableEntryOf(verDir)?.let { return EXEC }
        sharedObjectOf(verDir)?.let { return LIBRARY }
        return LIBRARY
    }

    /** 目录下**任何一层**里有共享库形态（ET_DYN **且**没有 PT_INTERP）的那份字节。
     *  ★ 与 [runnableEntryOf] 是两把不同的尺：一个看「它是程序」，一个看「它是库」，
     *    依据都是那份字节自己的 ELF 头，不看文件名后缀、不看目录名（§10.1:128）。 */
    private fun sharedObjectOf(verDir: File): File? =
        verDir.walkTopDown().maxDepth(2)
            .firstOrNull {
                it.isFile && lobos.kernel.elf.ExecBits.formOf(it) == lobos.kernel.elf.ExecBits.Form.DynPlain
            }

    /** 目录下**任何一层**里有可执行形态（ET_EXEC / ET_DYN+PT_INTERP）且 owner 有执行位的那份字节。 */
    private fun runnableEntryOf(verDir: File): File? =
        verDir.walkTopDown().maxDepth(2)
            .firstOrNull {
                it.isFile && lobos.kernel.elf.ExecBits.isRunnableByForm(it)
            }

    /** 这一件在磁盘上的形态判定。**主语是字节**，不是条目（§10.1:121「一个单元可以同时有
     *  可执行字节与不可执行字节」）。
     *
     *  ★ **缺陷名（C-102）先读声明再判字节**：原来这一段先取 `component-meta.json` 的 entry、
     *    再看那份有没有 SONAME —— 主语是清单里写着的那一条，而不是盘上那份字节。
     *    §10.1:138「一个字段若需要同时回答两个问题，拆成两个字段或废掉其中一个」⇒ 这一段不再读 meta。 */
    fun roleFromDisk(ctx: Context, id: String): String {
        // 找不到落位目录时**不猜**：按 §10.1:142「这一格的答案能不能从任意一个落位字节的 ELF 头读出来？
        // 不能 ⇒ 它不是判据，是我们的命名」⇒ 没有字节就没有答案，报缺（§10.1:119 的三态里「不可执行」那一态）。
        val dir = landedPieceDir(ctx, id)
            ?: return ENTRY_UNUSABLE
        return roleOf(dir)
    }

    /** 入口字段无效时返回的 role —— §20.2:619「报缺，而不是兜底到另一个目录去猜一个」。
     *  ★ 而**判据只有一条**（§20.2:621 的机械形式）：入口指向的那份字节能被当程序调起吗？
     *    不能 ⇒ 它不是入口 ⇒ 该单元无入口 ⇒ 报缺。
     *  ★ 「有无人引用」不在这条判据里 —— 07 今天真跑读数：jq 那件入口指向 `lib/liblobosjq.so`（-rw-------）
     *    而它 **exit=0 能调起**；crypto/curl/openssl/zlib 四件 exit=139。
     *  ★ 而**不兜底**是这一条的全部要点（§20.2:625）：一旦允许「换个目录猜一个」，
     *    §10.1:128 刚判为无效的「看目录名」那条读法就从后门回来。 */
    const val ENTRY_UNUSABLE = "entry-unusable"

    /** entry 字段的有效性：**主语是它指向的那份字节**（§20.2:618「有效性由那份字节自己回答」）。
     *  @return 有效时返回该 entry（相对 verDir），无效时返回 null —— 调用方据此报缺，不猜。
     *
     *  ★ **缺陷名（C-102）拿目录名当入口**：原来 [entryOf] 先看 `bin/` 下有没有文件，再看有没有 `.so`；
     *    而 §10.1:128 自己写着「同一个目录名下两种形态并存」—— 目录名是我们自己起的。
     *    现在它只问一件事：清单 entry 指的那份，今天能不能被当程序调起。 */
    fun usableEntryOf(verDir: File, declaredEntry: String): String? {
        if (declaredEntry.isBlank()) return null
        val target = File(verDir, declaredEntry)
        if (!target.isFile) return null
        return if (lobos.kernel.elf.ExecBits.isRunnableByForm(target)) declaredEntry else null
    }

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

    data class Verdict(val ok: Boolean, val checked: Int, val mismatched: List<String>)

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
        return Verdict(bad.isEmpty(), e.files.size, bad)
    }

    fun verifyAll(ctx: Context): Map<String, Verdict> {
        val out = linkedMapOf<String, Verdict>()
        for (e in ProgramIndex.all(ctx)) {
            out[e.id] = verify(ctx, e.id)
        }
        return out
    }
    fun landedId(path: String): String? {
        val p = path.replace(Regex("/+"), "/")
        val m = Regex("^(usr/)?opt/([^/]+)/").find(p) ?: return null
        return m.groupValues[2].ifBlank { null }
    }

    fun packagedIds(ctx: Context): Set<String> {
        val dir = "pieces"
        val names = runCatching { ctx.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        return names.mapNotNull { n ->
            val b = n.substringBeforeLast('.')
            b.takeIf { it.isNotBlank() && it != n }
        }.toSet()
    }

    fun landedPieceDir(ctx: Context, id: String): File? {
        val seg = id.replace("/", "")
        if (seg.isBlank()) return null
        val base = SystemDirs.opt(ctx).resolve(seg)
        if (!base.isDirectory) return null
        val vers = base.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
            ?: return null
        for (v in vers) {
            if (File(v, "component-meta.json").isFile) return v
        }
        return null
    }

    /** 这一件在盘上**有没有可执行形态的那份字节**（`UnitEntry.levelOf` 读的就是它）。
     *  ★ 与 [roleOf] 同一把尺（形态），同一处扫描（任何一层，不只 `bin/`）——
     *    缺陷名（C-102）同一问题两处读法：`levelOf` 走这一条、`roleOf` 走 roleOf，
     *    而这一条原来只看 `bin/` 且用的是含执行位的那把。
     *  ★ 找不到落位目录时答 false（没有字节就没有答案），不猜。 */
    fun runnableOnDisk(ctx: Context, id: String): Boolean {
        val dir = landedPieceDir(ctx, id) ?: return false
        return runnableEntryOf(dir) != null
    }

    fun isLandedPiece(ctx: Context, id: String): Boolean =
        landedPieceDir(ctx, id) != null

    fun pieceDir(ctx: Context, id: String): File? = landedPieceDir(ctx, id)

    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? =
        landedPieceDir(ctx, id)?.let { metaOf(it) }
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

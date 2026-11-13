package lobos.services.supply

import android.content.Context
import java.io.File
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitEntry
import lobos.services.reg.Level
import lobos.services.reg.edited
import lobos.services.log.RuntimeDiagnostics

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

    fun scan(
        ctx: Context,
        withContentFingerprint: Boolean = true,
        traverseFiles: Boolean = true,
    ): List<Found> {
        val lib = SystemDirs.opt(ctx)
        val kids = lib.listFiles() ?: return emptyList()
        val out = mutableListOf<Found>()
        for (pieceDir in kids) {
            if (!pieceDir.isDirectory) continue
            val id = pieceDir.name
            if (File(pieceDir, "include").isDirectory) {
                val hf = filesOf(pieceDir, withContentFingerprint, traverseFiles)
                out += Found(id, "from-layout", pieceDir, "include", HEADERS,
                    sha256Of(pieceDir, hf), metaOf(pieceDir), hf)
                continue
            }
            for (verDir in pieceDir.listFiles() ?: emptyArray()) {
                if (!verDir.isDirectory) continue
                val version = verDir.name

                val declared = metaOf(verDir)?.optString("entry", "").orEmpty()
                val role = roleOf(verDir)

                val entry = entryOf(verDir, declared)
                val libraryShaped = role == LIBRARY
                if (entry == null && !libraryShaped) continue
                val files = filesOf(verDir, withContentFingerprint, traverseFiles)
                out += Found(
                    id = id,
                    version = version,
                    dir = verDir,
                    entry = entry.orEmpty(),
                    role = role,
                    sha256 = sha256Of(verDir, files),
                    meta = metaOf(verDir),
                    files = files,
                )
            }
        }
        return out.sortedBy { it.id }
    }

    private fun entryOf(verDir: File, declared: String): String? =
        usableEntryOf(verDir, declared)

    private fun roleOf(verDir: File): String {
        val declared = metaOf(verDir)?.optString(ROLE_KEY, "").orEmpty().trim()
        if (declared.isNotBlank()) return declared

        val manifest = manifestOf(verDir)
        val mRole = manifest?.optString(ROLE_KEY, "").orEmpty().trim()
        return mRole.ifBlank { ENTRY_UNUSABLE }
    }

    private fun manifestOf(verDir: File): JSONObject? {
        val f = File(verDir, lobos.services.supply.ProgramDir.MANIFEST_NAME)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    const val ROLE_KEY = "role"

    fun roleFromDisk(ctx: Context, id: String): String {

        val dir = landedPieceDir(ctx, id)
            ?: return ENTRY_UNUSABLE
        return roleOf(dir)
    }

    const val ENTRY_UNUSABLE = "entry-unusable"

    fun usableEntryOf(verDir: File, declaredEntry: String): String? {
        if (declaredEntry.isBlank()) return null
        val target = File(verDir, declaredEntry)
        if (!target.isFile) return null
        return declaredEntry
    }

    private fun metaOf(verDir: File): JSONObject? {
        val f = File(verDir, "component-meta.json")
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun relativeStateDir(ctx: Context, dir: File): String {

        val filesRoot = ctx.filesDir.canonicalFile.path
        val optRoot = SystemDirs.opt(ctx).canonicalFile.path
        val d = runCatching { dir.canonicalFile.path }.getOrDefault(dir.absolutePath)
        if (d.startsWith(optRoot + File.separator)) {

            val id = d.substring(optRoot.length + 1).substringBefore(File.separatorChar)
            if (id.isNotBlank()) return SystemDirs.relPieceDir(id)
        }
        if (d.startsWith(filesRoot + File.separator)) {
            return d.substring(filesRoot.length + 1).replace(File.separatorChar, '/')
        }

        return ""
    }

    private fun filesOf(
        verDir: File,
        withContentFingerprint: Boolean,
        traverseFiles: Boolean = true,
    ): List<UnitEntry.FileRec> {
        val base = verDir.absolutePath
        val out = mutableListOf<UnitEntry.FileRec>()
        if (!traverseFiles) {

            return out
        }
        verDir.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.name == "component-meta.json") return@forEach
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (rel.isEmpty()) return@forEach

            out += UnitEntry.FileRec(rel, if (withContentFingerprint) SupplySha.sha256(f) else "")
        }
        return out.sortedBy { it.path }
    }

    private fun sha256Of(verDir: File, files: List<UnitEntry.FileRec>): String {
        if (files.isEmpty()) return ""

        if (files.any { it.sha256.isEmpty() }) return ""
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
        val withContentFingerprint = lobos.services.reg.ScanCost.withContentFingerprint(ctx)

        RuntimeDiagnostics.append(
            ctx, "scan-cost", true, "那扫描成本那一档",
            lobos.services.reg.ScanCost.describe(ctx) +
                " （**重建索引**那一族逐字走这一档、**内容指纹**逐字" +
                (if (withContentFingerprint) "算" else "跳过") +
                "、**扫描**逐字仍然扫、**缺件**那一族逐字仍然答）",
        )

        val traverseFiles = lobos.services.reg.ScanCost.traverseFiles(ctx)

        val scanStartMs = System.currentTimeMillis()
        val found = scan(ctx, withContentFingerprint, traverseFiles)
        val scanMs = System.currentTimeMillis() - scanStartMs

        RuntimeDiagnostics.append(
            ctx, "scan-cost", true,
            "那一次扫描逐字耗时 " + scanMs + "ms",
            "遍历=" + (if (traverseFiles) "做" else "不做") +
                " 内容指纹=" + (if (withContentFingerprint) "算" else "跳过") +
                " 件数=" + found.size +
                " （**两档逐字分开**、档二逐字答「那遍历本身」、" +
                "档一逐字答「那内容指纹算不算」；**两档逐字不并**）",
        )
        if (found.isEmpty()) return 0
        val cur = ProgramIndex.all(ctx).associateBy { it.id }
        var changed = 0
        var removed = 0
        for (f in found) {
            val prev = cur[f.id]

            val roleFromScan = true
            if (prev != null && prev.role.isNotBlank() && f.role.isNotBlank() && prev.role != f.role) {
                RuntimeDiagnostics.append(
                    ctx, "registry-role", false,
                    "那登记那一格与那扫描结果逐字不一致",
                    "件=" + f.id + " 登记=" + prev.role + " 扫描=" + f.role +
                        " （扫描结果逐字**赢**、旧值逐字**不**顶上来；" +
                        "这**两份实际**逐字不一致那一族归 03 另立、它逐字是 C-998 那一族的**邻族**）",
                )
            }
            val entry = (prev ?: ProgramIndex.empty(f.id, Level.PIECE)).edited(
                roleFromScan = roleFromScan,
                version = f.version,

                stateDir = relativeStateDir(ctx, f.dir),
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

        val foundIds = found.map { it.id }.toSet()

        val optRootIds: Set<String> = runCatching {
            SystemDirs.opt(ctx).listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?.toSet()
        }.getOrNull() ?: emptySet()
        if (optRootIds.isEmpty()) {

            return changed
        }
        for (e in ProgramIndex.all(ctx)) {
            if (e.id in foundIds) continue

            if (ProgramIndex.remove(ctx, e.id)) {
                removed++
                RuntimeDiagnostics.append(
                    ctx, "scan", true, "登记里删掉（扫不到）" + e.id,
                    "这一件在落位目录里已经不存在，而登记里还有它那一行（  而 upsert 只换不删）",
                )
            }
        }
        return changed + removed
    }

    data class Verdict(val ok: Boolean, val checked: Int, val mismatched: List<String>)

    fun verify(ctx: Context, id: String): Verdict {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return Verdict(false, 0, emptyList())
        if (e.stateDir.isBlank()) return Verdict(false, 0, emptyList())
        if (e.files.isEmpty()) return Verdict(false, 0, emptyList())

        val base = landedPieceDir(ctx, id)
            ?: return Verdict(false, 0, listOf("找不到落位目录：" + e.stateDir + "/<版本>"))
        val bad = mutableListOf<String>()
        for (fr in e.files) {

            val raw = fr.path
            if (!raw.contains('/')) {
                bad += "登记里的 path 没有目录（无法判定它该落在哪）：" + raw
                continue
            }
            val rel = raw
            val f = File(base, rel)
            when {
                !f.isFile -> bad += "缺：" + rel
                SupplySha.sha256(f) != fr.sha256 -> bad += "被换过：" + rel
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

    fun runnableOnDisk(ctx: Context, id: String): Boolean {
        val dir = landedPieceDir(ctx, id) ?: return false
        val declared = metaOf(dir)?.optString(ROLE_KEY, "").orEmpty().trim()
        return declared.isNotBlank() && declared != LIBRARY && declared != HEADERS
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

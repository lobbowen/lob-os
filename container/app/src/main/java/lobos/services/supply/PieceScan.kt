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
        val lib = SystemDirs.lib(ctx)
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

    private fun entryOf(verDir: File): String? {
        val bin = File(verDir, "bin")
        if (bin.isDirectory) {
            bin.listFiles()?.firstOrNull { it.isFile }?.let { return "bin/" + it.name }
        }
        verDir.walkTopDown().maxDepth(2).firstOrNull { it.isFile && it.name.endsWith(".so") }
            ?.let { return it.relativeTo(verDir).path }
        return null
    }

    private fun roleOf(verDir: File): String {
        if (File(verDir, "bin").isDirectory) return EXEC
        return LIBRARY
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
        val m = Regex("^usr/lib/([^/]+)/").find(p) ?: return null
        return m.groupValues[1].ifBlank { null }
    }

    fun packagedIds(ctx: Context): Set<String> {
        val dir = "supply/meta"
        val names = runCatching { ctx.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val out = mutableSetOf<String>()
        names.filter { it.endsWith(".meta.json") }.forEach { n ->
            runCatching {
                val m = org.json.JSONObject(ctx.assets.open("$dir/$n").use { it.readBytes().toString(Charsets.UTF_8) })
                m.optString("id", "").takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out
    }

    fun pieceDir(ctx: Context, id: String): File? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank()) return null
        return File(e.stateDir)
    }

    fun pieceFile(ctx: Context, id: String): File? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank() || e.assetEntry.isBlank()) return null
        return File(File(e.stateDir), e.assetEntry)
    }

    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? {
        val e = ProgramIndex.get(ctx, id)?.takeIf { ProgramIndex.isPiece(ctx, id) } ?: return null
        if (e.stateDir.isBlank()) return null
        return metaOf(File(e.stateDir))
    }

    fun shellBin(ctx: Context): File? {
        fun landOf(e: lobos.services.reg.UnitEntry): File? =
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

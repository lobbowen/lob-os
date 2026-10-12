package lobos.services.reg

import android.content.Context
import java.io.File
import lobos.services.supply.CatalogClient
import lobos.services.supply.PieceScan
import lobos.services.app.PieceProvisioner
import lobos.services.supply.SupplyProvisioner

object InstalledRuntime {

    fun binOf(ctx: Context, id: String): File? {
        val version = ProgramManager.currentVersion(ctx, id)
        if (version != null) {
            val entry = CatalogClient.entryFor(ctx, id)?.optString("entry", "").orEmpty()
                .ifBlank { "bin/$id" }
            val candidate = File(File(ProgramManager.stateDirOf(ctx, id), version), entry)
            if (candidate.isFile) return candidate
        }
        return SupplyProvisioner.entryLink(ctx, id).takeIf { it.isFile }
    }

    fun versionOf(ctx: Context, id: String): String {
        val bin = binOf(ctx, id) ?: return ""
        val args = PieceScan.pieceMeta(ctx, id)?.optJSONArray("versionArgs")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        if (args.isEmpty()) return recordedVersion(ctx, id)
        val out = runCatching {
            val pb = ProcessBuilder(bin.absolutePath, *args.toTypedArray())
                .redirectErrorStream(true)
            val root = RuntimeEnvironment.treeRootFor(ctx)
            RuntimeEnvironment.treeRootEnv(ctx, root, null).forEach { (k, v) ->
                pb.environment()[k] = v
            }
            val p = pb.start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            if (p.exitValue() == 0) text.trim() else ""
        }.getOrNull().orEmpty()
        return extractVersion(out) ?: recordedVersion(ctx, id)
    }

    private fun recordedVersion(ctx: Context, id: String): String =
        SupplyProvisioner.selectedVersion(ctx, id)

    fun notInstalledHint(ctx: Context, id: String): String {
        val registered = ProgramIndex.get(ctx, id) != null
        val where = if (registered) "已登记但落位缺失" else "尚未安装"
        return "$id 未就位（$where）。它是系统里可安装的一件，" +
            "装完即全局可用（入口在 \$PREFIX/bin，与其余件走同一条路）；" +
            "去控制面板的系统更新里装，或在诊断页显式装。"
    }

    private fun extractVersion(raw: String): String? {
        if (raw.isBlank()) return null
        val m = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(raw) ?: return null
        return m.groupValues[1]
    }

    data class ProgramRuntime(val id: String, val path: File?, val version: String)

    fun programRuntime(ctx: Context): ProgramRuntime {
        val declared = ProgramIndex.all(ctx).firstOrNull { it.role == RUNTIME && it.enabled }
            ?: ProgramIndex.all(ctx).firstOrNull { it.enabled && it.role.isNotBlank() && it.role != LIBRARY }
        val id = declared?.id ?: RUNTIME
        return ProgramRuntime(id, binOf(ctx, id), versionOf(ctx, id))
    }

    const val RUNTIME = "runtime"
    const val LIBRARY = "library"
}
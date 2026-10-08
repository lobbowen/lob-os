package lobos.runtime

import android.content.Context
import java.io.File

object InstalledRuntime {

    fun binOf(ctx: Context, id: String): File? {
        val version = ProgramManager.currentVersion(ctx, id)
        if (version != null) {
            val entry = lobos.os.CatalogClient.entryFor(ctx, id)?.optString("entry", "").orEmpty()
                .ifBlank { "bin/$id" }
            val candidate = File(File(ProgramManager.stateDirOf(ctx, id), version), entry)
            if (candidate.isFile) return candidate
        }
        return SupplyProvisioner.entryLink(ctx, id).takeIf { it.isFile }
    }

    fun versionOf(ctx: Context, id: String): String {
        val bin = binOf(ctx, id) ?: return ""
        val args = lobos.native.NativeAssetRegistry.of(id)?.versionArgs ?: emptyList()
        if (args.isEmpty()) return recordedVersion(ctx, id)
        val out = runCatching {
            val pb = ProcessBuilder(bin.absolutePath, *args.toTypedArray())
                .redirectErrorStream(true)
            val prior = pb.environment()["LD_LIBRARY_PATH"].orEmpty()
            pb.environment()["LD_LIBRARY_PATH"] =
                listOf(prior, lobos.native.NativePreparer.libSearchPath(ctx))
                    .filter { it.isNotBlank() }
                    .joinToString(File.pathSeparator)
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
        val registered = lobos.os.ProgramIndex.get(ctx, id) != null
        val where = if (registered) "已登记但落位缺失" else "尚未安装"
        return "$id 未就位（$where）。它是系统里可安装的一件，" +
            "装完即全局可用（入口在 \$PREFIX/bin，与其余件走同一条路）；" +
            "去控制面板的系统更新或应用商店装，或在诊断页显式装。"
    }

    private fun extractVersion(raw: String): String? {
        if (raw.isBlank()) return null
        val m = Regex("""(\d+\.\d+(?:\.\d+)?)""").find(raw) ?: return null
        return m.groupValues[1]
    }
}
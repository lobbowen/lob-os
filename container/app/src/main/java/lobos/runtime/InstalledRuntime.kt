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
        // 「跑什么参数能问出这件的版本」是件自己声明的（component-meta.json）
        val args = lobos.os.PieceScan.pieceMeta(ctx, id)?.optJSONArray("versionArgs")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        } ?: emptyList()
        if (args.isEmpty()) return recordedVersion(ctx, id)
        val out = runCatching {
            val pb = ProcessBuilder(bin.absolutePath, *args.toTypedArray())
                .redirectErrorStream(true)
            val prior = pb.environment()["LD_LIBRARY_PATH"].orEmpty()
            pb.environment()["LD_LIBRARY_PATH"] =
                listOf(prior, lobos.pieces.PieceProvisioner.libSearchPath(ctx))
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

    /**
     * 「跑程序的那个运行时」是哪个 —— 不写死是node。
     *
     * 此前控制面板的 os.runtime.status 直接问 node：把「运行时 = node」
     * 写进了桥接口。换一个运行时（deno、bun 或自研）就得改内核代码。
     * 现在按注册表声明的用途找：程序启动时需要的那种件。
     *
     * 找不到时退回第一个已装的runtime 角色的件；都没有则返回一个不存在的 id，
     * 调用方拿 binOf 得到 null，按「运行时未安装」处理。
     */
    data class ProgramRuntime(val id: String, val path: File?, val version: String)

    fun programRuntime(ctx: Context): ProgramRuntime {
        val declared = ProgramIndex.all(ctx).firstOrNull { it.role == RUNTIME && it.enabled }
            ?: ProgramIndex.all(ctx).firstOrNull { it.enabled && it.role.isNotBlank() && it.role != LIBRARY }
        val id = declared?.id ?: RUNTIME
        return ProgramRuntime(id, binOf(ctx, id), versionOf(ctx, id))
    }

    /** 注册表里声明为「跑程序的那个」的 role —— node 现在是，将来换了也只改那一行的数据 */
    const val RUNTIME = "runtime"
    const val LIBRARY = "library"
}
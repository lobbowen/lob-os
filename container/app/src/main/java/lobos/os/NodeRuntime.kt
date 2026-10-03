package lobos.os

import android.content.Context
import java.io.File
import lobos.native.NativeAssetRegistry

object NodeRuntime {

    fun path(ctx: Context): File? =
        ProgramManager.nodeBin(ctx)
            ?: lobos.runtime.SupplyProvisioner.entryLink(ctx, NAME).takeIf { it.isFile }
            ?: NativeAssetRegistry.resolve(ctx, NativeAssetRegistry.NODE).takeIf { it.isFile }

    fun missing(ctx: Context): String =
        "node 运行时未就位。开机时 SupplyProvisioner.ensure 会在后台线程从商店装它；" +
            "若装完仍缺，去面板的系统组件看商店供给的诊断（多半是清单过期或验签不过）"

    const val NAME = "node"

    fun version(ctx: Context): String {
        val bin = path(ctx) ?: return ""
        val out = runCatching {
            val p = ProcessBuilder(bin.absolutePath, "-p", "process.versions.node")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            if (p.exitValue() == 0) text.trim() else ""
        }.getOrNull().orEmpty()
        if (out.isNotBlank()) return out
        return ProgramManager.currentVersion(ctx, NAME).orEmpty()
    }
}


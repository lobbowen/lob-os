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
        "node 运行时未安装：请在控制面板的系统组件里安装 node（商店里的运行时包）"

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


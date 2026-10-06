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
            // 不带环境起 node 时，linker 会在进入 node 之前就因缺 libc++_shared.so 失败，
            // 这里拿到空串，诊断里表现为「Node 运行时版本=」空白。
            val pb = ProcessBuilder(bin.absolutePath, "-p", "process.versions.node")
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
        if (out.isNotBlank()) return out
        return ProgramManager.currentVersion(ctx, NAME).orEmpty()
    }
}


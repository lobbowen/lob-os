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
        "node 运行时未就位。node 是商店目录里的一类系统组件（runtime），" +
            "由装程序的安装器按该程序的 requires 拉取；" +
            "没装时去控制面板的应用商店装，或在诊断页显式装" +
            "（node 的 DT_RUNPATH 是 \$ORIGIN，它的 libc++_shared.so 必须与它在同一目录）"

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


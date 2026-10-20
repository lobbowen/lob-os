package lobos.kernel.layout

import android.content.Context
import java.io.File
import lobos.services.reg.RuntimeEnvironment

object SysEnv {

    const val FILE = "lobos-sysenv"

    const val ENV_POINTER = "LOBOS_SYSENV"

    val KEYS: List<String> = listOf(ENV_POINTER) + RuntimeEnvironment.RESERVED_ENV.sorted()

    fun file(ctx: Context): File = File(SystemDirs.etc(ctx), FILE)

    fun write(ctx: Context): Int {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            append("# 系统视图里要合并进子进程的键\n")
            append("# 键集由注册表持有：RuntimeEnvironment.RESERVED_ENV + " + ENV_POINTER + "\n")
            append("# 本文件只列键名，值由 RuntimeEnvironment.treeRootEnv 在起进程时算\n")
            for (k in KEYS) append(k).append('\n')
        })
        return KEYS.size
    }
}

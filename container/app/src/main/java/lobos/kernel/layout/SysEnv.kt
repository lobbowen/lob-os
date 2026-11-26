package lobos.kernel.layout

import java.io.File

object SysEnv {

    const val FILE = "lobos-sysenv"

    const val ENV_POINTER = "LOBOS_SYSENV"

    fun keys(): List<String> = listOf(ENV_POINTER) + lobos.kernel.spi.KernelServices.env().reservedKeys.sorted()

    fun file(): File = File(SystemDirs.etc(), FILE)

    fun write(): Int {
        val f = file()
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            append("# 系统视图里要合并进子进程的键\n")
            append("# 键集由注册表持有：RuntimeEnvironment.RESERVED_ENV + " + ENV_POINTER + "\n")
            append("# 本文件只列键名，值由 KernelServices.env().childEnv 在起进程时算\n")
            for (k in keys()) append(k).append('\n')
        })
        return keys().size
    }
}

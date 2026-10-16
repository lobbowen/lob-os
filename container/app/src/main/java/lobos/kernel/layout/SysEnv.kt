package lobos.kernel.layout

import android.content.Context
import java.io.File

object SysEnv {

    const val FILE = "lobos-sysenv"

    val KEYS: List<String> = listOf(
        "HOME",
        "TMPDIR",
        "LANG",
        "SHELL",
        "PATH",
        "LD_LIBRARY_PATH",
        "LD_PRELOAD",
        "SSL_CERT_DIR",
        "SSL_CERT_FILE",
        "CURL_CA_BUNDLE",
        "GIT_SSL_CAINFO",
        "LOBOS_SYSTEM_LINKER",
        "LOBOS_COMPAT_LOG",
    )

    fun file(ctx: Context): File = File(SystemDirs.etc(ctx), FILE)

    fun write(ctx: Context): Int {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            append("# 系统视图里要合并进子进程的键\n")
            append("# 与 " + KEYS.size + " 项一一对应\n")
            for (k in KEYS) append(k).append('\n')
        })
        return KEYS.size
    }
}

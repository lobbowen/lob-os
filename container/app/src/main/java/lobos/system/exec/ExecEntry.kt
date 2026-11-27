package lobos.system.exec

import java.io.File
import java.nio.file.Files
import lobos.system.exec.ElfFacts

object ExecEntry {

    const val SYSTEM_LINKER = "/system/bin/linker64"

    // glibc guest 运行时的 loader/lib 路径。由 RuntimeEnvironment.treeRootEnv 在
    // app 进程内设置（argv() 是 app 进程调用的，System.getenv 读不到注入给子进程的值）。
    @Volatile private var guestLoader: String? = null
    @Volatile private var guestLibDir: String? = null
    fun configureGlibcGuest(loader: String?, libDir: String?) {
        guestLoader = loader; guestLibDir = libDir
    }

    sealed class Shape {
        object Elf : Shape()
        object Exec : Shape()
        data class Shebang(val interp: String, val flags: String?) : Shape()

        object Pe : Shape()
        object Unknown : Shape()
    }

    private const val SHEBANG_MAX = 256
    private const val ELF_MAGIC = 0x7F
    private const val ELF_ET_EXEC = 2
    private const val ELF_ET_DYN = 3

    private const val PE_MAGIC_0 = 0x4D
    private const val PE_MAGIC_1 = 0x5A
    private const val PE_LFANEW_OFFSET = 0x3C
    private const val PE_SIGNATURE_0 = 0x50
    private const val PE_SIGNATURE_1 = 0x45

    private fun elfType(b: ByteArray, n: Int): Int {
        if (n < 18) return -1
        if ((b[0].toInt() and 0xFF) != ELF_MAGIC) return -1
        if ((b[1].toInt() and 0xFF) != 0x45 ||
            (b[2].toInt() and 0xFF) != 0x4C ||
            (b[3].toInt() and 0xFF) != 0x46) return -1
        return (b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8)
    }

    fun detect(entry: File): Shape {
        if (!entry.isFile) return Shape.Unknown
        val head = runCatching {
            entry.inputStream().buffered().use { inp ->
                val b = ByteArray(SHEBANG_MAX)
                val n = inp.read(b)
                if (n <= 0) return Shape.Unknown
                val t = elfType(b, n)
                if (t == ELF_ET_EXEC) return Shape.Exec
                if (t == ELF_ET_DYN) return Shape.Elf
                String(b, 0, n, Charsets.ISO_8859_1)
            }
        }.getOrNull() ?: return Shape.Unknown

        if (head.length >= PE_LFANEW_OFFSET + 4 &&
            (head[0].code and 0xFF) == PE_MAGIC_0 &&
            (head[1].code and 0xFF) == PE_MAGIC_1
        ) {
            val lfanew = (head[PE_LFANEW_OFFSET].code and 0xFF) or
                ((head[PE_LFANEW_OFFSET + 1].code and 0xFF) shl 8) or
                ((head[PE_LFANEW_OFFSET + 2].code and 0xFF) shl 16) or
                ((head[PE_LFANEW_OFFSET + 3].code and 0xFF) shl 24)
            if (lfanew >= 4 && head.length >= lfanew + 4 &&
                (head[lfanew].code and 0xFF) == PE_SIGNATURE_0 &&
                (head[lfanew + 1].code and 0xFF) == PE_SIGNATURE_1
            ) {
                return Shape.Pe
            }
        }

        if (head.startsWith("#!")) {
            val line = head.substring(2).lineSequence().firstOrNull { it.isNotBlank() }
                ?: return Shape.Unknown
            val t2 = line.trim()
            if (t2.isEmpty()) return Shape.Unknown
            val sp = t2.indexOf(' ')
            return if (sp < 0) Shape.Shebang(t2, null)
            else Shape.Shebang(t2.substring(0, sp), t2.substring(sp + 1).trim().ifBlank { null })
        }
        return Shape.Unknown
    }

    fun argv(entry: File, args: List<String>): List<String>? = when (val s = detect(entry)) {
        /* ET_EXEC 也可能带 PT_INTERP=ld-linux（Bun/Claude 官方原生二进制即如此）。
           必须与 ET_DYN 一样走 glibc guest loader；否则内核按 PT_INTERP 找
           /lib/ld-linux-aarch64.so.1 系统路径，guest 的 handler 与库路径全丢，
           原生程序一进初始化就 SIGSYS。 */
        is Shape.Exec -> glibcGuestArgv(entry, args) ?: (listOf(entry.absolutePath) + args.drop(1))

        is Shape.Elf -> glibcGuestArgv(entry, args) ?: run {
            if (entry.absolutePath == SYSTEM_LINKER) listOf(entry.absolutePath) + args.drop(1)
            else listOf(SYSTEM_LINKER, entry.absolutePath) + args.drop(1)
        }
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args

        is Shape.Pe -> null
        is Shape.Unknown -> null
    }

    /**
     * glibc guest 路由：判据只落在 PT_INTERP（不用 e_type/DT_NEEDED——那会误伤 bionic）。
     * PT_INTERP 含 ld-linux 且 RuntimeEnvironment 递来了 loader 路径时，
     * 改写成 [guest-loader, --library-path, <libdir>, <程序>, 原参数...]。
     * 否则返回 null（走调用方原有 bionic 分支）。
     */
    private fun glibcGuestArgv(entry: File, args: List<String>): List<String>? {
        val interp = runCatching { ElfFacts.read(entry)?.interp }.getOrNull() ?: return null
        if (!interp.contains("ld-linux")) return null
        val loader = guestLoader ?: System.getenv("LOBOS_GLIBC_LOADER") ?: return null
        val libdir = guestLibDir ?: System.getenv("LOBOS_GLIBC_LIBDIR") ?: return null
        if (!File(loader).isFile) return null
        return listOf(loader, "--library-path", libdir, entry.absolutePath) + args.drop(1)
    }

    /** Whether a file has a runnable shape and the exec bit (used by layout checks). */
    fun isRunnable(file: File): Boolean {
        if (!file.isFile) return false
        if (detect(file) == Shape.Unknown) return false
        return file.canExecute()
    }

    fun describe(entry: File): String = when (val s = detect(entry)) {
        is Shape.Elf -> "ELF 动态可执行文件（ET_DYN），经 " + SYSTEM_LINKER + " 驱动"
        is Shape.Exec -> "ELF 静态可执行文件（ET_EXEC），直接 exec，不经 linker"
        is Shape.Shebang -> "#! " + s.interp + (s.flags?.let { " $it" } ?: "") + " —— 用它跑"
        is Shape.Pe ->
            "PE 可执行文件（MZ + PE 签名）—— 那内核没有那 Windows 那一族的加载器，" +
                "所以这个入口跑不起来。"
        is Shape.Unknown ->
            "既不是 ELF 也没有 #! —— 内核会回 ENOEXEC（这个入口无法执行）。" +
                "要用解释器跑，请在文件头加一行 #!<解释器路径>"
    }
}

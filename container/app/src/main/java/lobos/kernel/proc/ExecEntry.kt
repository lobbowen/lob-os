package lobos.kernel.proc

import java.io.File
import java.nio.file.Files

object ExecEntry {

    const val SYSTEM_LINKER = "/system/bin/linker64"

    sealed class Shape {
        object Elf : Shape()
        object Exec : Shape()
        data class Shebang(val interp: String, val flags: String?) : Shape()
        object Unknown : Shape()
    }

    private const val SHEBANG_MAX = 256
    private const val ELF_MAGIC = 0x7F
    private const val ELF_ET_EXEC = 2
    private const val ELF_ET_DYN = 3

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
        is Shape.Exec -> listOf(entry.absolutePath) + args.drop(1)
        // 缺陷（C-102）**重复替换：已替换过的 argv 又被替换一次**。
        // 真机读数（07，logcat）：Cmdline = /system/bin/linker64 /system/bin/linker64 …/opt/bash/…/bash
        // —— linker64 出现两次。多出来的这一次来自**第二次调用本函数**（上游已替过）。
        // 而 linker64 自己也是 ET_DYN（07 那一份：ELF 64-bit LSB shared object）⇒ 本函数会再替一次。
        // linker64 把自己当程序加载 ⇒ call_constructors → libc 初始化链崩
        //（kill-audit 记 desc=null pointer dereference）。
        //
        // 而这一支**不能删**（ET_DYN 要经 linker64 驱动 —— 唯一真相源是本函数，native 那份已删，C-87），
        // 而原生那一支（container/native/d1/exec-path.c:430）本来就有这个门：
        //   etype==ET_DYN **且** path 不是 SYSTEM_LINKER 本身。
        // ⇒ 而这里补上同一道门，宿主侧与原生侧口径一致。
        is Shape.Elf ->
            if (entry.absolutePath == SYSTEM_LINKER) listOf(entry.absolutePath) + args.drop(1)
            else listOf(SYSTEM_LINKER, entry.absolutePath) + args.drop(1)
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args
        is Shape.Unknown -> null
    }

    fun describe(entry: File): String = when (val s = detect(entry)) {
        is Shape.Elf -> "ELF 动态可执行文件（ET_DYN），经 " + SYSTEM_LINKER + " 驱动"
        is Shape.Exec -> "ELF 静态可执行文件（ET_EXEC），直接 exec，不经 linker"
        is Shape.Shebang -> "#! " + s.interp + (s.flags?.let { " $it" } ?: "") + " —— 用它跑"
        is Shape.Unknown ->
            "既不是 ELF 也没有 #! —— 内核会回 ENOEXEC（这个入口无法执行）。" +
                "要用解释器跑，请在文件头加一行 #!<解释器路径>"
    }
}

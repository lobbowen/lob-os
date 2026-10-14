package lobos.kernel.proc

import java.io.File
import java.nio.file.Files

object ExecEntry {

    const val SYSTEM_LINKER = "/system/bin/linker64"

    sealed class Shape {
        object Elf : Shape()
        data class Shebang(val interp: String, val flags: String?) : Shape()
        object Unknown : Shape()
    }

    private const val SHEBANG_MAX = 256

    fun detect(entry: File): Shape {
        if (!entry.isFile) return Shape.Unknown
        val head = runCatching {
            entry.inputStream().buffered().use { inp ->
                val b = ByteArray(SHEBANG_MAX)
                val n = inp.read(b)
                if (n <= 0) return Shape.Unknown
                String(b, 0, n, Charsets.ISO_8859_1)
            }
        }.getOrNull() ?: return Shape.Unknown

        if (head.startsWith("\u007fELF")) return Shape.Elf
        if (!head.startsWith("#!")) return Shape.Unknown

        val line = head.substring(2).lineSequence().firstOrNull { it.isNotBlank() }
            ?: return Shape.Unknown
        val t = line.trim()
        if (t.isEmpty()) return Shape.Unknown
        val sp = t.indexOf(' ')
        return if (sp < 0) Shape.Shebang(t, null)
        else Shape.Shebang(t.substring(0, sp), t.substring(sp + 1).trim().ifBlank { null })
    }

    fun argv(entry: File, args: List<String>): List<String>? = when (val s = detect(entry)) {
        is Shape.Elf -> listOf(SYSTEM_LINKER, entry.absolutePath) + args.drop(1)
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args
        is Shape.Unknown -> null
    }

    fun describe(entry: File): String = when (val s = detect(entry)) {
        is Shape.Elf -> "ELF 可执行文件，直接 exec"
        is Shape.Shebang -> "#! " + s.interp + (s.flags?.let { " $it" } ?: "") + " —— 用它跑"
        is Shape.Unknown ->
            "既不是 ELF 也没有 #! —— 内核会回 ENOEXEC（这个入口无法执行）。" +
                "要用解释器跑，请在文件头加一行 #!<解释器路径>"
    }
}

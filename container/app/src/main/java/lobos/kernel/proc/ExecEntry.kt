package lobos.kernel.proc

import java.io.File
import java.nio.file.Files

object ExecEntry {

    sealed class Shape {
        object Elf : Shape()

        data class Shebang(val interp: String, val flags: String?) : Shape()

        object ShellLike : Shape()

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

        if (head.startsWith("#!")) {
            val line = head.substring(2).lineSequence().firstOrNull { it.isNotBlank() } ?: return Shape.Unknown
            val t = line.trim()
            if (t.isEmpty()) return Shape.Unknown
            val sp = t.indexOf(' ')
            return if (sp < 0) Shape.Shebang(t, null)
            else Shape.Shebang(t.substring(0, sp), t.substring(sp + 1).trim().ifBlank { null })
        }

        val firstCmd = head.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return Shape.Unknown
        val looksShell = listOf("sh ", "bash ", "echo ", "exit ", "export ", "cd ")
            .any { firstCmd.startsWith(it) }
        return if (looksShell) Shape.ShellLike else Shape.Unknown


    fun argv(entry: File, args: List<String>): List<String>? = when (val s = detect(entry)) {
        is Shape.Elf -> listOf(entry.absolutePath) + args
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args
        is Shape.ShellLike -> listOf(shellPath(), entry.absolutePath) + args
        is Shape.Unknown -> null
    }

    private fun shellPath(): String {
        val busy = runCatching {
            val dir = java.io.File("/data/data/lobos.app/files/usr/bin")
            File(dir, "busybox").takeIf { it.exists() }?.absolutePath
        }.getOrNull()
        return busy ?: "/system/bin/sh"
    }
}

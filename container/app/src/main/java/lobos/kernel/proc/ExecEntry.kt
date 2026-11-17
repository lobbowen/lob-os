package lobos.kernel.proc

import java.io.File
import java.nio.file.Files

object ExecEntry {

    const val SYSTEM_LINKER = "/system/bin/linker64"

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

    private fun elfInterp(entry: File): String? = runCatching {
        val raf = java.io.RandomAccessFile(entry, "r")
        raf.use { f ->
            val head = ByteArray(4096)
            val n = f.read(head)
            if (n < 64) return@use null
            if ((head[0].toInt() and 0xFF) != ELF_MAGIC) return@use null
            val ePhoff = (head[32].toInt() and 0xFF) or ((head[33].toInt() and 0xFF) shl 8) or
                ((head[34].toInt() and 0xFF) shl 16) or ((head[35].toInt() and 0xFF) shl 24)
            val ePhentsize = (head[54].toInt() and 0xFF) or ((head[55].toInt() and 0xFF) shl 8)
            val ePhnum = (head[56].toInt() and 0xFF) or ((head[57].toInt() and 0xFF) shl 8)
            for (i in 0 until ePhnum) {
                val off = ePhoff + i * ePhentsize
                if (off + 32 > n) break
                val pType = (head[off].toInt() and 0xFF) or ((head[off + 1].toInt() and 0xFF) shl 8) or
                    ((head[off + 2].toInt() and 0xFF) shl 16) or ((head[off + 3].toInt() and 0xFF) shl 24)
                if (pType != 3) continue
                val pOffset = (head[off + 8].toInt() and 0xFF) or ((head[off + 9].toInt() and 0xFF) shl 8) or
                    ((head[off + 10].toInt() and 0xFF) shl 16) or ((head[off + 11].toInt() and 0xFF) shl 24)
                val pFilesz = (head[off + 32].toInt() and 0xFF) or ((head[off + 33].toInt() and 0xFF) shl 8) or
                    ((head[off + 34].toInt() and 0xFF) shl 16) or ((head[off + 35].toInt() and 0xFF) shl 24)
                val b = ByteArray(pFilesz.coerceAtMost(512))
                f.seek(pOffset.toLong())
                f.readFully(b)
                val str = String(b, Charsets.UTF_8).substringBefore('\u0000')
                if (str.isNotBlank()) return@use str
            }
            null
        }
    }.getOrNull()

    private fun muslLinker(entry: File): File? {
        var dir: File? = entry.absoluteFile.parentFile
        while (dir != null) {
            val candidate = File(dir, "usr/lib/ld-musl-aarch64.so.1")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }

    fun linkerFor(entry: File): String {
        val interp = elfInterp(entry).orEmpty()
        if (interp.contains("musl")) {
            muslLinker(entry)?.let { return it.absolutePath }
        }
        return SYSTEM_LINKER
    }

    fun argv(entry: File, args: List<String>): List<String>? = when (val s = detect(entry)) {
        is Shape.Exec -> listOf(entry.absolutePath) + args.drop(1)

        is Shape.Elf -> {
            val linker = linkerFor(entry)
            if (entry.absolutePath == linker) listOf(entry.absolutePath) + args.drop(1)
            else listOf(linker, entry.absolutePath) + args.drop(1)
        }
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args

        is Shape.Pe -> null
        is Shape.Unknown -> null
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

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
    private const val ELF_CLASS64 = 2
    private const val ELF_DATA_LSB = 1
    private const val PT_INTERP = 3L

    private fun elfType(b: ByteArray, n: Int): Int {
        if (n < 18) return -1
        if ((b[0].toInt() and 0xFF) != ELF_MAGIC) return -1
        if ((b[1].toInt() and 0xFF) != 0x45 ||
            (b[2].toInt() and 0xFF) != 0x4C ||
            (b[3].toInt() and 0xFF) != 0x46) return -1
        return (b[16].toInt() and 0xFF) or ((b[17].toInt() and 0xFF) shl 8)
    }

    /** 那个入口自己带不带 PT_INTERP（程序解释器那一行）。
     *
     *  **带** => 内核 exec 它时会按 PT_INTERP 套一层解释器（argv = [interp, path, args...]），
     *  所以 LOB-OS 那一侧**不该再套** —— 而再套就是两层，
     *  而那两层会让 linker64 把自己当程序加载（真机读数：Cmdline 出现两次 /system/bin/linker64）。
     *  **不带** => 它是共享库那一类（ET_DYN 而没有 PT_INTERP），直接 exec 会 ENOEXEC，
     *  所以要显式套 SYSTEM_LINKER。
     *
     *  **读法**：读那份字节的 program header 表，找 PT_INTERP(3) 那一行 ——
     *  而**不读那一行指向的字符串**（判据只问「有没有那一行」）。
     */
    fun hasInterp(entry: File): Boolean = try {
        java.io.RandomAccessFile(entry, "r").use { f ->
            val eh = ByteArray(64)
            if (f.read(eh) < 64) return false
            if ((eh[0].toInt() and 0xFF) != ELF_MAGIC) return false
            if ((eh[4].toInt() and 0xFF) != ELF_CLASS64) return false
            if ((eh[5].toInt() and 0xFF) != ELF_DATA_LSB) return false

            fun u16(o: Int): Int = (eh[o].toInt() and 0xFF) or ((eh[o + 1].toInt() and 0xFF) shl 8)
            fun u64(o: Int): Long {
                var v = 0L
                for (i in 7 downTo 0) v = (v shl 8) or (eh[o + i].toLong() and 0xFF)
                return v
            }

            val phOff = u64(0x20)
            val phEnt = u16(0x36)
            val phNum = u16(0x38)
            if (phOff <= 0 || phEnt < 56 || phNum <= 0) return false

            f.seek(phOff)
            val ph = ByteArray(phEnt.toInt() * phNum)
            if (f.read(ph) < ph.size) return false
            for (i in 0 until phNum) {
                val o = i * phEnt
                val t = (ph[o].toLong() and 0xFF) or
                    ((ph[o + 1].toLong() and 0xFF) shl 8) or
                    ((ph[o + 2].toLong() and 0xFF) shl 16) or
                    ((ph[o + 3].toLong() and 0xFF) shl 24)
                if (t == PT_INTERP) return true
            }
            false
        }
    } catch (_: Throwable) {
        false
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
        // 缺陷（C-102）**该套解释器的那一层套了两层**。
        // 真机读数（07，logcat）：Cmdline = /system/bin/linker64 /system/bin/linker64 …/opt/bash/…/bash
        // —— linker64 出现两次；而那三件（bash / jq / ripgrep）自己的入口都带
        //    PT_INTERP = /system/bin/linker64（逐字相同），而它们自身是 ET_DYN。
        //
        // **两个来源叠在一起才出那个形态**（C-135：两问，两问都成立）：
        //  ① LOB-OS 显式套的那一层：这一支无条件加 SYSTEM_LINKER；
        //  ② 内核按 PT_INTERP 套的那一层：内核 exec 一个带 PT_INTERP 的文件时，
        //     自己 exec 那条 PT_INTERP（argv = [interp, path, args...]）。
        // 而 ① 不该有 —— **判据是「那个入口自己带不带 PT_INTERP」**：
        //   带 => 内核会套一层，LOB-OS 这一侧**不该再套**（套两层 => linker64 把自己当程序加载
        //        => call_constructors => libc 初始化链崩，AOSP linker_main.cpp:358 那一处注释
        //        写的正是「the linker attempts to execute itself」）；
        //   不带 => 它是共享库那一类（ET_DYN 而没有 PT_INTERP），直接 exec 会 ENOEXEC，
        //        所以要显式套 SYSTEM_LINKER。
        //
        // 而这一支**不能删**（ET_DYN 要经 linker64 驱动 —— 唯一真相源是本函数，native 那份已删，C-87）。
        // 而原生那一支（container/native/d1/exec-path.c:429）问的是 etype==ET_DYN ——
        //   它那一侧**没有** PT_INTERP 那一问，**而那是因为它走的是 LD_PRELOAD 注入的 execve**，
        //   **而那条路上内核已经按 PT_INTERP 套过了**（execve 的是那个入口，argv 已是 [interp, path…]）。
        is Shape.Elf ->
            when {
                // 已经替过的那一次（argv[0] 已是 linker64）—— 不再替。
                entry.absolutePath == SYSTEM_LINKER -> listOf(entry.absolutePath) + args.drop(1)
                // 入口自己带 PT_INTERP => 内核会套一层 => 这一侧不套。
                hasInterp(entry) -> listOf(entry.absolutePath) + args.drop(1)
                // 共享库那一类（ET_DYN 而没有 PT_INTERP）=> 要显式套。
                else -> listOf(SYSTEM_LINKER, entry.absolutePath) + args.drop(1)
            }
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

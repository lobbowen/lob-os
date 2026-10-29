package lobos.kernel.proc

import java.io.File
import java.nio.file.Files

object ExecEntry {

    const val SYSTEM_LINKER = "/system/bin/linker64"

    sealed class Shape {
        object Elf : Shape()
        object Exec : Shape()
        data class Shebang(val interp: String, val flags: String?) : Shape()
        /** 那 **PE** 那一族（=> 而那 **MZ** **那两个**字节 **与**那 **PE\u0000\u0000** **那个**偏移**）。 */
        object Pe : Shape()
        object Unknown : Shape()
    }

    private const val SHEBANG_MAX = 256
    private const val ELF_MAGIC = 0x7F
    private const val ELF_ET_EXEC = 2
    private const val ELF_ET_DYN = 3
    //  => 而那 **PE** **那**两个**字节**（=> 而那 **MZ**）**与**那 **PE** **那个**签名**在**哪**一格**。
    //    => 而那 **0x3C** 那一格逐字**是**那 **e_lfanew** 的**值**（=> 而那**它**逐字**是**那 PE 头**相对**那 MZ 头**的**偏移**）、
    //      => 而而那**它**逐字**不**是**那 PE 签名**在**哪**一格**、=> 而而那**它**逐字**是**那 PE 签名**在**哪**一格**的**指针**。
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

        //  缺陷名（C-102）**那 PE 那一档**逐字**把**那 P **读**在**那**固定**的 0x3C、
        //    => 而和那**它**逐字**没有**答**那**一件事**、=> 而和那那**后果**逐字 = 而那 PE 那一族逐字**认不出**那 PE 那一族**。
        //    => 而那**成因**：=> 而那 **0x3C** 那一格逐字**是**那 **e_lfanew** 的**值**（=> 而那**它**逐字**是**那 PE 头
        //      **相对**那 MZ 头**的**偏移**）、=> 而而那**它**逐字**不**是**那 PE 签名**在**哪**一格**、
        //      => 而而那**它**逐字**是**那 PE 签名**在**哪**一格**的**指针**、=> 而那**签名**逐字**在**那 e_lfanew **那一处**。
        //    => 而那**处置**：=> 而那 **PE** **那个**签名逐字**读**在**那**相对**的**那一处**（=> 而那**它**逐字**先**读**那 e_lfanew
        //      **那**四个**字节**、=> 而而那**它**逐字**再**读**那 e_lfanew **那一处**的**那**四个**字节**）。
        //    => 而那**它**逐字**在**那 **ELF** **判定**之后**、**在**那 **#!** **判定**之前**判**。
        //    => 而那**它**逐字**是**那**形状识别**那一层、=> 而和那**它**逐字**不是**那**执行**那一层**。
        //    => 而和那**它**与那 Elf 与那 Exec 与那 Shebang 与那 Unknown 那四个逐字**不互相顶替**。
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
        is Shape.Exec -> listOf(entry.absolutePath) + args.drop(1)
        // 缺陷（C-102）**该套解释器的那一层套了两层**。
        // 真机读数（07，logcat）：Cmdline = /system/bin/linker64 /system/bin/linker64 …/opt/bash/…/bash
        // —— linker64 出现两次。
        //
        // **而那一层只能有一层，而那一层必须由这一侧显式给**（07 设备侧 2026-10-24 的读数）：
        //  · X1（直接起入口）=> argv 里 linker64 出现 **0 次**、exit=0
        //    => 而那个入口带 PT_INTERP = /system/bin/linker64，
        //       **而内核 exec 它时并没有把那条解释器加进 argv**
        //       => 而所以「入口带 PT_INTERP => 内核会替我们套」这个前提**不成立**
        //  · X2（argv[0]=linker64, argv[1]=入口）=> exit=0
        //    => 而那正是**显式**套那一层的形态 => 而而它是跑得通的那一条
        //  · X3（两层都套）=> `error: linker cannot load itself`、exit=1
        //
        // **=> 而所以判据是「有没有已经被替过」**，而**不是**「入口带不带 PT_INTERP」：
        //   替过了（argv[0] 已是 linker64）=> 不再替
        //     （=> 而否则两层 => linker64 把自己当程序加载 => call_constructors => libc 初始化链崩；
        //      AOSP linker_main.cpp:358 那一处注释写的正是「the linker attempts to execute itself」）；
        //   没替过 => **显式套** SYSTEM_LINKER。
        //
        // **而为什么必须显式套**（07 设备侧 2026-10-24 的读数，0e447ef 那一版的回归）：
        //   那四份入口在设备上是 u:object_r:app_data_file:s0，
        //   而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
        //   （=> 而直接 exec 它在加载解释器之前就 EACCES => 而那一格是 error=13）；
        //   **而 /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可**
        //   => 而所以**经由 linker64 去 exec 那个入口**这条路是通的，
        //      **而「直接 exec 那个入口」那条路在 app 自己那个域里不通**
        //   （=> 而同一份 bash 字节在 runas_app 那个域里直接 exec 是成的
        //     => 而而那说明是域的策略，不是文件权限位）。
        //
        // 而这一支**不能删**（ET_DYN 要经 linker64 驱动 —— 唯一真相源是本函数，native 那份已删，C-87）。
        // 而原生那一支（container/native/d1/exec-path.c:429）问的是 etype==ET_DYN ——
        //   而它那一侧 execve 的是 **linker64**（:438）=> 而而那一支与这一支是同一条路。
        is Shape.Elf ->
            if (entry.absolutePath == SYSTEM_LINKER) listOf(entry.absolutePath) + args.drop(1)
            else listOf(SYSTEM_LINKER, entry.absolutePath) + args.drop(1)
        is Shape.Shebang ->
            if (s.flags == null) listOf(s.interp, entry.absolutePath) + args
            else listOf(s.interp, s.flags, entry.absolutePath) + args
        //  => 而那 **PE** 那一族逐字**没有**那**内核**能**直接**跑**的**入口**（=> 而那 **PE** 逐字**要**那 **Windows** **那一族**的**加载器**）。
        //    => 而那**它**逐字**不**是**那 **ELF** 那一族、=> 而那**它**逐字**不**是**那**脚本**那一族、=> 而那**它**逐字**跑不起来**。
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

package lobos.kernel.elf

import java.io.File

object ExecBits {

    /** 给它执行位 —— 判据只问文件头（那样一个 600 的文件也能被加上）。
     *  「能不能跑」的判据是 isRunnable，它还要问 owner 的执行位。 */
    fun apply(file: File) {
        if (hasExecutableHeader(file)) file.setExecutable(true, false)
    }

    fun hasExecutableHeader(file: File): Boolean =
        file.isFile &&
            lobos.kernel.proc.ExecEntry.detect(file) != lobos.kernel.proc.ExecEntry.Shape.Unknown

    /** 文件头看起来可执行**且** owner 有执行位 —— 两者缺一不可。
     *  真机读数：bash/rg 的 entry 是 bin/ 下 -rwx--x--x（过），其余六件是 lib/ 下 -rw------
     *  （文件头是 ELF，但 owner 没有执行位 ⇒ spawn 得 error=13）。只看文件头会把后者判成可执行。 */
    fun isRunnable(file: File): Boolean {
        if (!file.isFile) return false
        if (lobos.kernel.proc.ExecEntry.detect(file) == lobos.kernel.proc.ExecEntry.Shape.Unknown) return false
        return file.canExecute()
    }

    /** 这份字节的**形态**（01 今天的三态措辞：只看 ELF，不看文件名/目录名/form）。
     *  - [Exec]      ET_EXEC —— 直接能 exec
     *  - [DynInterp] ET_DYN **且** 有 PT_INTERP —— 位置无关可执行，需经 linker64 驱动
     *  - [DynPlain]  ET_DYN **且** 没有 PT_INTERP —— 共享库，不是程序
     *  - [Unknown]  不是 ELF 或是 #!（走 [hasExecutableHeader] 那条，不归这里）
     *
     *  **缺陷名（C-102）形态被合并判据抹平**：原来只判 `detect() != Unknown`
     *  ⇒ 一个 ET_DYN 共享库与一个 ET_DYN 的 PIE 程序在这把尺上完全一样，
     *  而 01 今天要的正���这两者是不同的答案。 */
    enum class Form { Exec, DynInterp, DynPlain, Unknown }

    fun formOf(file: File): Form {
        if (!file.isFile) return Form.Unknown
        return when (lobos.kernel.proc.ExecEntry.detect(file)) {
            is lobos.kernel.proc.ExecEntry.Shape.Exec -> Form.Exec
            is lobos.kernel.proc.ExecEntry.Shape.Elf -> {
                // PT_INTERP 在 ELF 程序头里，ElfFacts.read 已经读它并放进 Dynamic.interp
                val interp = runCatching { ElfFacts.read(file)?.interp }.getOrNull()
                if (!interp.isNullOrBlank()) Form.DynInterp else Form.DynPlain
            }
            else -> Form.Unknown
        }
    }

    /** 按 01 今天的判别式：**这一份字节能被当程序调起吗？**
     *  形态（ET_EXEC 或 ET_DYN+PT_INTERP）**且** owner 有执行位。
     *  ★ 而 `formOf` 单独回答形态那一问，`canExecute` 单独回答权限那一问 ——
     *  两把尺分开，谁也不替谁答（§10.1:136 错的是「一个字段回答两个问题」）。 */
    fun isRunnableByForm(file: File): Boolean = when (formOf(file)) {
        Form.Exec, Form.DynInterp -> file.canExecute()
        else -> false
    }
}

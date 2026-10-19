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
}

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
     *  （文件头是 ELF，但 owner 没有执行位 => spawn 得 error=13）。只看文件头会把后者判成可执行。 */
    fun isRunnable(file: File): Boolean {
        if (!file.isFile) return false
        if (lobos.kernel.proc.ExecEntry.detect(file) == lobos.kernel.proc.ExecEntry.Shape.Unknown) return false
        return file.canExecute()
    }

    /** 这一族（运行期读 ELF 头判断形态）已于 2026-10-20 按 04 的九节清单整族删除。
     *
     *  **缺陷名（C-102）运行期探测形态**：原来这里有 [Form] 枚举、[formOf]、[isRunnableByForm] 三个
     *  入口，分别读 `e_type`（ET_EXEC / ET_DYN）与 `PT_INTERP`，由**运行期自己算**「这是库还是程序」。
     *  这与用户 2026-10-20 定的第三行规则（运行期只读，不判断、不探测）冲突：
     *  形态是**构建期就知道**的事（一），落位目录是构建期定的（二），注册表运行期只该读那两份写下的东西。
     *
     *  **留着的两把尺不是这一族**（判据见 runtime-deletion-list.md 第 1 节）：
     *  · [hasExecutableHeader] / [apply]：判据是「能不能加执行位」，读的是文件头存在与否，主语是权限位；
     *    读者是落位侧（SupplyProvisioner / ProgramInstallPipeline / PieceUpdater）。
     *  · [isRunnable]：读文件头 + owner 执行位，读者是 LdConfig（bin/ 扫描建链），属位置/链接那一路（§33 / §38），
     *    不属「运行时判断形态」那一族，故未删。
     *
     *  ★ 而形态那一问的答案现在从哪来：**清单里那份构建期写下的字段**，字段缺失时报缺（不猜、不探测）。
     *    该字段由构建脚本产出，不在本文件的写范围内 —— 见 runtime-deletion-list.md 第 2 节「需要别人跟着改的清单」。
     */
}

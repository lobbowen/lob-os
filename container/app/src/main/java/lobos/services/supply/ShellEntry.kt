package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitEntry

object ShellEntry {

    private fun landOf(e: UnitEntry): File? =
        if (e.stateDir.isBlank() || e.assetEntry.isBlank()) null
        else File(File(e.stateDir), e.assetEntry).takeIf { it.isFile }

    private fun linkOf(e: UnitEntry, ctx: Context): File? {
        val name = e.assetEntry.substringAfterLast('/')
        if (name.isBlank()) return null
        val f = File(PrefixProvisioner.binDir(ctx), name)
        return f.takeIf { it.isFile }
    }

    /** 判据是什么 —— 供报缺时逐条列出，不让「没有」与「哪条没命中」混成一句。 */
    fun criteria(): List<String> = listOf(
        "role=shell（落位 bin/ 下有可执行入口的件，被登记为 shell）",
        "role=multi-command（落位 bin/ 下有可执行入口的件，被登记为 multi-command）",
    )

    fun resolve(ctx: Context): File? {
        //  缺陷名（C-102）**那一格逐字按那 `isPiece` 筛、=> 而而那它逐字把那 shell 那一档逐字筛掉**
        //   => 而那旧版逐字是 `ProgramIndex.all(ctx).filter { ProgramIndex.isPiece(ctx, it) }`
        //     => 而那 `isPiece` 逐字是 `e.levelOf(ctx) == Level.PIECE`
        //       （=> 而那 `ProgramIndex.kt:70` 那一格逐字
        //         `return if (assetEntry.isBlank()) Level.PIECE else Level.PROGRAM`）
        //       => 而那**判据**逐字是「那**入口**那一格有没有值」
        //     => 而那**修好了写入那一侧**之后（=> 而那 shell 那一档逐字**有**入口了）
        //       => 而那它逐字变成 `Level.PROGRAM`、=> 而那**这一格**逐字就把它逐字**筛掉**
        //       => 而那 `resolve` 逐字还是返回 null、=> 而那**同一个**抛错逐字还在
        //   => 而那**正解**：=> 而那**这一格**逐字找的是「那 role 逐字是 shell 那一档的那一件」、
        //     => 而那它逐字**不**该由「那一件在登记里是程序还是件」决定
        //     （=> 而那是**两把尺**、=> 而依 C-105 逐字**不**互相顶替）
        //   => 而那它逐字**仍然**只在那**落位目录**底下找（=> 而那它逐字**不**认用户程序那一族）
        val pieces = ProgramIndex.all(ctx).filter { it.role == PieceScan.SHELL || it.role == PieceScan.MULTI_COMMAND }

        val shell = pieces.firstOrNull { it.role == PieceScan.SHELL }
        if (shell != null) {
            landOf(shell)?.let { return it }
            linkOf(shell, ctx)?.let { return it }
        }

        val multi = pieces.firstOrNull { it.role == PieceScan.MULTI_COMMAND }
        if (multi != null) {
            landOf(multi)?.let { return it }
            linkOf(multi, ctx)?.let { return it }
        }
        return null
    }

    fun require(ctx: Context): File =
        resolve(ctx) ?: throw IllegalStateException(
            "底座没有命令解释器（/usr/bin 下无可用入口）—— 不能静默落到 /system/bin/sh" +
                "（那会让命令在另一套语义下跑）。底座不完整，先铺件（dpkg -V 那条路查登记与磁盘）。" +
                "\n判据逐条：\n" + criteria().mapIndexed { i, s -> "  ${i + 1}) $s" }.joinToString("\n") +
                "\n在册的件：" + ProgramIndex.all(ctx)
                    .filter { it.role == PieceScan.SHELL || it.role == PieceScan.MULTI_COMMAND }
                    .joinToString("\n") { "  " + it.id + " role=" + (if (it.role.isBlank()) "(空)" else it.role) }
        )
}
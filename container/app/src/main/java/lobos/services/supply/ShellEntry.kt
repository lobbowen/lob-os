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
        val pieces = ProgramIndex.all(ctx).filter { ProgramIndex.isPiece(ctx, it) }

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
                "\n在册的件：" + ProgramIndex.all(ctx).filter { ProgramIndex.isPiece(ctx, it) }
                    .joinToString("\n") { "  " + it.id + " role=" + (if (it.role.isBlank()) "(空)" else it.role) }
        )
}
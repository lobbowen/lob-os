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

    fun resolve(ctx: Context): File? {
        val pieces = ProgramIndex.all(ctx).filter { ProgramIndex.isPiece(it) }

        val shell = pieces.firstOrNull { it.role == PieceScan.SHELL || it.id == "bash" }
        if (shell != null) {
            landOf(shell)?.let { return it }
            linkOf(shell, ctx)?.let { return it }
        }

        val multi = pieces.firstOrNull { it.role == PieceScan.MULTI_COMMAND || it.id == "busybox" }
        if (multi != null) {
            landOf(multi)?.let { return it }
            linkOf(multi, ctx)?.let { return it }
        }
        return null
    }

    fun require(ctx: Context): File =
        resolve(ctx) ?: throw IllegalStateException(
            "底座没有命令解释器（/usr/bin 下无可用入口）—— 不能静默落到 /system/bin/sh" +
                "（那会让命令在另一套语义下跑）。底座不完整，先铺件（dpkg -V 那条路查登记与磁盘）。"
        )
}
package lobos.services.host

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.services.reg.Desired
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramManager
import lobos.services.app.CompatSemantics
import lobos.kernel.fs.StateFiles
import lobos.services.supervise.TaskRegistry
import lobos.services.reg.ProgramRegistry
import lobos.services.supply.ProgramDir

object BootReconciler {

    private const val PENDING_MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val PART_MAX_AGE_MS = 6L * 60 * 60 * 1000
    private const val TMP_MIN_AGE_MS = 60_000L

    data class Report(
        val temps: List<String>,
        val parts: List<String>,
        val abandonedTasks: Int,
        val notRunning: List<String>,
        val stagingGone: List<String>,
        val stagingStuck: List<String>,
        val repairedProgram: String?,
        val clearedPendings: List<String>,
    ) {
        val changed: Boolean
            get() = temps.isNotEmpty() || parts.isNotEmpty() || abandonedTasks > 0 ||
                notRunning.isNotEmpty() || stagingGone.isNotEmpty() ||
                repairedProgram != null || clearedPendings.isNotEmpty()

        fun summary(): String = listOf(
            "临时残留=" + temps.size,
            "半包=" + parts.size,
            "放弃任务=" + abandonedTasks,
            "撤销陈旧 desired=" + notRunning.size,
            "暂存清理=" + stagingGone.size + (if (stagingStuck.isNotEmpty()) "(卡住 " + stagingStuck.size + ")" else ""),
            "入口缺失修复=" + (repairedProgram ?: "无"),
            "陈旧 PENDING=" + clearedPendings.size,
        ).joinToString("；")
    }

    fun run(ctx: Context): Report {
        val temps = StateFiles.cleanTemps(ctx, TMP_MIN_AGE_MS)
        val parts = StateFiles.cleanParts(ctx, PART_MAX_AGE_MS)
        val abandoned = TaskRegistry.abandonRunning(ctx, "boot-reconcile")
        val notRunning = reconcileNotRunning(ctx)
        val cleared = mutableListOf<String>()
        val gone = mutableListOf<String>()
        val stuck = mutableListOf<String>()

        for (id in ProgramRegistry.listIds(ctx)) {
            val pm = ProgramDir(ctx, id)
            val sweep = runCatching { pm.sweepStaleStaging() }.getOrNull()
            if (sweep != null) {
                gone.addAll(sweep.first)
                stuck.addAll(sweep.second)
            }
            val pruned = runCatching { pm.pruneOldVersions() }.getOrNull()
            if (pruned != null && pruned.first.isNotEmpty()) {
                RuntimeDiagnostics.append(
                    ctx, "program-gc", true,
                    "清掉回滚用不到的旧版本（保留 CURRENT/FLOOR/PENDING 目标 + 1 个）",
                    "删=" + pruned.first.joinToString(",") + " 释放=" + (pruned.third / 1024) + "KB" +
                        (if (pruned.second.isEmpty()) "" else " 删不掉=" + pruned.second.joinToString(",")),
                )
            }
            if (expireStalePending(ctx, pm, id)) cleared.add(id)
        }

        val reconciledPending = reconcilePending(ctx)
        cleared.addAll(reconciledPending)
        ProgramManager.reconcile(ctx)
        ProgramManager.assemble(ctx)
        lobos.services.app.CompatSemantics.write(ctx)
        val repaired = repairMissingEntry(ctx)

        return Report(
            temps = temps,
            parts = parts,
            abandonedTasks = abandoned,
            notRunning = notRunning,
            stagingGone = gone,
            stagingStuck = stuck,
            repairedProgram = repaired,
            clearedPendings = cleared,
        )
    }

    private fun reconcileNotRunning(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        for (e in ProgramIndex.all(ctx)) {
            if (e.desired != Desired.RUNNING) continue
            if (ProgramIndex.isPiece(e)) continue
            val cur = runCatching { ProgramManager.stateDirOf(ctx, e.id).let { d ->
                ProgramDir(ctx, e.id, d).currentVersion()
            } }.getOrNull().orEmpty()
            if (e.version.isNotBlank() && cur.isNotBlank()) continue
            ProgramManager.setDesired(ctx, e.id, Desired.STOPPED)
            out.add(e.id)
        }
        if (out.isNotEmpty()) {
            Journal.note(ctx, "boot", null, "声明要跑但磁盘无版本，已置为停止", "id=" + out.joinToString(","))
        }
        return out
    }

    private fun expireStalePending(ctx: Context, pm: ProgramDir, id: String): Boolean {
        val p = pm.pending() ?: return false
        val dir = pm.programDir(p.version)
        val age = System.currentTimeMillis() - File(pm.programRootDir(), "PENDING").lastModified()
        val stale = !dir.isDirectory || age > PENDING_MAX_AGE_MS
        if (!stale) return false
        pm.clearPending()
        Journal.note(
            ctx, "boot", null, "清理陈旧 PENDING",
            "id=" + id + " version=" + p.version + " 目录在=" + dir.isDirectory,
        )
        return true
    }

    private fun reconcilePending(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        for (id in ProgramRegistry.listIds(ctx)) {
            val pm = ProgramDir(ctx, id)
            val p = pm.pending() ?: continue
            val cur = pm.currentVersion()
            if (p.version == cur) continue
            if (!pm.programDir(p.version).isDirectory) continue
            pm.clearPending()
            out.add(id)
            Journal.note(
                ctx, "boot", null, "撤销未完成的安装（PENDING 未激活）",
                "id=" + id + " pending=" + p.version + " current=" + (cur ?: "无") + "（目录在但指针未切，按未安装处理）",
            )
        }
        return out
    }

    private fun repairMissingEntry(ctx: Context): String? {
        for (id in ProgramRegistry.listIds(ctx)) {
            val km = ProgramDir(ctx, id)
            val cur = km.currentVersion() ?: continue
            if (cur.isBlank()) continue
            if (km.entryPath(cur).exists()) continue
            val candidates = km.installedVersions().filter { it != cur }.sortedDescending()
            for (v in candidates) {
                if (!km.entryPath(v).exists()) continue
                km.setCurrentVersion(v)
                km.clearPending()
                Journal.note(
                    ctx, "boot", false, "入口缺失：回退到可用版本",
                    "id=" + id + " 损坏=" + cur + " 回退=" + v + "（损坏版本的清单入口不存在）",
                )
                return v
            }
            Journal.note(ctx, "boot", false, "入口缺失且无可用版本", "id=" + id + " current=" + cur)
        }
        return null
    }
}

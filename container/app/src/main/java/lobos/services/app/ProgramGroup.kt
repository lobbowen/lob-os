package lobos.services.app

import android.content.Context
import java.io.File
import lobos.services.log.Journal
import lobos.kernel.proc.ProcessLedger

object ProgramGroup {

    fun ensureBackendRunning(ctx: Context, unit: String): String? {
        val e = lobos.services.reg.ProgramIndex.get(ctx, unit)
            ?: return "注册表里没有 " + unit
        if (e.pid > 0 && lobos.kernel.proc.ProcessLedger.isOwnedAlive(e.pid, e.starttime)) return null
        if (e.desired == lobos.services.reg.Desired.STOPPED) return null
        return lobos.services.reg.ProgramManager.requestDesired(
            ctx, unit, lobos.services.reg.Desired.RUNNING, "前端打开，需要后端",
        )
    }

    fun hasUi(ctx: Context, unit: String): Boolean =
        lobos.services.reg.ProgramIndex.get(ctx, unit)?.uiPackage?.isNotBlank() == true

    fun shouldStopBackendOnUiClose(ctx: Context, unit: String): Boolean {
        val mode = lobos.services.reg.ProgramIndex.get(ctx, unit)?.onUiClosed.orEmpty()
        return mode != lobos.services.reg.ManifestSchema.CLOSED_KEEP_ALIVE
    }

    fun onUiClosed(ctx: Context, unit: String): Boolean {
        if (!shouldStopBackendOnUiClose(ctx, unit)) {
            lobos.services.log.Journal.note(
                ctx, "group", null,
                "前端关闭但后端保留（ui.onUiClosed=keep-alive）",
                "unit=" + unit,
            )
            return false
        }
        val stopped = runCatching {
            val e = lobos.services.reg.ProgramIndex.get(ctx, unit)
            if (e == null) return false
            lobos.services.reg.ProgramManager.requestDesired(
                ctx, unit, lobos.services.reg.Desired.STOPPED, "前端关闭（ui.onUiClosed）",
            ) == null
        }.getOrDefault(false)
        lobos.services.log.Journal.note(
            ctx, "group", stopped,
            if (stopped) "前端关闭 → 后端已排入停止队列" else "前端关闭 → 后端未在跑或排队失败",
            "unit=" + unit,
        )
        return stopped
    }

    fun onBackendExit(ctx: Context, unit: String, exitCode: Int) {
        runCatching {
            QuickAppHost.close(unit)
        }
        lobos.services.log.Journal.note(
            ctx, "group", null,
            "后端退出 → 前端已收",
            "unit=" + unit + " exitCode=" + exitCode,
        )
    }

    fun reconcile(ctx: Context) {
        runCatching {
            for (e in lobos.services.reg.ProgramIndex.all(ctx)) {
                val pkg = e.uiPackage
                if (pkg.isBlank()) continue
                val installed = QuickAppHost.installed(e.id)
                if (!installed) {
                    lobos.services.log.Journal.note(
                        ctx, "group", null,
                        "登记了前端但dimina 里没有 —— 留待下次装包时补",
                        "unit=" + e.id + " uiPackage=" + pkg,
                    )
                }
            }
        }
    }
}
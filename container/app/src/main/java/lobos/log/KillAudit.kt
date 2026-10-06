package lobos.log
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KillAudit {

    private const val CURSOR_FILE = "kill-audit-cursor.txt"

    // Journal 的 category。归因时按它筛。
    private const val CATEGORY = "kill-audit"

    private const val MAX_RECORDS = 32

    data class ExitRecord(
        val atMs: Long,
        val pid: Int,
        val process: String,
        val reason: Int,
        val importance: Int,
        val description: String?,
    ) {
        val verdict: Journal.Reason get() = Journal.Reason.fromExitInfo(reason, description)

        fun detail(): String = "reason=" + reason + " importance=" + importance +
            " process=" + process + " desc=" + (description?.take(160) ?: "null")
    }


    data class ExitRecord(
        val atMs: Long,
        val pid: Int,
        val process: String,
        val reason: Int,
        val importance: Int,
        val description: String?,
    ) {
        val verdict: Journal.Reason get() = Journal.Reason.fromExitInfo(reason, description)

        fun detail(): String = "reason=" + reason + " importance=" + importance +
            " process=" + process + " desc=" + (description?.take(160) ?: "null")
    }


    // 采集后逐条写 Journal（category=kill-audit），归因由 attribution() 查 Journal。
    // 不留内存副本：那份只活到进程结束，而退出史要跨进程留存。
    fun auditOnce(ctx: Context) {
        val pkg = ctx.packageName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportUnreadable(ctx, pkg, "本机系统（API ${Build.VERSION.SDK_INT}）不提供退出史")
            return
        }
        val raw = runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(pkg, 0, MAX_RECORDS)
        }.getOrNull()
        if (raw == null) {
            reportUnreadable(ctx, pkg, "读系统退出史失败（binder 调用没答上来）")
            return
        }
        val records = raw.map {
            ExitRecord(
                atMs = it.timestamp,
                pid = it.pid,
                process = it.processName,
                reason = it.reason,
                importance = it.importance,
                description = it.description,
            )
        }
        val cursor = readCursor(ctx)
        records.asReversed().filter { isNewerThanCursor(it, cursor) }.forEach {
            Journal.append(ctx, CATEGORY, it.verdict, it.detail())
        }
        records.filter { isNewerThanCursor(it, cursor) }.maxByOrNull { it.atMs }
            ?.let { writeCursor(ctx, it) }
    }

    // 归因从 Journal 查，不再读内存 —— 内存副本只活到进程结束，
    // 而退出史已经由 auditOnce 经 Journal.append 落盘（category=kill-audit）。
    fun attribution(ctx: Context, sinceMs: Long): String {
        val own = ctx.packageName
        val hits = lobos.Journal.events(ctx, limit = 400)
            .filter { it.category == CATEGORY && it.atMs >= sinceMs }
        val ev = hits.firstOrNull { it.detail.contains("process=$own") }
        if (ev == null) {
            val unreadable = hits.firstOrNull()?.detail
            return if (unreadable == null) {
                "死因未取证（还没读系统退出史）"
            } else {
                "死因未取证（$unreadable）"
            }
        }
        return "死因=" + ev.detail
    }

    private fun reportUnreadable(ctx: Context, pkg: String, why: String) {
        Journal.append(ctx, CATEGORY, Journal.Reason.UNREADABLE, why)
    }

    private fun isNewerThanCursor(r: ExitRecord, cursor: Pair<Long, Int>): Boolean =
        r.atMs > cursor.first || (r.atMs == cursor.first && r.pid > cursor.second)

    private fun readCursor(ctx: Context): Pair<Long, Int> = runCatching {
        val parts = File(ctx.filesDir, CURSOR_FILE).readText().trim().split(" ")
        (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }.getOrDefault(0L to 0)

    private fun writeCursor(ctx: Context, newest: ExitRecord) {
        runCatching { StateFiles.writeAtomic(File(ctx.filesDir, CURSOR_FILE), "${newest.atMs} ${newest.pid}") }
    }
}

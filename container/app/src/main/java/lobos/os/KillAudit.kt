package lobos.os

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KillAudit {

    private const val CURSOR_FILE = "kill-audit-cursor.txt"

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

    private data class Reading(
        val exits: List<ExitRecord>,
        val ownProcess: String,
        val unreadable: String?,
    )

    @Volatile
    private var reading: Reading? = null

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
        reading = Reading(records, pkg, null)
        val cursor = readCursor(ctx)
        records.asReversed().filter { isNewerThanCursor(it, cursor) }.forEach {
            Journal.append(ctx, "kill-audit", it.verdict, it.detail())
        }
        records.filter { isNewerThanCursor(it, cursor) }.maxByOrNull { it.atMs }
            ?.let { writeCursor(ctx, it) }
    }

    fun attribution(sinceMs: Long): String {
        val r = reading ?: return "死因未取证（还没读系统退出史）"
        return attribute(r.exits, r.unreadable, sinceMs, r.ownProcess)
    }

    fun attribute(
        exits: List<ExitRecord>,
        unreadable: String?,
        sinceMs: Long,
        mainProcess: String,
    ): String {
        val r = exits.firstOrNull { it.process == mainProcess && it.atMs >= sinceMs }
        if (r == null) {
            return if (unreadable == null) {
                "死因未取证（退出史里没有这次中断对应的记录）"
            } else {
                "死因未取证（$unreadable）"
            }
        }
        val at = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(r.atMs))
        return "死因=" + r.verdict.code + "（系统退出记录 " + at + " " + r.detail() + "）"
    }

    private fun reportUnreadable(ctx: Context, pkg: String, why: String) {
        reading = Reading(emptyList(), pkg, why)
        Journal.append(ctx, "kill-audit", Journal.Reason.UNREADABLE, why)
    }

    private fun isNewerThanCursor(r: ExitRecord, cursor: Pair<Long, Int>): Boolean =
        r.atMs > cursor.first || (r.atMs == cursor.first && r.pid > cursor.second)

    private fun readCursor(ctx: Context): Pair<Long, Int> = runCatching {
        val parts = File(ctx.filesDir, CURSOR_FILE).readText().trim().split(" ")
        (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }.getOrDefault(0L to 0)

    private fun writeCursor(ctx: Context, newest: ExitRecord) {
        runCatching { lobos.os.StateFiles.writeAtomic(File(ctx.filesDir, CURSOR_FILE), "${newest.atMs} ${newest.pid}") }
    }
}

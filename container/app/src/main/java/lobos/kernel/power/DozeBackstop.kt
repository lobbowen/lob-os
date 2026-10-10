package lobos.kernel.power

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * 内核机制：兜底自唤醒定时器。
 * 不认识任何业务词汇，只负责按时把广播投递出去（见 DozeBackstopReceiver）。
 */
object DozeBackstop {

    const val ACTION = "lobos.action.DOZE_BACKSTOP"

    /** 兜底自唤醒间隔（ms）。 */
    const val WAKE_BACKSTOP_MS = 15 * 60_000L

    private const val INTERVAL_MS = WAKE_BACKSTOP_MS

    @Volatile private var lastArmedAt = 0L

    fun armedRecently(nowMs: Long): Boolean = lastArmedAt > 0L && nowMs - lastArmedAt < INTERVAL_MS * 2

    fun schedule(ctx: Context): Boolean {
        return runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx,
                0,
                Intent(ctx, DozeBackstopReceiver::class.java).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pi,
            )
            lastArmedAt = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }
}
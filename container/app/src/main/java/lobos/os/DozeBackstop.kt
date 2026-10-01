package lobos.os

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import lobos.lifecycle.DozeBackstopReceiver

object DozeBackstop {

    const val ACTION = "lobos.action.DOZE_BACKSTOP"

    private const val INTERVAL_MS = lobos.lifecycle.ResidencyPolicy.WAKE_BACKSTOP_MS

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

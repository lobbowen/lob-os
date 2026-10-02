package lobos.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

data class ProgramNotice(
    val programId: String,
    val title: String,
    val text: String,
    val atMs: Long,
    val ongoing: Boolean,
    val silent: Boolean,
)

object ProgramNotificationHub {

    const val CHANNEL_PROGRAM = "lobos_program"
    private const val NOTIF_BASE = 2000
    private const val MAX_TEXT = 400

    private val notices = ConcurrentHashMap<String, ProgramNotice>()

    private fun channel(ctx: Context): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_PROGRAM) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_PROGRAM,
                        "程序状态",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "由虚拟系统内的程序自行投递（插件接口）" },
                )
            }
        }
        return CHANNEL_PROGRAM
    }

    fun publish(
        ctx: Context,
        programId: String,
        title: String,
        text: String,
        ongoing: Boolean,
        silent: Boolean,
    ): ProgramNotice {
        val safeId = FacilityRegistry.safeSegment(programId)
            ?: throw IllegalArgumentException("programId 非法: " + programId.take(40))
        val t = title.trim().take(80).ifBlank { safeId }
        val b = text.trim().take(MAX_TEXT)
        val n = ProgramNotice(safeId, t, b, System.currentTimeMillis(), ongoing, silent)
        notices[safeId] = n
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val builder = android.app.Notification.Builder(ctx, channel(ctx))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(t)
                .setContentText(b)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setWhen(n.atMs)
            nm.notify(NOTIF_BASE + (safeId.hashCode() and 0x7fff), builder.build())
        }
        return n
    }

    fun clear(ctx: Context, programId: String): Boolean {
        val safeId = FacilityRegistry.safeSegment(programId) ?: return false
        val had = notices.remove(safeId) != null
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_BASE + (safeId.hashCode() and 0x7fff))
        }
        return had
    }

    fun clearAll(ctx: Context) {
        for (id in notices.keys.toList()) clear(ctx, id)
    }

    fun list(): List<ProgramNotice> = notices.values.sortedBy { it.atMs }

    fun listJson(): org.json.JSONArray = org.json.JSONArray().apply {
        for (n in list()) {
            put(org.json.JSONObject().apply {
                put("programId", n.programId)
                put("title", n.title)
                put("text", n.text)
                put("atMs", n.atMs)
                put("ongoing", n.ongoing)
            })
        }
    }

    fun summaryLine(): String? {
        if (notices.isEmpty()) return null
        val head = notices.values.maxByOrNull { it.atMs } ?: return null
        val more = notices.size - 1
        return head.text.ifBlank { head.title } + if (more > 0) "（另 $more 项）" else ""
    }
}
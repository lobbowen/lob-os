package lobos.os

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

data class ProgramNotice(
    val programId: String,
    val groupKey: String,
    val groupLabel: String,
    val title: String,
    val text: String,
    val atMs: Long,
    val ongoing: Boolean,
    val silent: Boolean,
    val subText: String?,
    val progress: Int?,
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("programId", programId)
        put("group", groupKey)
        put("groupLabel", groupLabel)
        put("title", title)
        put("text", text)
        put("atMs", atMs)
        put("ongoing", ongoing)
        put("silent", silent)
        put("subText", subText ?: org.json.JSONObject.NULL)
        put("progress", if (progress == null) org.json.JSONObject.NULL else progress)
    }
}

object ProgramNotificationHub {

    const val GROUP_PARENT = "lobos_program_group"
    private const val CHANNEL_PREFIX = "lobos_prog_"
    private const val CHANNEL_DEFAULT = "lobos_prog_default"
    private const val NOTIF_BASE = 2000
    private const val MAX_TEXT = 400
    private const val MAX_TITLE = 80
    private const val MAX_GROUPS = 32

    private val notices = ConcurrentHashMap<String, ProgramNotice>()

    private fun channelKeyOf(raw: String): String {
        val g = FacilityRegistry.safeSegment(raw) ?: "default"
        return if (g == "default") CHANNEL_DEFAULT else (CHANNEL_PREFIX + g)
    }

    private fun ensureChannel(ctx: Context, key: String, label: String) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(key) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                key,
                label.ifBlank { "程序状态" },
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "由虚拟系统内的程序自行投递（组件自带标题与分组）"
                setShowBadge(false)
            },
        )
    }

    fun publish(
        ctx: Context,
        programId: String,
        title: String,
        text: String,
        ongoing: Boolean,
        silent: Boolean,
        groupKey: String?,
        groupLabel: String?,
        subText: String?,
        progress: Int?,
    ): ProgramNotice {
        val safeId = FacilityRegistry.safeSegment(programId)
            ?: throw IllegalArgumentException("programId 非法: " + programId.take(40))
        val gRaw = groupKey?.trim().orEmpty().ifBlank { "default" }
        val gLabel = groupLabel?.trim()?.take(60).orEmpty()
        val chKey = channelKeyOf(gRaw)
        ensureChannel(ctx, chKey, gLabel)

        val n = ProgramNotice(
            programId = safeId,
            groupKey = gRaw,
            groupLabel = gLabel,
            title = title.trim().take(MAX_TITLE).ifBlank { safeId },
            text = text.trim().take(MAX_TEXT),
            atMs = System.currentTimeMillis(),
            ongoing = ongoing,
            silent = silent,
            subText = subText?.trim()?.take(120)?.ifBlank { null },
            progress = progress?.coerceIn(-1, 100),
        )
        notices[safeId] = n
        prune(ctx)

        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = android.app.Notification.Builder(ctx, chKey)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setGroup(GROUP_PARENT)
                .setSubText(n.subText)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setWhen(n.atMs)
                .setShowWhen(true)
            if (n.progress != null && n.progress >= 0) {
                b.setProgress(100, n.progress, false)
            }
            nm.notify(notifId(safeId), b.build())
        }
        return n
    }

    private fun notifId(programId: String): Int = NOTIF_BASE + (programId.hashCode() and 0x7fff)

    private fun prune(ctx: Context) {
        if (notices.size <= MAX_GROUPS) return
        val keep = notices.values.sortedByDescending { it.atMs }.take(MAX_GROUPS)
        for (id in notices.keys - keep.map { it.programId }.toSet()) clear(ctx, id)
    }

    fun clear(ctx: Context, programId: String): Boolean {
        val safeId = FacilityRegistry.safeSegment(programId) ?: return false
        val had = notices.remove(safeId) != null
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId(safeId))
        }
        return had
    }

    fun clearAll(ctx: Context) {
        for (id in notices.keys.toList()) clear(ctx, id)
    }

    fun list(): List<ProgramNotice> = notices.values.sortedBy { it.atMs }

    fun groups(): Map<String, List<ProgramNotice>> = list().groupBy { it.groupKey }

    fun listJson(): org.json.JSONArray = org.json.JSONArray().apply {
        for (n in list()) put(n.toJson())
    }

    fun groupsJson(): org.json.JSONArray = org.json.JSONArray().apply {
        for ((key, items) in groups().toSortedMap()) {
            put(org.json.JSONObject().apply {
                put("group", key)
                put("label", items.firstOrNull()?.groupLabel ?: "")
                put("count", items.size)
                put("programs", org.json.JSONArray().apply { for (i in items) put(i.programId) })
            })
        }
    }

    fun summaryLine(): String? {
        if (notices.isEmpty()) return null
        val byGroup = groups()
        val head = byGroup.entries.maxByOrNull { it.value.maxOf { n -> n.atMs } } ?: return null
        val top = head.value.maxByOrNull { it.atMs } ?: return null
        val otherGroups = byGroup.size - 1
        val more = head.value.size - 1
        return buildString {
            append(top.text.ifBlank { top.title })
            if (more > 0) append("（本组另 ").append(more).append(" 项）")
            if (otherGroups > 0) append("（另 ").append(otherGroups).append(" 组）")
        }
    }
}
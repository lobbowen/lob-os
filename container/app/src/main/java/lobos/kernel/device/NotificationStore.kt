package lobos.kernel.device
import android.app.Notification
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque
import org.json.JSONArray
import org.json.JSONObject

object NotificationStore {

    private const val MAX = 200

    private val items = ConcurrentLinkedDeque<JSONObject>()

    @Volatile
    var connected: Boolean = false

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val obj = JSONObject().apply {
            put("pkg", sbn.packageName)
            put("key", sbn.key)
            put("postTime", sbn.postTime)
            put("title", ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            put("text", ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            put("ongoing", sbn.isOngoing)
            put("clearable", sbn.isClearable)
        }
        items.addFirst(obj)
        while (items.size > MAX) items.pollLast()
    }

    fun onRemoved(key: String?) {
        if (key == null) return
        items.removeIf { it.optString("key") == key }
    }

    fun snapshot(limit: Int): JSONArray {
        val arr = JSONArray()
        var i = 0
        for (o in items) {
            if (i++ >= limit) break
            arr.put(o)
        }
        return arr
    }
}

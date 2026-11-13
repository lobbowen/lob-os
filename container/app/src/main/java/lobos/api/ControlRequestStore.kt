package lobos.api

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import lobos.kernel.fs.StateFiles
import org.json.JSONObject

class ControlRequestStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "control-requests.json")

    companion object {
        private val globalLock = Any()
        private val pieceLocks = ConcurrentHashMap<String, Any>()

        private const val KEY_SEP = "::"

        private const val REJECT_PREFIX = "rejected"

        fun keyOf(method: String, requestId: String): String = method + KEY_SEP + requestId

        fun rejectKeyOf(method: String, requestId: String, pieceId: String, base: String): String =
            REJECT_PREFIX + KEY_SEP + method + KEY_SEP + requestId + KEY_SEP + pieceId + KEY_SEP + base
    }

    enum class State { RECEIVED, VALIDATING, RUNNING, SUCCEEDED, FAILED, REJECTED }

    data class Entry(
        val requestId: String,
        val method: String,
        val pieceId: String,
        val base: String,
        val state: State,
        val response: JSONObject,
        val updatedAt: Long,
    )

    @Synchronized
    fun find(method: String, requestId: String): Entry? {
        val root = read()
        val composite = root.optJSONObject(keyOf(method, requestId))?.let { entryOf(it) }
        if (composite != null && composite.requestId == requestId) {
            return composite
        }
        val legacy = root.optJSONObject(requestId)?.let { entryOf(it) } ?: return null
        if (legacy.requestId != requestId) return null
        if (legacy.method != method) return null
        return legacy
    }

    @Synchronized
    fun findLegacyForeign(method: String, requestId: String): Entry? {
        val legacy = read().optJSONObject(requestId)?.let { entryOf(it) } ?: return null
        if (legacy.requestId != requestId) return null
        if (legacy.method == method) return null
        return legacy
    }

    @Synchronized
    fun save(entry: Entry) {
        write(keyOf(entry.method, entry.requestId), entry)
    }

    @Synchronized
    fun saveRejected(entry: Entry) {
        write(rejectKeyOf(entry.method, entry.requestId, entry.pieceId, entry.base), entry)
    }

    private fun write(key: String, entry: Entry) {
        val root = read()

        root.put(key, JSONObject().apply {
            put("requestId", entry.requestId)
            put("method", entry.method)
            put("pieceId", entry.pieceId)
            put("base", entry.base)
            put("state", entry.state.name)
            put("response", entry.response)
            put("updatedAt", entry.updatedAt)
        })
        StateFiles.writeAtomic(file, root.toString(2))
    }

    fun lockFor(pieceId: String): Any = synchronized(globalLock) {
        pieceLocks.computeIfAbsent(pieceId) { Any() }
    }

    private fun entryOf(raw: JSONObject): Entry {
        val state = runCatching { State.valueOf(raw.optString("state", "")) }
            .getOrDefault(State.RECEIVED)
        return Entry(
            requestId = raw.optString("requestId"),
            method = raw.optString("method"),
            pieceId = raw.optString("pieceId"),
            base = raw.optString("base"),
            state = state,
            response = raw.optJSONObject("response") ?: JSONObject(),
            updatedAt = raw.optLong("updatedAt"),
        )
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
}

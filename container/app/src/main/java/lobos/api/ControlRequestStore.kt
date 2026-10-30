package lobos.api

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import lobos.kernel.fs.StateFiles
import org.json.JSONObject

/**
 * Persistent idempotency state for formal control operations.
 *
 * State machine: RECEIVED -> VALIDATING -> RUNNING -> SUCCEEDED / FAILED, plus REJECTED.
 * The state file is the only truth source for a requestId; a repeated request with the same key
 * returns the stored response instead of running the operation again.
 */
class ControlRequestStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "control-requests.json")

    companion object {
        private val globalLock = Any()
        private val pieceLocks = ConcurrentHashMap<String, Any>()
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
    fun find(requestId: String): Entry? {
        val raw = read().optJSONObject(requestId) ?: return null
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

    @Synchronized
    fun save(entry: Entry) {
        val root = read()
        root.put(entry.requestId, JSONObject().apply {
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

    /** Per-piece lock: two concurrent requests must not write the same baseline. */
    fun lockFor(pieceId: String): Any = synchronized(globalLock) {
        pieceLocks.computeIfAbsent(pieceId) { Any() }
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
}

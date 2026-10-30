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
 *
 * 缺陷名（C-102）幂等命名空间只按 requestId 单独作键：同一个 requestId 用在两个不同方法上就撞在一起，
 * 请求标识被当成了全局操作键，而本来的语义是「幂等键 = 方法语义 + requestId」。
 * 这一族把落盘键改成复合键，requestId 本身原样留在条目里，报告出去的地方不改写。
 */
class ControlRequestStore(context: Context) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "control-requests.json")

    companion object {
        private val globalLock = Any()
        private val pieceLocks = ConcurrentHashMap<String, Any>()

        /** 复合键里方法语义与 requestId 之间只隔这一处分隔符。 */
        private const val KEY_SEP = "::"

        /** 被拒记录键的前缀，被拒记录不占那幂等主键。 */
        private const val REJECT_PREFIX = "rejected"

        /**
         * 缺陷名（C-102）幂等键改成「方法语义 + requestId」的复合键：同一个 requestId 用在两个不同方法上
         * 各占各的命名空间，两趟不撞。方法名取自固定方法名集合，集合里没有带分隔符的方法名，
         * requestId 里就算带了分隔符也撞不出第二个复合键。
         */
        fun keyOf(method: String, requestId: String): String = method + KEY_SEP + requestId

        /**
         * 缺陷名（C-102）被拒记录另占一条键，不顶掉那幂等主记录：「同方法同参数返回已存结果」那条保证
         * 不能被一次参数冲突顶没。键里带上那关键参数，两趟不同参数的被拒记录各自留着。
         */
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

    /**
     * 缺陷名（C-102）幂等查找先试复合键（方法语义 + requestId），再退回旧键（单独 requestId）：
     * 1. 复合键那条按「方法语义 + requestId」原样取回，同方法同参数的重复请求还是命中原条目。
     * 2. 旧键那条（历史文件按单独 requestId 写的）只认回它自己那个方法，旧文件里那一条旧记录不无声消失，
     * 它自己那个方法拿同一个 requestId 重放时还命中原条目。
     * 3. 旧键那条上属于别的方法的那一条不在这里复用，它由 findLegacyForeign 交回调用方判幂等冲突。
     */
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

    /**
     * 缺陷名（C-102）旧键（单独 requestId）下属于别的方法的那一条不是「没有」，它是幂等冲突：
     * 旧文件里那 requestId 已经挂在一个别的方法的操作上，本方法拿同一个 requestId
     * 不得静默复用那一条别的方法的已存结果，也不得静默另起一趟。
     */
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

    /**
     * 缺陷名（C-102）被拒记录另写一条键，那一次幂等主记录原样留着：
     * 同方法同参数的那一趟还是返回它自己那已存结果，不因为中途来过一次参数冲突就顶没。
     */
    @Synchronized
    fun saveRejected(entry: Entry) {
        write(rejectKeyOf(entry.method, entry.requestId, entry.pieceId, entry.base), entry)
    }

    private fun write(key: String, entry: Entry) {
        val root = read()
        // 缺陷名（C-102）落盘键从「单独 requestId」改成「方法语义 + requestId」的复合键：
        // 同一个 requestId 用在两个不同方法上各写各的那一条，两趟不互相顶掉。
        // requestId 本身原样写进条目，审计行与结构化响应仍报同一个 requestId。
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

    /** Per-piece lock: two concurrent requests must not write the same baseline. */
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

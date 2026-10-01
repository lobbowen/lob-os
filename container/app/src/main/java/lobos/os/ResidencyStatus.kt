package lobos.os

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ResidencyStatus {

    data class Snapshot(
        val anchorBound: Boolean,
        val adbReady: Boolean,
        val programsRunning: Int,
        val installedPrograms: Int,
        val runningIds: List<String>,
        val reasons: List<String>,
        val actions: List<String>,
        val tickGapMs: Long,
        val frozen: Boolean,
        val anchorRebindAttempts: Int,
        val startedAtMs: Long,
        val tier: String,
        val tierBasis: List<String>,
        val adbState: String,
        val adbAttempts: Int,
    )

    @Volatile private var last: JSONObject = JSONObject()

    fun record(s: Snapshot) {
        last = JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("anchorBound", s.anchorBound)
            put("adbReady", s.adbReady)
            put("programsRunning", s.programsRunning)
            put("installedPrograms", s.installedPrograms)
            put("runningIds", JSONArray(s.runningIds))
            put("degraded", s.reasons.isNotEmpty())
            put("degradedReasons", JSONArray(s.reasons))
            put("actions", JSONArray(s.actions))
            put("tickGapMs", s.tickGapMs)
            put("frozen", s.frozen)
            put("anchorRebindAttempts", s.anchorRebindAttempts)
            put("startedAtMs", s.startedAtMs)
            put("uptimeMs", if (s.startedAtMs > 0) System.currentTimeMillis() - s.startedAtMs else 0L)
            put("tier", s.tier)
            put("tierBasis", JSONArray(s.tierBasis))
            put("adbState", s.adbState)
            put("adbAttempts", s.adbAttempts)
        }
    }

    fun recordWake() {
        last = JSONObject(last.toString()).apply { put("lastBackstopWakeAt", System.currentTimeMillis()) }
    }

    fun snapshot(): JSONObject = last

    fun persist(ctx: android.content.Context) {
        runCatching {
            val d = File(ctx.filesDir, "os")
            d.mkdirs()
            StateFiles.writeAtomic(File(d, "residency.json"), last.toString())
        }
    }

    fun restore(ctx: android.content.Context) {
        runCatching {
            val f = File(File(ctx.filesDir, "os"), "residency.json")
            if (f.isFile) last = JSONObject(f.readText())
        }
    }

    fun detail(): String = runCatching {
        val s = last
        val reasons = s.optJSONArray("degradedReasons")
        val names = if (reasons == null || reasons.length() == 0) {
            ""
        } else {
            " 降级=" + (0 until reasons.length()).joinToString(",") { reasons.optString(it) }
        }
        "锚=" + (if (s.optBoolean("anchorBound")) "绑定" else "未绑定") +
            " ADB=" + s.optString("adbState", if (s.optBoolean("adbReady")) "online" else "?") +
            " 程序在跑=" + s.optInt("programsRunning") + "/" + s.optInt("installedPrograms") +
            " 档位=" + s.optString("tier", "?") +
            " 节拍间隔=" + s.optLong("tickGapMs") + "ms" + names
    }.getOrDefault("(无快照)")
}

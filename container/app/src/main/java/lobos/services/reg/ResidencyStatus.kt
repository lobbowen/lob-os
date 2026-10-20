package lobos.services.reg

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles

object ResidencyStatus {

    data class Snapshot(
        val accessibilityReady: Boolean,
        val programsRunning: Int,
        val installedPrograms: Int,
        val runningIds: List<String>,
        val reasons: List<String>,
        val actions: List<String>,
        val tickGapMs: Long,
        val frozen: Boolean,
            val startedAtMs: Long,
        // C-110 第二格：结论旁边必须带原话。这三样就是 programsRunning /
        // installedPrograms / runningIds 这三个数各自的原话，不是另一个结论。
        val runningPids: List<Int> = emptyList(),
        val ledgerEntries: List<String> = emptyList(),
        val installedIds: List<String> = emptyList(),
    )

    @Volatile private var last: JSONObject = JSONObject()

    fun record(s: Snapshot) {
        last = JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("accessibilityReady", s.accessibilityReady)
            put("programsRunning", s.programsRunning)
            put("installedPrograms", s.installedPrograms)
            put("runningIds", JSONArray(s.runningIds))
            put("degraded", s.reasons.isNotEmpty())
            put("degradedReasons", JSONArray(s.reasons))
            put("actions", JSONArray(s.actions))
            put("tickGapMs", s.tickGapMs)
            // C-110：这三个数各自的原话（不是另一个结论）—— 改一处就能换回原文。
            put("runningPids", JSONArray(s.runningPids))
            put("ledgerEntries", JSONArray(s.ledgerEntries))
            put("installedIds", JSONArray(s.installedIds))
            put("frozen", s.frozen)
            put("startedAtMs", s.startedAtMs)
            put("uptimeMs", if (s.startedAtMs > 0) System.currentTimeMillis() - s.startedAtMs else 0L)
        }
    }

    fun recordWake() {
        last = JSONObject(last.toString()).apply { put("lastBackstopWakeAt", System.currentTimeMillis()) }
    }

    fun snapshot(): JSONObject = last

    fun persist(ctx: android.content.Context) {
        runCatching {
            val d = SystemDirs.run(ctx)
            d.mkdirs()
            StateFiles.writeAtomic(File(d, "residency.json"), last.toString())
        }
    }

    fun restore(ctx: android.content.Context) {
        runCatching {
            val f = File(SystemDirs.run(ctx), "residency.json")
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
        "无障碍=" + (if (s.optBoolean("accessibilityReady")) "已连" else "未连") +
            " 程序在跑=" + s.optInt("programsRunning") + "/" + s.optInt("installedPrograms") +
            " 档位=" + s.optString("tier", "?") +
            " 节拍间隔=" + s.optLong("tickGapMs") + "ms" + names
    }.getOrDefault("(无快照)")
}

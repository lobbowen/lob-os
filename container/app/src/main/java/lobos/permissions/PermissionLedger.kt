package lobos.permissions

import android.content.Context
import java.io.File
import lobos.capability.AttemptOutcome
import lobos.capability.AttemptOutcomeRule
import lobos.capability.CapabilityEvidenceCollector
import lobos.capability.SilentAttempt
import lobos.log.Journal
import org.json.JSONObject

/**
 * 静默下发实测账 —— 「我们下发过什么、系统实际答了什么」。
 *
 * 与 dpkg 的 /var/log/dpkg.log 同一件事：把每一次操作与其真实后果记下来，
 * 供判断这台机器到底支持到哪一步。判定只用系统回的话
 * （AttemptOutcomeRule.of），不由我们自己猜。
 *
 * 落在 libvar/permission-ledger.json。OsHostService 与开场报告都从这儿读，
 * 「保活必需项齐不齐」也从这儿答。
 */
object PermissionLedger {

    private const val FILE = "permission-ledger.json"
    private const val SCHEMA = 1

    /**
     * 一次实测快照。
     *
     * records 是每一项的下发结果；missing 是「保活必需却没拿到」的项 ——
     * 保活必需的判据是 PermissionRoles 里的 GrantPolicy.ALWAYS_KEEP，
     * 不是我们另立的一张表。
     */
    data class Snap(
        val atMs: Long,
        val records: Map<String, SilentAttempt>,
        val missing: List<PermissionRole>,
    )

    private fun file(ctx: Context): File = File(SystemDirs.libvar(ctx), FILE)

    /** 最近一次实测；没有就是 null */
    fun read(ctx: Context): Snap? = runCatching {
        val f = file(ctx)
        if (!f.isFile) null else parse(JSONObject(f.readText()))
    }.getOrNull()

    fun readAll(ctx: Context): Map<String, SilentAttempt> =
        read(ctx)?.records ?: emptyMap()

    /**
     * 实测一遍 —— 只读系统状态，不改任何设置。
     *
     * 与 PieceScan.verify 的区别：那是拿登记符与盘上文件比对，这里是问系统
     * 「你刚那条指令答了什么」。两者不能互相替代。
     */
    fun register(ctx: Context): Snap {
        val ev = runCatching { CapabilityEvidenceCollector.systemReads(ctx) }.getOrNull()
        val records = ev?.permissionAttempts ?: emptyMap()
        // 保活必需（ALWAYS_KEEP）里，账上答不出 SILENT_OK 的就是缺
        val missing = PermissionRoles.declared()
            .filter { it.policy == GrantPolicy.ALWAYS_KEEP }
            .filter { records[it.id]?.outcome != AttemptOutcome.SILENT_OK }
        val snap = Snap(System.currentTimeMillis(), records, missing)
        runCatching { StateFiles.writeJson(file(ctx), encode(snap)) }
        Journal.note(
            ctx, "permission-ledger", missing.isEmpty(),
            "静默下发实测完成",
            "在册=" + records.size + "；保活必需缺=" + missing.joinToString { it.id },
        )
        return snap
    }

    private fun encode(s: Snap) = JSONObject().apply {
        put("schema", SCHEMA)
        put("at", s.atMs)
        put("records", JSONObject().apply {
            s.records.forEach { (id, a) ->
                put(id, JSONObject().apply {
                    put("outcome", a.outcome.name)
                    put("at", a.atMs)
                    put("detail", a.detail)
                })
            }
        })
        put("missing", org.json.JSONArray().apply { s.missing.forEach { put(it.id) } })
    }

    private fun parse(o: JSONObject): Snap {
        val records = HashMap<String, SilentAttempt>()
        o.optJSONObject("records")?.let { m ->
            m.keys().forEach { id ->
                val a = m.optJSONObject(id) ?: return@forEach
                val outcome = AttemptOutcomeRule.from(a.optString("outcome", "")) ?: return@forEach
                records[id] = SilentAttempt(outcome, a.optLong("at", 0L), a.optString("detail", ""))
            }
        }
        val missing = o.optJSONArray("missing")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").takeIf { it.isNotBlank() }?.let { PermissionRoles.of(it) }
            }
        } ?: emptyList()
        return Snap(o.optLong("at", 0L), records, missing)
    }
}

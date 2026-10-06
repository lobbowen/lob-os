package lobos.permissions

import android.content.Context
import lobos.os.Journal
import lobos.os.StateFiles
import org.json.JSONArray
import org.json.JSONObject

data class GrantRecord(
    val id: String,
    val purpose: String,
    val policy: GrantPolicy,
    val autoHeal: AutoHeal,
    val owner: String,
    val held: Boolean,
    val detail: String,
)

data class LedgerSnapshot(
    val atMs: Long,
    val records: List<GrantRecord>,
    val missing: List<GrantRecord>,
    val undeclared: List<PermissionRole>,
)

object PermissionLedger {

    const val FILE = "permission-ledger.json"
    const val SCHEMA = 1
    const val SCHEMA_KEY = "schema"
    const val ATTEMPTS = "attempts"

    fun file(ctx: Context) = java.io.File(java.io.File(ctx.filesDir, "os"), FILE)

    fun write(ctx: Context, snap: LedgerSnapshot) {
        StateFiles.writeJson(file(ctx), JSONObject().apply {
            put("schema", SCHEMA)
            put("atMs", snap.atMs)
            put("records", JSONArray().apply {
                for (r in snap.records) {
                    put(JSONObject().apply {
                        put("id", r.id)
                        put("purpose", r.purpose)
                        put("policy", r.policy.name)
                        put("autoHeal", r.autoHeal.name)
                        put("owner", r.owner)
                        put("held", r.held)
                        put("detail", r.detail)
                    })
                }
            })
            put(ATTEMPTS, readAttemptsRaw(ctx))
        })
    }

    private fun readAttemptsRaw(ctx: Context): JSONArray =
        StateFiles.readJson(file(ctx))?.optJSONArray(ATTEMPTS) ?: JSONArray()

    fun register(ctx: Context): LedgerSnapshot {
        val held = heldIds(ctx)
        val records = PermissionRoles.declared().map { role ->
            val spec = PermissionCatalog.byId(role.id)
            GrantRecord(
                id = role.id,
                purpose = role.purpose,
                policy = role.policy,
                autoHeal = role.autoHeal,
                owner = role.owner,
                held = role.id in held,
                detail = spec?.note ?: "",
            )
        }
        val snap = LedgerSnapshot(
            atMs = System.currentTimeMillis(),
            records = records,
            missing = records.filter { !it.held && it.policy == GrantPolicy.ALWAYS_KEEP },
            undeclared = PermissionRoles.undeclared(),
        )
        write(ctx, snap)
        Journal.note(
            ctx, "permission-ledger", if (snap.missing.isEmpty()) true else null,
            "权限登记在册：" + records.size + " 项，缺保活必需 " + snap.missing.size + " 项",
            snap.missing.joinToString { it.id },
        )
        return snap
    }

    @Synchronized
    fun readAll(ctx: Context): Map<String, lobos.capability.SilentAttempt> {
        val root = StateFiles.readJson(file(ctx)) ?: return emptyMap()
        val out = LinkedHashMap<String, lobos.capability.SilentAttempt>()

        fun accept(id: String, outcomeName: String, atMs: Long, detail: String) {
            if (id.isBlank() || PermissionCatalog.byId(id) == null) return
            val outcome = lobos.capability.AttemptOutcomeRule.from(outcomeName) ?: return
            val prev = out[id]
            if (prev == null || atMs >= prev.atMs) {
                out[id] = lobos.capability.SilentAttempt(outcome, atMs, detail)
            }
        }

        val arr = root.optJSONArray(ATTEMPTS)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                accept(
                    e.optString("id", ""),
                    e.optString("outcome", ""),
                    e.optLong("atMs", 0L),
                    e.optString("detail", ""),
                )
            }
        } else {
            val names = root.names()
            if (names != null) {
                for (i in 0 until names.length()) {
                    val id = names.optString(i)
                    if (id == SCHEMA_KEY || id == "atMs" || id == "records" || id == ATTEMPTS) continue
                    val e = root.optJSONObject(id) ?: continue
                    accept(id, e.optString("outcome", ""), e.optLong("atMs", 0L), e.optString("detail", ""))
                }
            }
        }
        return out
    }

    fun heldIds(ctx: Context): Set<String> = try {
        lobos.capability.CapabilityEvidenceCollector.systemReads(ctx).grants
    } catch (_: Throwable) {
        emptySet()
    }

    fun read(ctx: Context): LedgerSnapshot? {
        val o = StateFiles.readJson(file(ctx)) ?: return null
        val arr = o.optJSONArray("records") ?: return null
        val out = mutableListOf<GrantRecord>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out += GrantRecord(
                id = e.optString("id", ""),
                purpose = e.optString("purpose", ""),
                policy = runCatching { GrantPolicy.valueOf(e.optString("policy", "ON_DEMAND")) }
                    .getOrDefault(GrantPolicy.ON_DEMAND),
                autoHeal = runCatching { AutoHeal.valueOf(e.optString("autoHeal", "NO")) }
                    .getOrDefault(AutoHeal.NO),
                owner = e.optString("owner", ""),
                held = e.optBoolean("held", false),
                detail = e.optString("detail", ""),
            )
        }
        return LedgerSnapshot(
            atMs = o.optLong("atMs", 0L),
            records = out,
            missing = out.filter { !it.held && it.policy == GrantPolicy.ALWAYS_KEEP },
            undeclared = PermissionRoles.undeclared(),
        )
    }

    fun toJson(snap: LedgerSnapshot): JSONObject = JSONObject().apply {
        put("atMs", snap.atMs)
        put("count", snap.records.size)
        put("missingKeepAlive", JSONArray().apply { for (r in snap.missing) put(r.id) })
        put("undeclared", JSONArray().apply { for (r in snap.undeclared) put(r.id) })
        put("records", JSONArray().apply {
            for (r in snap.records) {
                put(JSONObject().apply {
                    put("id", r.id)
                    put("purpose", r.purpose)
                    put("policy", r.policy.name)
                    put("autoHeal", r.autoHeal.name)
                    put("owner", r.owner)
                    put("held", r.held)
                    put("detail", r.detail)
                })
            }
        })
    }
}
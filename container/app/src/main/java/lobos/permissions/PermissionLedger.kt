package lobos.permissions

import android.content.Context
import lobos.log.Journal
import lobos.os.StateFiles
import lobos.kernel.layout.SystemDirs
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

    fun file(ctx: Context) = java.io.File(SystemDirs.libvar(ctx), FILE)

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


    /**
     * 当前真正持有的权限 id 集合。
     *
     * 原本绕经 CapabilityEvidenceCollector.systemReads().grants —— 那是判据体系
     * 的中间产物。判据已随「APK 侧自己判权限、自己取权」那套设计一并清空。
     * 现在直接问 PermissionCenter：那才是权限状态的来源。
     */
    fun heldIds(ctx: Context): Set<String> = try {
        val center = PermissionCenter(ctx)
        PermissionCatalog.ALL.filter { center.isGranted(it) }.map { it.id }.toSet()
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
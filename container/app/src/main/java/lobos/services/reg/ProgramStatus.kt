package lobos.services.reg

import android.content.Context
import lobos.services.log.Journal
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.proc.ProcessLedger
import lobos.services.host.OsInit

data class ProgramStatus(
    val id: String,
    val version: String?,
    val role: String,
    val desired: Desired,
    val load: UnitState.Load,
    val active: UnitState.Active,
    val sub: UnitState.Sub,
    val supervised: Boolean,
    val detail: String,
    val startedAtMs: Long,
    val aliveMs: Long,
    val restarts: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("version", version ?: JSONObject.NULL)
        put("role", role)
        put("desired", desired.name)
        put("load", load.name)
        put("active", active.name)
        put("sub", sub.name)
        put("supervised", supervised)
        put("detail", detail)
        put("startedAtMs", startedAtMs)
        put("aliveMs", aliveMs)
        put("restarts", restarts)
    }
}

object ProgramStatusHub {

    @Volatile
    private var healthDetail: Map<String, String> = emptyMap()

    @Volatile
    private var restarts: Map<String, Int> = emptyMap()

    @Volatile
    private var quarantined: Set<String> = emptySet()

    @Volatile
    private var startedAt: Map<String, Long> = emptyMap()

    @Volatile
    private var lastState: Map<String, Triple<UnitState.Load, UnitState.Active, UnitState.Sub>> =
        emptyMap()

    fun publishHealth(id: String, healthy: Boolean, detail: String) {
        healthDetail = healthDetail.toMutableMap().apply { put(id, if (healthy) detail else "!$detail") }
    }

    fun publishRestarts(id: String, count: Int) {
        restarts = restarts.toMutableMap().apply { put(id, count) }
    }

    fun publishQuarantined(id: String, value: Boolean) {
        quarantined = if (value) quarantined + id else quarantined - id
    }

    fun forget(id: String) {
        healthDetail = healthDetail - id
        restarts = restarts - id
        quarantined = quarantined - id
        startedAt = startedAt - id
        lastState = lastState - id
    }

    fun clear() {
        healthDetail = emptyMap()
        restarts = emptyMap()
        quarantined = emptySet()
        startedAt = emptyMap()
        lastState = emptyMap()
    }

    fun snapshot(ctx: Context): List<ProgramStatus> {
        val installed = ProgramRegistry.listIds(ctx)
        val out = mutableListOf<ProgramStatus>()
        for (id in installed) {
            out += statusOf(ctx, id)
        }
        val liveIds = ProcessLedger.liveOwned(ctx).map { it.programId }.toSet()
        for (id in liveIds - installed.toSet()) {
            out += statusOf(ctx, id)
        }
        return out.sortedBy { it.id }
    }

    private fun exitHint(active: UnitState.Active, unit: UnitEntry?): String =
        if (active == UnitState.Active.FAILED) " 退出码=" + (unit?.exitCode ?: -1) else ""

    fun statusOf(ctx: Context, id: String): ProgramStatus {
        val entry = ProgramIndex.get(ctx, id)?.takeIf { it.level == Level.PROGRAM }
        val spec = ProgramRegistry.spec(ctx, id)
        val running = entry != null && entry.pid > 0 &&
            ProcessLedger.isOwnedAlive(entry.pid, entry.starttime)
        val detail = healthDetail[id] ?: ""
        val desired = entry?.desired ?: Desired.STOPPED
        val installed = spec != null || entry != null
        val unit = entry
        val healthy = running && !detail.startsWith("!")
        val load = UnitState.loadOf(unit ?: if (installed) ProgramIndex.empty(id, Level.PROGRAM) else null)
        val active = UnitState.activeOf(
            entry = unit,
            processAlive = running,
            startRequested = desired == Desired.RUNNING,
            stopRequested = desired == Desired.STOPPED,
        )
        val sub = UnitState.subOf(unit, running, healthy)
        val prev = lastState[id]
        val state = Triple(load, active, sub)
        if (prev != null && prev != state) {
            lastState = lastState + (id to state)
            Journal.note(
                ctx, "state", true,
                "状态变化：" + prev.second.label + " -> " + active.label,
                "id=" + id + " load=" + load.label + " sub=" + sub.label + exitHint(active, unit),
            )
        } else if (prev == null) {
            lastState = lastState + (id to state)
        }
        val at = startedAt[id] ?: 0L
        return ProgramStatus(
            id = id,
            version = spec?.version ?: entry?.version,
            role = spec?.role ?: entry?.role ?: "app",
            desired = desired,
            load = load,
            active = active,
            sub = sub,
            supervised = desired == Desired.RUNNING &&
                active != UnitState.Active.FAILED,
            detail = detail.removePrefix("!"),
            startedAtMs = at,
            aliveMs = if (at > 0L) System.currentTimeMillis() - at else 0L,
            restarts = restarts[id] ?: 0,
        )
    }

    fun toJson(ctx: Context): JSONObject {
        val list = snapshot(ctx)
        val running = list.count { it.active == UnitState.Active.ACTIVE }
        val unhealthy = list.count { it.sub == UnitState.Sub.DEGRADED }
        return JSONObject().apply {
            put("phase", OsInit.current(ctx).name)
            put("installed", list.size)
            put("running", running)
            put("unhealthy", unhealthy)
            put("runtimeUp", CapabilityRuntimeState.controlPlaneUp())
            put("programs", JSONArray().apply { for (p in list) put(p.toJson()) })
        }
    }

    fun summaryLine(ctx: Context): String {
        val list = snapshot(ctx)
        val running = list.count { it.active == UnitState.Active.ACTIVE }
        val unhealthy = list.count { it.sub == UnitState.Sub.DEGRADED }
        val phase = OsInit.current(ctx).label
        return buildString {
            append(phase)
            append(" · ")
            append(running)
            append("/")
            append(list.size)
            append(" 个程序在跑")
            if (unhealthy > 0) append(" · ").append(unhealthy).append(" 个异常")
        }
    }
}

object CapabilityRuntimeState {
    fun controlPlaneUp(): Boolean = runCatching {
        val snap = ResidencyStatus.snapshot()
        val at = snap.optLong("updatedAt", 0L)
        lobos.kernel.time.Heartbeat.fresh(at, System.currentTimeMillis())
    }.getOrDefault(false)
}
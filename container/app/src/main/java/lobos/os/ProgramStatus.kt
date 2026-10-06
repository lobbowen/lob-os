package lobos.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProgramStatus(
    val id: String,
    val version: String?,
    val role: String,
    val desired: Desired,
    val state: ProgramStateMachine.Run,
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
        put("state", state.name)
        put("supervised", supervised)
        put("detail", detail)
        put("startedAtMs", startedAtMs)
        put("aliveMs", aliveMs)
        put("restarts", restarts)
    }
}

object ProgramStatusHub {

    @Volatile
    private var runningIds: Set<String> = emptySet()

    @Volatile
    private var healthDetail: Map<String, String> = emptyMap()

    @Volatile
    private var restarts: Map<String, Int> = emptyMap()

    @Volatile
    private var quarantined: Set<String> = emptySet()

    @Volatile
    private var startedAt: Map<String, Long> = emptyMap()

    @Volatile
    private var startRequested: Set<String> = emptySet()

    @Volatile
    private var lastState: Map<String, ProgramStateMachine.Run> = emptyMap()

    fun publishRunning(ids: Set<String>) {
        runningIds = ids
    }

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
        startRequested = startRequested - id
        lastState = lastState - id
        runningIds = runningIds - id
    }

    fun clear() {
        runningIds = emptySet()
        healthDetail = emptyMap()
        restarts = emptyMap()
        quarantined = emptySet()
        startedAt = emptyMap()
        startRequested = emptySet()
        lastState = emptyMap()
    }

    fun snapshot(ctx: Context): List<ProgramStatus> {
        val installed = ProgramRegistry.listIds(ctx)
        val out = mutableListOf<ProgramStatus>()
        for (id in installed) {
            out += statusOf(ctx, id)
        }
        for (id in runningIds - installed.toSet()) {
            out += statusOf(ctx, id)
        }
        return out.sortedBy { it.id }
    }

    fun statusOf(ctx: Context, id: String): ProgramStatus {
        val entry = ProgramIndex.all(ctx).firstOrNull { it.id == id && it.level == Level.APPLICATION }
        val spec = ProgramRegistry.spec(ctx, id)
        val running = runningIds.contains(id)
        val detail = healthDetail[id] ?: ""
        val desired = entry?.desired ?: Desired.STOPPED
        val installed = spec != null || entry != null
        val prev = lastState[id]
        val state = ProgramStateMachine.resolve(
            desired = desired,
            installed = installed,
            manifestValid = spec == null || spec.invalid == null,
            processAlive = running,
            healthy = running && !detail.startsWith("!"),
            quarantined = quarantined.contains(id),
            startRequested = startRequested.contains(id),
        )
        if (prev != null && prev != state) {
            val why = ProgramStateMachine.transition(
                prev, state, desired, installed, spec == null || spec.invalid == null,
            )
            lastState = lastState + (id to state)
            if (why != null) {
                val msg = "非法状态转换：" + prev + " -> " + state
                Journal.note(ctx, "state", false, msg, "id=" + id + " " + why)
            }
        } else if (prev == null) {
            lastState = lastState + (id to state)
        }
        val at = startedAt[id] ?: 0L
        return ProgramStatus(
            id = id,
            version = spec?.version ?: entry?.version,
            role = spec?.role ?: entry?.role ?: "app",
            desired = desired,
            state = state,
            supervised = ProgramStateMachine.supervised(state, desired),
            detail = detail.removePrefix("!"),
            startedAtMs = at,
            aliveMs = if (at > 0L) System.currentTimeMillis() - at else 0L,
            restarts = restarts[id] ?: 0,
        )
    }

    fun toJson(ctx: Context): JSONObject {
        val list = snapshot(ctx)
        val running = list.count { it.state == ProgramStateMachine.Run.RUNNING }
        val unhealthy = list.count { it.state == ProgramStateMachine.Run.UNHEALTHY }
        return JSONObject().apply {
            put("phase", OsInit.current(ctx).name)
            put("installed", list.size)
            put("running", running)
            put("unhealthy", unhealthy)
            put("runtimeUp", CapabilityRuntimeProbe.controlPlaneUp())
            put("programs", JSONArray().apply { for (p in list) put(p.toJson()) })
        }
    }

    fun summaryLine(ctx: Context): String {
        val list = snapshot(ctx)
        val running = list.count { it.state == ProgramStateMachine.Run.RUNNING }
        val unhealthy = list.count { it.state == ProgramStateMachine.Run.UNHEALTHY }
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

object CapabilityRuntimeProbe {
    fun controlPlaneUp(): Boolean = runCatching {
        val snap = ResidencyStatus.snapshot()
        val at = snap.optLong("updatedAt", 0L)
        at > 0L && System.currentTimeMillis() - at < 60_000L
    }.getOrDefault(false)
}
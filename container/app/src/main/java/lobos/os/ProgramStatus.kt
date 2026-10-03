package lobos.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ProgramRunState {
    RUNNING,
    STOPPED,
    UNHEALTHY,
    RESTARTING,
    ABSENT,
}

data class ProgramStatus(
    val id: String,
    val version: String?,
    val role: String,
    val desired: Desired,
    val state: ProgramRunState,
    val supervised: Boolean,
    val detail: String,
    val startedAtMs: Long,
    val aliveMs: Long,
    val restarts: Int,
    val quarantined: Boolean,
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
        put("quarantined", quarantined)
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

    fun publishStarted(id: String, atMs: Long) {
        startedAt = startedAt.toMutableMap().apply { put(id, atMs) }
    }

    fun forget(id: String) {
        healthDetail = healthDetail - id
        restarts = restarts - id
        quarantined = quarantined - id
        startedAt = startedAt - id
        runningIds = runningIds - id
    }

    fun clear() {
        runningIds = emptySet()
        healthDetail = emptyMap()
        restarts = emptyMap()
        quarantined = emptySet()
        startedAt = emptyMap()
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
        val quarantine = quarantined.contains(id)
        val detail = healthDetail[id] ?: ""
        val state = when {
            quarantine -> ProgramRunState.RESTARTING
            running && detail.startsWith("!") -> ProgramRunState.UNHEALTHY
            running -> ProgramRunState.RUNNING
            spec == null && entry == null -> ProgramRunState.ABSENT
            else -> ProgramRunState.STOPPED
        }
        val at = startedAt[id] ?: 0L
        return ProgramStatus(
            id = id,
            version = spec?.version ?: entry?.version,
            role = spec?.role ?: entry?.role ?: "app",
            desired = entry?.desired ?: Desired.STOPPED,
            state = state,
            supervised = running,
            detail = detail.removePrefix("!"),
            startedAtMs = at,
            aliveMs = if (at > 0L) System.currentTimeMillis() - at else 0L,
            restarts = restarts[id] ?: 0,
            quarantined = quarantine,
        )
    }

    fun toJson(ctx: Context): JSONObject {
        val list = snapshot(ctx)
        val running = list.count { it.state == ProgramRunState.RUNNING }
        val unhealthy = list.count { it.state == ProgramRunState.UNHEALTHY }
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
        val running = list.count { it.state == ProgramRunState.RUNNING }
        val unhealthy = list.count { it.state == ProgramRunState.UNHEALTHY }
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
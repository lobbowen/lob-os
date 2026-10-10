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
    /**
     * 状态三列 —— 照抄 systemctl list-units 的 LOAD/ACTIVE/SUB。
     *
     * 此前是一个自造的 Run 枚举（8 个值里有 4 个 systemd 没有）。
     * 现在是官方三列：**状态是算出来的，不是记下来的**。
     */
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
        // systemctl list-units 的三列，不是一个自造的 state
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
    /** 上一次的三列（只为记变化，不作为状态的来源） */
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
        // 「还有谁在跑」问**账本**，不抄监管池的内存集合。
        //   账本是内核侧的事实（带 starttime，能防 pid 复用），
        //   监管池那份只是「谁有监管器」的缓存 —— 两者不是一回事：
        //   · 进程在跑但监管器已撤（程序自己 daemonize 出去）→ 账本知道，池子不知道
        //   · 池子还留着 key 但进程早已没了 → 池子以为在跑，账本说没有
        // 照 systemd(1) 的做法，ActiveState 由内核的账算，不是某个守护者的记忆。
        // 另外这一条也让「已卸载但进程还在跑」的 id 仍出现在列表里，
        // 不至于从面板上凭空消失。
        val liveIds = ProcessLedger.liveOwned(ctx).map { it.programId }.toSet()
        for (id in liveIds - installed.toSet()) {
            out += statusOf(ctx, id)
        }
        return out.sortedBy { it.id }
    }

    /** 退出时才补一句退出码 —— 单独抽出来，避免多行实参里裸跟一个括号 */
    private fun exitHint(active: UnitState.Active, unit: UnitEntry?): String =
        if (active == UnitState.Active.FAILED) " 退出码=" + (unit?.exitCode ?: -1) else ""

    fun statusOf(ctx: Context, id: String): ProgramStatus {
        val entry = ProgramIndex.get(ctx, id)?.takeIf { it.level == Level.PROGRAM }
        val spec = ProgramRegistry.spec(ctx, id)
        // 「进程在不在」问**账本**，不读SupervisorPool 的内存集合 ——
        // systemd 的 ActiveState 由 cgroup（内核的账）算出，不是某个守护者的记忆。
        // 账本带 starttime，能防 pid 复用（Linux 自己也这么做）。
        val running = entry != null && entry.pid > 0 &&
            ProcessLedger.isOwnedAlive(entry.pid, entry.starttime)
        val detail = healthDetail[id] ?: ""
        val desired = entry?.desired ?: Desired.STOPPED
        val installed = spec != null || entry != null
        // 三列的判据全是事实：注册表那一条 + 进程账本 + 探活结果
        //（systemd 的 ActiveState 也是这么算的，不额外存一个状态）
        val unit = entry
        val healthy = running && !detail.startsWith("!")
        val load = UnitState.loadOf(unit ?: if (installed) ProgramIndex.empty(id, Level.PROGRAM) else null)
        val active = UnitState.activeOf(
            entry = unit,
            processAlive = running,
            // 「请求过起/停」不另存一份内存集合 —— 就是注册表里那条 desired。
            // 此前这里读 startRequested/stopRequested 两个 Set：stopRequested
            // 从未声明，startRequested 只有 forget/clear 会删、没有任何写入点，
            // 于是「想跑但没跑起来」与「没想跑」判成同一种。
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
            // 「要不要被监管」= 想跑 且 现在没在跑到该跑的态
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
        at > 0L && System.currentTimeMillis() - at < 60_000L
    }.getOrDefault(false)
}
package lobos.services.host

import lobos.services.host.ResidencyPolicy
import lobos.services.reg.ResidencyStatus

enum class OsPhase {
    BOOTING,

    RUNNING,

    DEGRADED,

    STOPPING;

    val label: String
        get() = when (this) {
            BOOTING -> "启动中"
            RUNNING -> "运行中"
            DEGRADED -> "降级"
            STOPPING -> "停止中"
        }
}

data class OsFacts(
    val readingsCollected: Boolean = false,
    val controlPlaneUp: Boolean = false,
)

data class OsSnapshot(
    val phase: OsPhase,
    val facts: OsFacts,
    val interrupted: String? = null,
    val atMs: Long = 0L,
)

object OsPhaseRule {

    fun degraded(facts: OsFacts): Boolean =
        !facts.controlPlaneUp || noProgram() || noneRunning()

    private fun noProgram(): Boolean {
        val reasons = runCatching { ResidencyStatus.snapshot().optJSONArray("degradedReasons") }.getOrNull()
            ?: return false
        val all = (0 until reasons.length()).map { reasons.optString(it) }
        return all.contains(ResidencyPolicy.REASON_NO_PROGRAM)
    }

    private fun noneRunning(): Boolean {
        val reasons = runCatching { ResidencyStatus.snapshot().optJSONArray("degradedReasons") }.getOrNull()
            ?: return false
        val all = (0 until reasons.length()).map { reasons.optString(it) }
        return all.contains(ResidencyPolicy.REASON_NONE_RUNNING)
    }

    fun next(prev: OsPhase, facts: OsFacts): OsPhase? = when {
        !facts.readingsCollected -> null
        prev == OsPhase.BOOTING || prev == OsPhase.STOPPING -> null
        degraded(facts) && prev != OsPhase.DEGRADED -> OsPhase.DEGRADED
        !degraded(facts) && prev == OsPhase.DEGRADED -> OsPhase.RUNNING
        else -> null
    }

    fun reason(next: OsPhase, facts: OsFacts): String = when {
        !facts.controlPlaneUp -> "控制面不在线"
        noProgram() -> "未安装任何程序"
        noneRunning() -> "没有程序在运行"
        else -> "读数恢复"
    }
}

package lobos.os

import lobos.capability.ProbeOutcome
import lobos.lifecycle.AnchorState
import lobos.lifecycle.ResidencyPolicy

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
    val channel: ProbeOutcome = ProbeOutcome.NEVER_RUN,
    val anchor: AnchorState = AnchorState.UNKNOWN,
)

data class OsSnapshot(
    val phase: OsPhase,
    val facts: OsFacts,
    val interrupted: String? = null,
    val atMs: Long = 0L,
)

object OsPhaseRule {

    fun degraded(facts: OsFacts): Boolean =
        !facts.controlPlaneUp || facts.anchor == AnchorState.UNBOUND || channelDegraded()

    private fun channelDegraded(): Boolean {
        val reasons = runCatching { ResidencyStatus.snapshot().optJSONArray("degradedReasons") }.getOrNull()
            ?: return false
        return ResidencyPolicy.hostDegraded((0 until reasons.length()).map { reasons.optString(it) })
    }

    fun next(prev: OsPhase, facts: OsFacts): OsPhase? = when {
        !facts.readingsCollected -> null
        prev == OsPhase.BOOTING || prev == OsPhase.STOPPING -> null
        degraded(facts) && prev != OsPhase.DEGRADED -> OsPhase.DEGRADED
        !degraded(facts) && prev == OsPhase.DEGRADED -> OsPhase.RUNNING
        else -> null
    }

    fun reason(next: OsPhase, facts: OsFacts): String = when {
        next == OsPhase.DEGRADED && !facts.controlPlaneUp -> "控制面不在线"
        channelDegraded() -> "ADB 通道不在线（锚的能力面受损）"
        next == OsPhase.DEGRADED -> "锚不在位"
        else -> "读数恢复"
    }
}

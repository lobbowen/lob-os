package lobos.android.host

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
        !facts.readingsCollected || !facts.controlPlaneUp

    fun next(prev: OsPhase, facts: OsFacts): OsPhase? = when {
        !facts.readingsCollected -> null
        prev == OsPhase.BOOTING || prev == OsPhase.STOPPING -> null
        degraded(facts) && prev != OsPhase.DEGRADED -> OsPhase.DEGRADED
        !degraded(facts) && prev == OsPhase.DEGRADED -> OsPhase.RUNNING
        else -> null
    }

    fun reason(next: OsPhase, facts: OsFacts): String = when {
        !facts.readingsCollected -> "自检读数还没齐"
        !facts.controlPlaneUp -> "控制面不在线"
        else -> "控制面就绪"
    }
}

package lobos.os

object ProgramStateMachine {

    enum class Run {
        STARTING,
        RUNNING,
        STOPPED,
        RESTARTING,
        UNHEALTHY,
        QUARANTINED,
        ABSENT,
        BROKEN,
    }

    fun active(r: Run): Boolean = r == Run.RUNNING || r == Run.STARTING || r == Run.RESTARTING || r == Run.UNHEALTHY

    fun counted(r: Run): Boolean = r == Run.RUNNING || r == Run.RESTARTING || r == Run.UNHEALTHY

    fun supervised(r: Run, desired: Desired): Boolean =
        desired == Desired.RUNNING && r != Run.QUARANTINED && r != Run.BROKEN && r != Run.ABSENT

    fun resolve(
        desired: Desired,
        installed: Boolean,
        manifestValid: Boolean,
        processAlive: Boolean,
        healthy: Boolean,
        quarantined: Boolean,
        startRequested: Boolean,
    ): Run = when {
        !installed -> Run.ABSENT
        !manifestValid -> Run.BROKEN
        desired == Desired.FROZEN -> Run.STOPPED
        desired == Desired.STOPPED -> Run.STOPPED
        quarantined -> Run.QUARANTINED
        startRequested -> Run.STARTING
        processAlive && healthy -> Run.RUNNING
        processAlive -> Run.UNHEALTHY
        else -> Run.RESTARTING
    }

    fun transition(
        from: Run,
        to: Run,
        desired: Desired,
        installed: Boolean,
        manifestValid: Boolean,
    ): String? {
        if (from == to) return null
        if (!installed && to != Run.ABSENT) return "未安装却上报 $to"
        if (installed && !manifestValid && to != Run.BROKEN) return "清单非法却上报 $to"
        if (to == Run.QUARANTINED && desired == Desired.FROZEN) return "冻结意图不该进入隔离"
        if (counted(to) && desired == Desired.FROZEN) return "冻结意图不该处于活跃态 $to"
        if (active(to) && desired == Desired.STOPPED) return "停止意图不该处于活跃态 $to"
        return null
    }
}

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

    fun names(): List<String> = Run.entries.map { it.name }

    fun runOf(raw: String): Run =
        runCatching { Run.valueOf(raw.trim().uppercase()) }.getOrDefault(Run.STOPPED)

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
        if (to.counted() && desired == Desired.FROZEN) return "冻结意图不该处于活跃态 $to"
        if (to.active() && desired == Desired.STOPPED) return "停止意图不该处于活跃态 $to"
        if (from == Run.RUNNING && to == Run.RESTARTING) return "RUNNING -> RESTARTING 只能由存活转存活判定触发"
        return null
    }

    fun toJson(ctx: Context): JSONObject = JSONObject().apply {
        put("runs", org.json.JSONArray(names()))
        put("desireds", org.json.JSONArray(Desired.entries.map { it.name }))
        put("active", org.json.JSONArray(names().filter { active(runOf(it)) }))
        put("counted", org.json.JSONArray(names().filter { counted(runOf(it)) }))
        put("table", org.json.JSONArray(Desired.entries.map { d ->
            JSONObject().apply {
                put("desired", d.name)
                put(
                    "reachable",
                    org.json.JSONArray(
                        listOf(
                            Run.ABSENT, Run.BROKEN, Run.STOPPED, Run.STARTING,
                            Run.RUNNING, Run.UNHEALTHY, Run.RESTARTING, Run.QUARANTINED,
                        ).filter { reachable(d, it) }.map { it.name },
                    ),
                )
            }
        }))
    }

    fun reachable(d: Desired, r: Run): Boolean = when (r) {
        Run.ABSENT -> true
        Run.BROKEN -> true
        Run.STOPPED -> true
        Run.QUARANTINED -> d == Desired.RUNNING
        Run.STARTING -> d == Desired.RUNNING
        Run.RESTARTING -> d == Desired.RUNNING
        Run.RUNNING -> d == Desired.RUNNING
        Run.UNHEALTHY -> d == Desired.RUNNING
    }
}

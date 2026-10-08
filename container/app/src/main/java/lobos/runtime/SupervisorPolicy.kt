package lobos.runtime

import lobos.os.Backoff

object SupervisorPolicy {

    enum class BootOutcome {
        RUNNING,

        FAILED,

        NO_PROGRAM,
    }

    const val BACKOFF_BASE_MS = 1_000L
    const val BACKOFF_MAX_MS = 30_000L
    const val BACKOFF_STEPS = 5

    const val STABLE_MS = 15_000L

    const val NO_PROGRAM_BACKOFF_MS = 60_000L

    const val CHECK_INTERVAL_MS = 15_000L

    const val HEALTH_FAIL_THRESHOLD = 3

    const val RESTART_WINDOW_MS = 10 * 60_000L

    const val MAX_RESTARTS_IN_WINDOW = 5

    const val QUARANTINE_POLL_MS = 30_000L

    const val QUARANTINE_HALF_OPEN_POLLS = 4

    fun backoffMs(restartCount: Int): Long =
        Backoff.exponential(restartCount, BACKOFF_BASE_MS, BACKOFF_MAX_MS)

    fun backoffFor(outcome: BootOutcome, restartCount: Int): Long =
        if (outcome == BootOutcome.NO_PROGRAM) NO_PROGRAM_BACKOFF_MS else backoffMs(restartCount)

    fun nextRestartCount(currentCount: Int, bootOk: Boolean, aliveMs: Long): Int =
        if (bootOk && aliveMs >= STABLE_MS) 0 else currentCount + 1

    fun bootSucceeded(outcome: BootOutcome): Boolean = outcome == BootOutcome.RUNNING

    fun shouldQuarantine(restartsWithinWindow: Int): Boolean =
        restartsWithinWindow >= MAX_RESTARTS_IN_WINDOW

    fun healthyByStreak(failStreak: Int): Boolean = failStreak < HEALTH_FAIL_THRESHOLD

    fun pruneRestartWindow(window: MutableList<Long>, now: Long) {
        while (window.isNotEmpty() && now - window.first() > RESTART_WINDOW_MS) {
            window.removeAt(0)
        }
    }

    fun exitNote(wasReady: Boolean): String =
        if (wasReady) "（曾就绪后退出 —— 排查方向：启动后崩溃/单实例锁冲突，而非拉不起）" else ""
}

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

    fun bootSucceeded(outcome: BootOutcome): Boolean = outcome == BootOutcome.RUNNING


    fun healthyByStreak(failStreak: Int): Boolean = failStreak < HEALTH_FAIL_THRESHOLD

    fun exitNote(wasReady: Boolean): String =
        if (wasReady) "（曾就绪后退出 —— 排查方向：启动后崩溃/单实例锁冲突，而非拉不起）" else ""
    /**
     * 记一次重启 —— **落进注册表**，不是内存里的计数。
     *
     * systemctl(1)：「properties shown by systemctl show … expose RUNTIME STATE
     * IN ADDITION TO CONFIGURATION」—— NRestarts / ExecMainStatus 都是
     * systemctl show 查得到的属性。
     *
     * 此前是 InstanceHost.superviseOne 的局部变量：进程一重启就归零，
     * 于是 maxRestarts 与 MAX_RESTARTS_IN_WINDOW 形同虚设 ——
     * 驻留程序被反复杀掉就会无限重启。
     *
     * @return 窗口内累计的重启次数（已按窗口剪过）
     */
    fun noteRestart(ctx: android.content.Context, unit: String, atMs: Long): Int {
        val cur = lobos.os.ProgramIndex.get(ctx, unit)
        if (cur == null) return 0
        var n = cur.restarts + 1
        // 超过窗口就当重新开始计（systemd 的 StartLimitIntervalSec 同义）
        if (atMs - cur.exitedAt > RESTART_WINDOW_MS) n = 1
        lobos.os.ProgramIndex.mutate(ctx, unit) {
            it.edited(restarts = n, exitedAt = atMs)
        }
        return n
    }

    /** 记一次退出 —— 退出码落进注册表（ExecMainStatus 的对应物） */
    fun noteExit(
        ctx: android.content.Context,
        unit: String,
        exitCode: Int,
        atMs: Long,
        failure: String = "",
    ) {
        runCatching {
            lobos.os.ProgramIndex.mutate(ctx, unit) {
                it.edited(
                    exitCode = exitCode,
                    exitedAt = atMs,
                    lastFailure = failure,
                    pid = 0,
                    starttime = 0L,
                )
            }
        }
    }

    /** 记「起来了」—— pid 与 starttime 落进注册表（MainPID 的对应物） */
    fun noteStarted(
        ctx: android.content.Context,
        unit: String,
        pid: Int,
        starttime: Long,
    ) {
        runCatching {
            lobos.os.ProgramIndex.mutate(ctx, unit) {
                it.edited(pid = pid, starttime = starttime)
            }
        }
    }

    /**
     * 稳定运行足够久就把计数清零 —— systemd 的 NRestarts 语义：
     * 跑够 StartLimitIntervalSec 就算「不是反复挂」。
     */
    fun clearIfStable(ctx: android.content.Context, unit: String, aliveMs: Long) {
        if (aliveMs < STABLE_MS) return
        runCatching {
            if (lobos.os.ProgramIndex.get(ctx, unit)?.restarts ?: 0 <= 0) return
            lobos.os.ProgramIndex.mutate(ctx, unit) { it.edited(restarts = 0) }
        }
    }
}

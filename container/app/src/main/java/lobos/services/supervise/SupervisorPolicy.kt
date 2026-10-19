package lobos.services.supervise

import lobos.services.reg.edited

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
    fun noteRestart(ctx: android.content.Context, unit: String, atMs: Long): Int {
        val cur = lobos.services.reg.ProgramIndex.get(ctx, unit)
        if (cur == null) return 0
        var n = cur.restarts + 1
        if (atMs - cur.exitedAt > RESTART_WINDOW_MS) n = 1
        lobos.services.reg.ProgramIndex.mutate(ctx, unit) {
            it.edited(restarts = n, exitedAt = atMs)
        }
        return n
    }

    fun noteExit(
        ctx: android.content.Context,
        unit: String,
        exitCode: Int,
        atMs: Long,
        failure: String = "",
    ) {
        runCatching {
            lobos.services.reg.ProgramIndex.mutate(ctx, unit) {
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

    fun noteStarted(
        ctx: android.content.Context,
        unit: String,
        pid: Int,
        starttime: Long,
    ) {
        // 失败必须留痕：账本（ProcessLedger.begin）记了真 pid，而索引停在 pid=0，
        // 两侧没有任何对账 —— 原来这层 runCatching 把失败吞成「无」，谁也看不见。
        val ok = runCatching {
            lobos.services.reg.ProgramIndex.mutate(ctx, unit) {
                it.edited(pid = pid, starttime = starttime)
            }
        }.getOrElse { e ->
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, "ledger", false, "回写 program-index 失败",
                "unit=" + unit + " pid=" + pid + " starttime=" + starttime +
                    "（账本已记真值而索引未更新，两侧现无对账） 原因=" +
                    (e::class.java.simpleName + ": " + e.message),
            )
            false
        }
        if (!ok) {
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, "ledger", false, "回写 program-index 未生效",
                "unit=" + unit + " pid=" + pid + " starttime=" + starttime,
            )
        }
    }

    fun clearIfStable(ctx: android.content.Context, unit: String, aliveMs: Long) {
        if (aliveMs < STABLE_MS) return
        runCatching {
            if (lobos.services.reg.ProgramIndex.get(ctx, unit)?.restarts ?: 0 <= 0) return
            lobos.services.reg.ProgramIndex.mutate(ctx, unit) { it.edited(restarts = 0) }
        }
    }
}

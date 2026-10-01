package lobos.lifecycle

import lobos.os.Backoff

object ResidencyPolicy {

    const val FAST_TICK_MS = 15_000L

    const val FREEZE_GAP_MS = 5 * 60_000L

    const val OWNER_PROBE_TTL_MS = 30 * 60_000L

    const val ANCHOR_REBIND_BASE_MS = 30_000L

    const val ANCHOR_REBIND_MAX_MS = 15 * 60_000L

    const val ANCHOR_REBIND_MAX_ATTEMPTS = 5

    fun shouldRebind(anchorProtected: Boolean, attempts: Int): Boolean =
        !anchorProtected && attempts < ANCHOR_REBIND_MAX_ATTEMPTS

    fun anchorBackoffMs(attempt: Int): Long =
        Backoff.exponential(attempt, ANCHOR_REBIND_BASE_MS, ANCHOR_REBIND_MAX_MS)

    const val WAKE_BACKSTOP_MS = 15 * 60_000L

    fun frozen(gapMs: Long): Boolean = gapMs > FREEZE_GAP_MS

    fun hostDegraded(reasons: List<String>): Boolean =
        reasons.any { it == REASON_ANCHOR || it == REASON_ADB }

    fun degradedReasons(
        anchorProtected: Boolean,
        adbReady: Boolean,
        programsRunning: Int,
        installedPrograms: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (!anchorProtected) out.add(REASON_ANCHOR)
        if (!adbReady) out.add(REASON_ADB)
        if (installedPrograms <= 0) out.add(REASON_NO_PROGRAM)
        else if (programsRunning <= 0) out.add(REASON_NONE_RUNNING)
        return out
    }

    fun actions(reasons: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (r in reasons) {
            when (r) {
                REASON_ANCHOR -> out.add("到系统设置开启无障碍服务；开屏/开机/能力获取流程会重新挂锚（内核只观测与上报，不做复活式兜底）")
                REASON_ADB -> out.add("重连 ADB 通道（自带 ADB 客户端）；必要时重新走无线调试配对")
                REASON_NO_PROGRAM -> out.add("安装程序包（OTA 或本地包）")
                REASON_NONE_RUNNING -> out.add("经内核重启该程序；检查 CURRENT 与清单入口是否存在")
                else -> out.add("查看 os.journal.read 定位原因")
            }
        }
        return out
    }

    const val REASON_ANCHOR = "锚未绑定"

    const val REASON_ADB = "ADB 通道不可用"

    const val REASON_NO_PROGRAM = "未安装任何程序"

    const val REASON_NONE_RUNNING = "没有程序在运行"
}

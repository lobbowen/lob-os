package lobos.lifecycle


object ResidencyPolicy {

    const val FAST_TICK_MS = 15_000L

    const val FREEZE_GAP_MS = 5 * 60_000L

    const val OWNER_PROBE_TTL_MS = 30 * 60_000L


    const val WAKE_BACKSTOP_MS = 15 * 60_000L

    fun frozen(gapMs: Long): Boolean = gapMs > FREEZE_GAP_MS
    fun degradedReasons(
        accessibilityReady: Boolean,
        adbReady: Boolean,
        programsRunning: Int,
        installedPrograms: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (!accessibilityReady) out.add(REASON_ANCHOR)
        if (!adbReady && ADB_REQUIRED_FOR_LIVENESS) out.add(REASON_ADB)
        if (installedPrograms <= 0) out.add(REASON_NO_PROGRAM)
        else if (programsRunning <= 0) out.add(REASON_NONE_RUNNING)
        return out
    }

    fun actions(reasons: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (r in reasons) {
            when (r) {
                REASON_ANCHOR -> out.add("无障碍未开启：UI 自动化不可用（保活不依赖它）")
                REASON_ADB -> out.add("重连 ADB 通道（可选组件，不影响系统存活）")
                REASON_NO_PROGRAM -> out.add("安装程序包（OTA 或本地包）")
                REASON_NONE_RUNNING -> out.add("经宿主重启该程序；检查 CURRENT 与清单入口是否存在")
                else -> out.add("查看 os.journal.read 定位原因")
            }
        }
        return out
    }

    const val ADB_REQUIRED_FOR_LIVENESS = false

    const val REASON_ANCHOR = "未开启无障碍（UI 自动化不可用）"

    const val REASON_ADB = "ADB 通道不可用"

    const val REASON_NO_PROGRAM = "未安装任何程序"

    const val REASON_NONE_RUNNING = "没有程序在运行"
}

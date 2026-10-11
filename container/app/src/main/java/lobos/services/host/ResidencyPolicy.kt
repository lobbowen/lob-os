package lobos.services.host

object ResidencyPolicy {

    const val FAST_TICK_MS = 15_000L

    const val FREEZE_GAP_MS = 5 * 60_000L

    const val OWNER_CHECK_TTL_MS = 30 * 60_000L

    const val WAKE_BACKSTOP_MS = 15 * 60_000L

    fun frozen(gapMs: Long): Boolean = gapMs > FREEZE_GAP_MS

    fun degradedReasons(
        programsRunning: Int,
        installedPrograms: Int,
    ): List<String> {
        val out = mutableListOf<String>()
        if (installedPrograms <= 0) out.add(REASON_NO_PROGRAM)
        else if (programsRunning <= 0) out.add(REASON_NONE_RUNNING)
        return out
    }

    fun actions(reasons: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (r in reasons) {
            when (r) {
                REASON_NO_PROGRAM -> out.add("没有可运行的程序 —— 控制面板也是程序，得先把它装上")
                REASON_NONE_RUNNING -> out.add("经宿主重启该程序；检查 CURRENT 与清单入口是否存在")
                else -> out.add("查看 os.journal.read 定位原因")
            }
        }
        return out
    }

    const val REASON_NO_PROGRAM = "未安装任何程序"

    const val REASON_NONE_RUNNING = "没有程序在运行"
}

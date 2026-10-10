package lobos.lifecycle

/**
 * 常驻状态的降级判定。
 *
 * 三条降级理由，都是「系统能不能继续跑」的事实，不含任何权限判据 ——
 * 判断「系统允许什么」是权限服务的事，这里只回答「现在还活着吗」。
 *
 * 无障碍在其中的定位曾长期矛盾：CapabilityCatalog 与 OsAccessibilityService 说
 * 「不承担保活」，PermissionRoles 又把它写成「后台保活锚」，SetupActivity 干脆
 * 把它算进「保活必需」。那是两代设计叠在一起。现已定死：
 *
 *   **无障碍 = UI 自动化的执行体，不是保活锚。**
 *   真正托住后台的是前台服务（OsHostService，FGS）+ Doze 兜底闹钟。
 *
 * 所以它不在 degradedReasons 里 —— 它缺失只是「UI 自动化不可用」，
 * 不构成「系统降级」。要不要开、什么时候开，由控制面板经权限服务决定。
 */
object ResidencyPolicy {

    const val FAST_TICK_MS = 15_000L

    /** 超过这个间隔没有心跳，判定进程被冻结/杀掉。 */
    const val FREEZE_GAP_MS = 5 * 60_000L

    const val OWNER_CHECK_TTL_MS = 30 * 60_000L

    /** Doze 兜底闹钟的间隔 —— 与前台服务一起构成保活的两条腿。 */
    const val WAKE_BACKSTOP_MS = 15 * 60_000L

    fun frozen(gapMs: Long): Boolean = gapMs > FREEZE_GAP_MS

    /**
     * 当前有哪些降级。
     *
     * @param programsRunning   进程账本里活着的程序数
     * @param installedPrograms 已安装且可启动的程序数
     */
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

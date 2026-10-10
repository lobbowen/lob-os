package lobos.services.perm

enum class GrantPolicy {
    ALWAYS_KEEP,
    ON_DEMAND,
    ADB_DRIVEN,
}

enum class AutoHeal {
    YES,
    NO,
}

data class PermissionRole(
    val id: String,
    val purpose: String,
    val policy: GrantPolicy,
    val autoHeal: AutoHeal,
    val owner: String,
)

object PermissionRoles {

    const val OWNER_KERNEL = "kernel"
    const val OWNER_AUTOMATION = "automation"
    const val OWNER_APP = "app"

    private val ROLES: List<PermissionRole> = listOf(
        PermissionRole(
            PermissionCatalog.ACCESSIBILITY, "后台保活锚：uid 被冻结时系统仍放行无障碍服务执行",
            GrantPolicy.ALWAYS_KEEP, AutoHeal.YES, OWNER_KERNEL,
        ),
        PermissionRole(
            PermissionCatalog.NOTIFICATION_ACCESS, "后台保活锚之二：通知监听同样常驻系统进程",
            GrantPolicy.ALWAYS_KEEP, AutoHeal.YES, OWNER_KERNEL,
        ),
        PermissionRole(
            PermissionCatalog.BATTERY_OPTIMIZATION, "保活：不受 Doze 限制才谈得上常驻",
            GrantPolicy.ALWAYS_KEEP, AutoHeal.YES, OWNER_KERNEL,
        ),
        PermissionRole(
            PermissionCatalog.POST_NOTIFICATIONS, "前台服务通知可见",
            GrantPolicy.ALWAYS_KEEP, AutoHeal.NO, OWNER_KERNEL,
        ),
        PermissionRole(
            PermissionCatalog.MEDIAPROJECTION, "屏幕捕获：每次会话授权，物理不可预置",
            GrantPolicy.ON_DEMAND, AutoHeal.NO, OWNER_AUTOMATION,
        ),
        PermissionRole(
            PermissionCatalog.SYSTEM_ALERT_WINDOW, "悬浮窗：自动化落点提示与调试视图",
            GrantPolicy.ON_DEMAND, AutoHeal.NO, OWNER_AUTOMATION,
        ),
        PermissionRole(
            PermissionCatalog.MANAGE_EXTERNAL_STORAGE, "访客程序访问宿主共享目录",
            GrantPolicy.ON_DEMAND, AutoHeal.NO, OWNER_APP,
        ),
        PermissionRole(
            PermissionCatalog.REQUEST_INSTALL_PACKAGES, "程序安装到宿主沙箱之外",
            GrantPolicy.ON_DEMAND, AutoHeal.NO, OWNER_APP,
        ),
    )

    fun of(id: String): PermissionRole? = ROLES.firstOrNull { it.id == id }
    fun declared(): List<PermissionRole> = ROLES.filter { PermissionCatalog.byId(it.id) != null }

    fun undeclared(): List<PermissionRole> = ROLES.filter { PermissionCatalog.byId(it.id) == null }
}
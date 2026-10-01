package lobos.permissions

import android.Manifest
import android.provider.Settings

enum class PermTier { NORMAL, RUNTIME, APPOP, SETTINGS, SERVICE_TOGGLE, DEVICE_ADMIN, ADB_ONLY }

data class PermissionSpec(
    val id: String,
    val label: String,
    val tier: PermTier,
    val permission: String? = null,
    val settingsAction: String? = null,
    val note: String = "",
    val appOpsOp: String? = null,
)

object PermissionCatalog {

    const val MANAGE_EXTERNAL_STORAGE = "manage-external-storage"
    const val NOTIFICATION_ACCESS = "notification-access"
    const val POST_NOTIFICATIONS = "post-notifications"
    const val REQUEST_INSTALL_PACKAGES = "request-install-packages"
    const val SYSTEM_ALERT_WINDOW = "system-alert-window"
    const val BATTERY_OPTIMIZATION = "battery-optimization"
    const val ACCESSIBILITY = "accessibility"
    const val MEDIAPROJECTION = "mediaprojection"

    const val SECURE_KEY_ACCESSIBILITY = "enabled_accessibility_services"
    const val SECURE_KEY_NOTIFICATION_LISTENER = "enabled_notification_listeners"

    const val SECURE_KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"

    val SPECIAL: List<PermissionSpec> = listOf(
        PermissionSpec(
            MANAGE_EXTERNAL_STORAGE, "MANAGE_EXTERNAL_STORAGE", PermTier.APPOP,
            settingsAction = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            appOpsOp = "MANAGE_EXTERNAL_STORAGE",
        ),
        PermissionSpec(
            NOTIFICATION_ACCESS, "通知访问", PermTier.SETTINGS,
            settingsAction = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
        ),
        PermissionSpec(
            POST_NOTIFICATIONS, "POST_NOTIFICATIONS", PermTier.RUNTIME,
            permission = Manifest.permission.POST_NOTIFICATIONS,
            note = "notif.post 会被系统静默丢弃",
        ),
        PermissionSpec(
            REQUEST_INSTALL_PACKAGES, "REQUEST_INSTALL_PACKAGES", PermTier.APPOP,
            settingsAction = Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            appOpsOp = "REQUEST_INSTALL_PACKAGES",
        ),
        PermissionSpec(
            SYSTEM_ALERT_WINDOW, "SYSTEM_ALERT_WINDOW", PermTier.APPOP,
            settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            appOpsOp = "SYSTEM_ALERT_WINDOW",
        ),
        PermissionSpec(
            ACCESSIBILITY, "无障碍服务", PermTier.SERVICE_TOGGLE,
            settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
            note = "判据看服务实例已连，设置串残留不作数",
        ),
        PermissionSpec(
            MEDIAPROJECTION, "屏幕捕获授权", PermTier.SETTINGS,
            note = "每次会话授权，物理不可预置",
        ),
    )

    val LIFECYCLE: List<PermissionSpec> = listOf(
        PermissionSpec(
            BATTERY_OPTIMIZATION, "电池优化豁免", PermTier.APPOP,
            settingsAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            note = "不豁免则 Doze 下更易被杀",
        ),
    )

    val ALL: List<PermissionSpec> = SPECIAL + LIFECYCLE

    fun byId(id: String): PermissionSpec? = ALL.firstOrNull { it.id == id }
}

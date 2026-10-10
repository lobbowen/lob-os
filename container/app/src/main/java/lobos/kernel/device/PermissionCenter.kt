package lobos.kernel.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import lobos.kernel.device.OsAccessibilityService

/**
 * 设备权限现状的实测口。
 *
 * 这里只做一件事：此刻这一项**到底是不是真的拿到了** —— 直接读 Settings /
 * AppOps / AccessibilityManager / DevicePolicyManager 的当前值，而不是查台账、
 * 不看历史、也不推断。无障碍看服务实例是否已连（设置串里的残留不作数），
 * 屏幕捕获看投影是否已就绪，通知监听看监听器是否已绑定。
 *
 * 内核不认识任何业务词汇：这里没有权限 id、没有角色、没有台账条目，
 * 只有一个个「这项机制此刻的状态」。谁把这些状态归到业务名下，由上层决定。
 */
class PermissionCenter(private val ctx: Context) {

    /** 全部文件访问（AppOps MANAGE_EXTERNAL_STORAGE） */
    fun externalStorageManager(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else false

    /** 无障碍服务实例是否已连上 —— 唯一可信的判据 */
    fun accessibilityReady(): Boolean =
        runCatching { lobos.kernel.device.OsAccessibilityService.isReady() }.getOrDefault(false)

    /** 通知监听器是否已绑定 */
    fun notificationListenerBound(): Boolean = NotificationStore.connected

    /** 屏幕投影是否已授权就绪（每次会话重新授权） */
    fun screenCaptureReady(): Boolean =
        runCatching { ScreenCaptureController.isReady() }.getOrDefault(false)

    /** 运行时权限的当前授予态 */
    fun runtimePermissionGranted(permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS：Tiramisu 以下恒为已授予 */
    fun postNotificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else true

    /** 是否可以请求安装宿主沙箱之外的包 */
    fun canRequestPackageInstalls(): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /** 是否可以绘制悬浮窗（SYSTEM_ALERT_WINDOW） */
    fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** 是否已豁免电池优化；不豁免则 Doze 下更易被杀 */
    fun batteryExempt(): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    // ---- 设置串读取：判定「设置里被勾上」这件事本身 ----

    fun accessibilityServicesValue(): String = secureString("enabled_accessibility_services")

    fun accessibilityEnabledInSettings(): Boolean =
        accessibilityServicesValue().split(":").any { it.contains(ctx.packageName) }

    fun notificationListenersValue(): String = secureString("enabled_notification_listeners")

    fun notificationListenerEnabled(): Boolean =
        notificationListenersValue().split(":").any { it.contains(ctx.packageName) }

    private fun secureString(key: String): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, key) ?: ""
    }.getOrDefault("")
}

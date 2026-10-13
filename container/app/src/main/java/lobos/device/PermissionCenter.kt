package lobos.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

class PermissionCenter(private val ctx: Context) {

    fun externalStorageManager(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else false

    fun accessibilityReady(): Boolean =
        runCatching { lobos.device.OsAccessibilityService.isReady() }.getOrDefault(false)

    fun notificationListenerBound(): Boolean = NotificationStore.connected

    fun screenCaptureReady(): Boolean =
        runCatching { ScreenCaptureController.isReady() }.getOrDefault(false)

    fun runtimePermissionGranted(permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun postNotificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissionGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else true

    fun canRequestPackageInstalls(): Boolean =
        runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    fun canDrawOverlays(): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    fun batteryExempt(): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

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

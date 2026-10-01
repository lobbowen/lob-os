package lobos.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import lobos.bridge.NotificationStore
import lobos.bridge.ScreenCaptureController
import lobos.lifecycle.OsAccessibilityService

class PermissionCenter(private val ctx: Context) {

    fun isGranted(spec: PermissionSpec): Boolean = when (spec.id) {
        PermissionCatalog.MANAGE_EXTERNAL_STORAGE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
            } else false

        PermissionCatalog.ACCESSIBILITY -> OsAccessibilityService.isReady()
        PermissionCatalog.NOTIFICATION_ACCESS -> notificationListenerBound()

        PermissionCatalog.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true

        PermissionCatalog.REQUEST_INSTALL_PACKAGES ->
            runCatching { ctx.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

        PermissionCatalog.SYSTEM_ALERT_WINDOW ->
            runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

        PermissionCatalog.BATTERY_OPTIMIZATION -> batteryExempt()

        PermissionCatalog.MEDIAPROJECTION -> ScreenCaptureController.isReady()

        else -> spec.permission?.let {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    fun accessibilityEnabledInSettings(): Boolean =
        accessibilityServicesValue().split(":").any { it.contains(ctx.packageName) }

    fun accessibilityServicesValue(): String = secureString(PermissionCatalog.SECURE_KEY_ACCESSIBILITY)

    fun notificationListenerEnabled(): Boolean =
        notificationListenersValue().split(":").any { it.contains(ctx.packageName) }

    fun notificationListenerBound(): Boolean = NotificationStore.connected

    fun notificationListenersValue(): String = secureString(PermissionCatalog.SECURE_KEY_NOTIFICATION_LISTENER)

    private fun secureString(key: String): String = runCatching {
        Settings.Secure.getString(ctx.contentResolver, key) ?: ""
    }.getOrDefault("")

    fun batteryExempt(): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

}

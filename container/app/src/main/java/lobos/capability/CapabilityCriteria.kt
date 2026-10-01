package lobos.capability

import android.content.Context
import android.provider.Settings
import lobos.bridge.OsNotificationListenerService
import lobos.lifecycle.OsAccessibilityService
import java.io.File

object CapabilityCriteria {

    fun credentialsState(ctx: Context): CredentialsState {
        val dir = File(ctx.filesDir, "adb")
        val paired = File(dir, "state.json").isFile && File(dir, "adbkey.pem").isFile
        return if (paired) CredentialsState.PAIRED else CredentialsState.NO_KEY
    }

    fun devOptionsOn(ctx: Context): Boolean =
        globalInt(ctx, "development_settings_enabled") == 1

    fun wirelessDebugOn(ctx: Context): Boolean = globalInt(ctx, "adb_wifi_enabled") == 1

    fun names(ctx: Context): DeviceNames = DeviceNames(
        packageName = ctx.packageName,
        accessibilityComponent = "${ctx.packageName}/${OsAccessibilityService::class.java.name}",
        notificationListenerComponent = "${ctx.packageName}/" +
            OsNotificationListenerService::class.java.name,
    )

    private fun globalInt(ctx: Context, name: String): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, name, 0)
    }.getOrDefault(0)
}

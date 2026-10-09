package lobos.capability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import lobos.os.StateFiles
import lobos.os.SystemDirs
import org.json.JSONObject

object OemGuards {

    const val CARD_LOCK = "oem-card-lock"
    const val FULL_BACKGROUND = "oem-full-background"
    const val FREEZE_WHITELIST = "oem-freeze-whitelist"
    const val STARTUP_MANAGER = "oem-startup-manager"

    val KEYS = listOf(CARD_LOCK, FULL_BACKGROUND, FREEZE_WHITELIST, STARTUP_MANAGER)

    private fun file(ctx: Context) = File(SystemDirs.libvar(ctx), "oem-guards.json")

    fun vendor(): String = (Build.MANUFACTURER + "/" + Build.BRAND).trim()

    fun confirmed(ctx: Context, key: String): Boolean = runCatching {
        val f = file(ctx)
        if (!f.exists()) false else JSONObject(f.readText()).optLong(key, 0L) > 0L
    }.getOrDefault(false)

    fun confirmedAll(ctx: Context): Set<String> = KEYS.filter { confirmed(ctx, it) }.toSet()

    @Synchronized
    fun confirm(ctx: Context, keys: List<String> = KEYS) {
        runCatching {
            val f = file(ctx)
            val o = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            keys.forEach { o.put(it, System.currentTimeMillis()) }
            f.parentFile?.mkdirs()
            lobos.os.StateFiles.writeAtomic(f, o.toString())
        }
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun appDetails(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", ctx.packageName, null))

    fun candidates(ctx: Context, key: String): List<Intent> = when (key) {
        STARTUP_MANAGER -> listOf(
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.oplus.safecenter", "com.oplus.safecenter.startup.StartupAppListActivity"),
            appDetails(ctx),
        )
        CARD_LOCK -> listOf(
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity"),
            componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.PermissionManagerActivity"),
            appDetails(ctx),
        )
        FULL_BACKGROUND -> listOf(
            componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
            componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            appDetails(ctx),
        )
        FREEZE_WHITELIST -> listOf(
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            appDetails(ctx),
        )
        else -> listOf(appDetails(ctx))
    }

    fun resolve(ctx: Context, key: String): Pair<Intent, String> {
        val list = candidates(ctx, key)
        for (i in list.indices) {
            val intent = list[i]
            val ok = runCatching { intent.resolveActivity(ctx.packageManager) != null }.getOrDefault(false)
            if (ok) return intent to if (i == list.size - 1) "应用详情页（厂商入口未命中）" else "厂商页命中（候选 #" + (i + 1) + "）"
        }
        return appDetails(ctx) to "应用详情页（无可用候选）"
    }
}

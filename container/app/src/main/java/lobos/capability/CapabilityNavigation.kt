package lobos.capability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import lobos.MainActivity
import lobos.permissions.PermTier
import lobos.permissions.PermissionCatalog

object CapabilityNavigation {

    fun intentFor(ctx: Context, acq: Acquisition): Intent? {
        if (acq.kind != AcquireKind.USER_TAP) return null
        return when (acq.target) {
            CapabilityCatalog.NAV_DEV_OPTIONS -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            CapabilityCatalog.NAV_DIAGNOSTICS -> Intent(ctx, MainActivity::class.java)
            CapabilityCatalog.NAV_OEM_STARTUP -> OemGuards.resolve(ctx, OemGuards.STARTUP_MANAGER).first
            CapabilityCatalog.NAV_OEM_CARD_LOCK -> OemGuards.resolve(ctx, OemGuards.CARD_LOCK).first
            CapabilityCatalog.NAV_OEM_FULL_BG -> OemGuards.resolve(ctx, OemGuards.FULL_BACKGROUND).first
            CapabilityCatalog.NAV_OEM_FREEZE -> OemGuards.resolve(ctx, OemGuards.FREEZE_WHITELIST).first
            else -> Intent(PermissionCatalog.byId(acq.target ?: "")?.settingsAction ?: return null)
        }
    }

    fun runtimePermission(acq: Acquisition): String? {
        if (acq.kind != AcquireKind.RUNTIME_DIALOG) return null
        val spec = PermissionCatalog.byId(acq.target ?: "") ?: return null
        return if (spec.tier == PermTier.RUNTIME) spec.permission else null
    }

    fun appDetailsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", ctx.packageName, null))

    fun wirelessDebugIntent(ctx: Context): Pair<Intent, String> {
        val deep = Intent(WIRELESS_DEBUG_SETTINGS_ACTION)
        val resolved = runCatching { deep.resolveActivity(ctx.packageManager) }.getOrNull()
        return if (resolved != null) {
            deep to "深链命中 $WIRELESS_DEBUG_SETTINGS_ACTION"
        } else {
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) to
                "深链无 Activity 响应（本机已知）→ 落开发者选项页"
        }
    }

    fun launch(ctx: Context, acq: Acquisition, deepLinkEmitted: (String) -> Unit = {}): Boolean {
        if (acq.kind == AcquireKind.USER_TAP && acq.target == CapabilityCatalog.NAV_WIRELESS_DEBUG) {
            val (intent, note) = wirelessDebugIntent(ctx)
            val ok = runCatching {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            deepLinkEmitted(note)
            return ok
        }
        val intent = intentFor(ctx, acq) ?: return false
        if (intent.component != null) return runCatching {
            ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            deepLinkEmitted("组件直投：${intent.component?.className}")
        }.isSuccess
        val described = intent.action ?: return false
        val withData = runCatching {
            ctx.startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(Uri.parse("package:${ctx.packageName}"))
            )
            deepLinkEmitted("带 package data：$described")
        }.isSuccess
        if (withData) return true
        return runCatching {
            ctx.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            deepLinkEmitted("去 data 重发：$described")
        }.isSuccess
    }

    const val WIRELESS_DEBUG_SETTINGS_ACTION = "android.settings.WIRELESS_DEBUGGING_SETTINGS"
}

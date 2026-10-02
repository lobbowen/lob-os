package lobos.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import lobos.RuntimeDiagnostics
import lobos.os.KillAudit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "BootReceiver: $action -> 拉起 OsHostService（唯一入口）")
            startQuietly(context, Intent(context, OsHostService::class.java), bootSafe = false)
            ensureProtectionActive(context)
        }
    }

    private fun ensureProtectionActive(context: Context) {
        val appCtx = context.applicationContext ?: context
        Thread({
            try {
                runCatching { KillAudit.auditOnce(appCtx) }
                runCatching {
                    AccessibilityAnchor.ensureBound(appCtx, AnchorPolicy.ACTIVATION_BUDGET_MS)
                }
            } catch (_: Throwable) {
            }
        }, "protection-active").start()
    }

    private fun startQuietly(context: Context, svc: Intent, bootSafe: Boolean) {
        try {
            if (bootSafe && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "拉起 ${svc.component?.shortClassName} 失败", e)
            RuntimeDiagnostics.append(
                context, "boot", false, "拉起 ${svc.component?.shortClassName} 失败",
                "${e::class.java.simpleName}: ${e.message}"
            )
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}

package lobos.services.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import lobos.services.log.RuntimeDiagnostics
import lobos.api.CapabilityBroker
import lobos.services.supply.PackageInstaller

class PackageInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val pkg = intent.getStringExtra(CapabilityBroker.EXTRA_PKG) ?: ""
        val target = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: pkg

        val ok = status == PackageInstaller.STATUS_SUCCESS
        val stage = if (intent.action == ACTION_UNINSTALLED) "pkg-uninstall" else "pkg-install"

        RuntimeDiagnostics.append(
            context, stage, ok,
            "${if (ok) "成功" else "失败"}：$target",
            "status=$status${if (message.isNotBlank()) ", message=$message" else ""}"
        )
        Log.i(TAG, "$stage target=$target status=$status msg=$message")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Throwable) {
                    Log.w(TAG, "无法拉起安装确认界面", e)
                }
            }
        }
    }

    companion object {
        const val TAG = "PackageInstallReceiver"
        const val ACTION_UNINSTALLED = "lobos.PKG_UNINSTALLED"
    }
}

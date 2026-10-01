package lobos.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import lobos.RuntimeDiagnostics
import lobos.os.DozeBackstop

class DozeBackstopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != DozeBackstop.ACTION) return
        lobos.os.ResidencyStatus.recordWake()
        RuntimeDiagnostics.append(
            context, "doze", null, "兜底投递：确保 OS 宿主在",
            "自唤醒间隔=" + lobos.lifecycle.ResidencyPolicy.WAKE_BACKSTOP_MS + "ms",
        )
        OsHostService.ensureRunning(context)
        DozeBackstop.schedule(context)
    }
}

package lobos.services.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DozeBackstopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != DozeBackstop.ACTION) return
        PowerHostHooks.noteWake(System.currentTimeMillis())
        PowerHostHooks.ensureHost(context)
        DozeBackstop.schedule(context)
    }
}
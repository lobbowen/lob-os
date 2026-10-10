package lobos.kernel.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 内核机制：接收兜底唤醒投递并重排下一轮。
 * 不 import 服务层：宿主拉起与存活记账交给 PowerHostHooks，由外部注入。
 */
class DozeBackstopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != DozeBackstop.ACTION) return
        PowerHostHooks.noteWake(System.currentTimeMillis())
        PowerHostHooks.ensureHost(context)
        DozeBackstop.schedule(context)
    }
}
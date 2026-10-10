package lobos.kernel.power

import android.content.Context
import android.net.wifi.WifiManager

object PowerLocks {

    fun wifi(ctx: Context, tag: String = "lobos:net"): AutoCloseable {
        val wm = runCatching {
            ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
        val lock = runCatching { wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag) }.getOrNull()
        runCatching { lock?.setReferenceCounted(false); lock?.acquire() }
        return AutoCloseable {
            runCatching { lock?.let { if (it.isHeld) it.release() } }
            Unit
        }
    }
}

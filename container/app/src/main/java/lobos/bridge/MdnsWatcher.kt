package lobos.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

class MdnsWatcher(private val context: Context) {

    interface Sink {
        fun onRecord(type: String, host: String?, port: Int, name: String, ageMs: Long)

        fun onLost(type: String, name: String)

        fun onLog(message: String)
    }

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val releaseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseRunnable = Runnable { releaseMulticast() }

    private val browses = mutableMapOf<String, NsdManager.DiscoveryListener>()

    fun start(type: String, browseStartedAt: Long, sink: Sink) {
        if (browses.containsKey(type)) return
        acquireMulticast()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                sink.onLog("browse 已启动 $type")
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                val s = service ?: return
                sink.onLog("发现记录 $type → ${s.serviceName}（解析中…）")
                resolve(s, type, browseStartedAt, sink)
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                sink.onLost(type, service?.serviceName ?: "")
                sink.onLog("记录消失 $type → ${service?.serviceName ?: "未知实例（整类作废）"}")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                sink.onLog("browse 已停止 $type")
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                sink.onLog("browse 启动失败 $type code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                sink.onLog("browse 停止失败 $type code=$errorCode")
            }
        }
        browses[type] = listener
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { sink.onLog("browse 注册异常 $type: ${it::class.java.simpleName}: ${it.message}") }
    }

    fun stop(type: String) {
        val l: NsdManager.DiscoveryListener? = browses.remove(type)
        if (l != null) runCatching { nsd.stopServiceDiscovery(l) }
        if (browses.isEmpty()) releaseMulticast()
    }

    fun stopAll() {
        browses.keys.toList().forEach { stop(it) }
        releaseMulticast()
    }

    @Suppress("DEPRECATION")
    private fun resolve(found: NsdServiceInfo, type: String, startedAt: Long, sink: Sink) {
        nsd.resolveService(found, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                sink.onLog("解析失败 ${info?.serviceName ?: found.serviceName} code=$errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo?) {
                if (info == null) return
                val host = runCatching { info.host?.hostAddress }.getOrNull()
                val age = if (startedAt > 0) System.currentTimeMillis() - startedAt else -1L
                sink.onRecord(type, host, info.port, info.serviceName, age)
            }
        })
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            wifi?.createMulticastLock("lobos-adb-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        releaseHandler.removeCallbacks(releaseRunnable)
        releaseHandler.postDelayed(releaseRunnable, MULTICAST_LOCK_TIMEOUT_MS)
    }

    private fun releaseMulticast() {
        releaseHandler.removeCallbacks(releaseRunnable)
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    companion object {
        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"

        const val MULTICAST_LOCK_TIMEOUT_MS = 60_000L
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
    }
}

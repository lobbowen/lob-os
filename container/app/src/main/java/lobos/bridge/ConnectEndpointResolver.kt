package lobos.bridge

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ConnectEndpointResolver {

    data class Endpoint(val host: String? = null, val port: Int? = null)

    private val lock = Any()
    private var watcher: MdnsWatcher? = null

    @Volatile
    private var cached: Endpoint? = null
    @Volatile private var cachedAtMs = 0L

    fun invalidateStale(nowMs: Long, ttlMs: Long) {
        if (cached == null) return
        if (cachedAtMs > 0L && nowMs - cachedAtMs < ttlMs) return
        synchronized(lock) {
            cached = null
            cachedAtMs = 0L
        }
        rebrowse()
    }

    private val waiters = CopyOnWriteArrayList<CountDownLatch>()

    private val sink = object : MdnsWatcher.Sink {
        override fun onRecord(type: String, host: String?, port: Int, name: String, ageMs: Long) {
            if (type != MdnsWatcher.TYPE_CONNECT) return
            if (host.isNullOrBlank() || port <= 0) return
            cached = Endpoint(host, port)
            cachedAtMs = System.currentTimeMillis()
            releaseWaiters()
        }

        override fun onLost(type: String, name: String) {
            if (type != MdnsWatcher.TYPE_CONNECT) return
            cached = null
            rebrowse()
        }

        override fun onLog(message: String) {}
    }

    private fun releaseWaiters() {
        val ws = waiters.toList()
        waiters.clear()
        for (w in ws) w.countDown()
    }

    private fun ensureStarted(context: Context) {
        synchronized(lock) {
            if (watcher == null) {
                watcher = MdnsWatcher(context.applicationContext).also {
                    it.start(MdnsWatcher.TYPE_CONNECT, System.currentTimeMillis(), sink)
                }
            }
        }
    }

    private fun rebrowse() {
        val w = synchronized(lock) { watcher } ?: return
        runCatching { w.stop(MdnsWatcher.TYPE_CONNECT) }
        w.start(MdnsWatcher.TYPE_CONNECT, System.currentTimeMillis(), sink)
    }

    fun resolve(context: Context, timeoutMs: Long = RESOLVE_TIMEOUT_MS): Endpoint? {
        ensureStarted(context)
        cached?.let { return it }
        val latch = CountDownLatch(1)
        waiters.add(latch)
        try {
            cached?.let { return it }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            return cached
        } finally {
            waiters.remove(latch)
        }
    }

    private const val RESOLVE_TIMEOUT_MS = 3_000L
}

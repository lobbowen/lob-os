package lobos.services.power

import android.content.Context

object PowerHostHooks {

    @Volatile
    private var ensurer: ((Context) -> Unit)? = null

    @Volatile
    private var wakeObserver: ((Long) -> Unit)? = null

    fun setEnsurer(block: (Context) -> Unit) {
        ensurer = block
    }

    fun setWakeObserver(block: (Long) -> Unit) {
        wakeObserver = block
    }

    fun clear() {
        ensurer = null
        wakeObserver = null
    }

    fun ensureHost(ctx: Context) {
        runCatching { ensurer?.invoke(ctx.applicationContext) }
    }

    fun noteWake(nowMs: Long) {
        runCatching { wakeObserver?.invoke(nowMs) }
    }
}
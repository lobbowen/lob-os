package lobos.kernel.power

import android.content.Context

/**
 * 内核只提供机制与形状：外部把自己的「宿主拉起」「存活记账」动作挂进来，
 * 内核不关心宿主是什么、也不关心宿主是否真的活着。
 */
object PowerHostHooks {

    @Volatile
    private var ensurer: ((Context) -> Unit)? = null

    @Volatile
    private var wakeObserver: ((Long) -> Unit)? = null

    /** 由服务层注册。 */
    fun setEnsurer(block: (Context) -> Unit) {
        ensurer = block
    }

    /** 由服务层注册：兜底唤醒投递时被告知一次。 */
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
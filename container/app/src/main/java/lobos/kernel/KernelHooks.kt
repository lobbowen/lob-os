package lobos.kernel

import android.content.Context

object KernelHooks {

    @Volatile
    private var ensurer: ((Context) -> Unit)? = null

    @Volatile
    private var reporter: ((Context, String, Boolean?, String, String?) -> Unit)? = null

    fun setEnsurer(block: (Context) -> Unit) {
        ensurer = block
    }

    fun setReporter(block: (Context, String, Boolean?, String, String?) -> Unit) {
        reporter = block
    }

    fun clear() {
        ensurer = null
        reporter = null
    }

    fun ensureHost(ctx: Context) {
        val r = ensurer ?: return
        runCatching { r(ctx.applicationContext) }
    }

    fun report(
        ctx: Context,
        stage: String,
        ok: Boolean?,
        message: String,
        detail: String? = null,
    ) {
        val r = reporter ?: return
        runCatching { r(ctx.applicationContext, stage, ok, message, detail) }
    }
}

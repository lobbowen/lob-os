package lobos.kernel

/**
 * Host lifecycle hooks. The host captures its own Context in the registered
 * callbacks — kernel code never passes Android Context here.
 */
object KernelHooks {

    @Volatile
    private var ensurer: (() -> Unit)? = null

    @Volatile
    private var reporter: ((String, Boolean?, String, String?) -> Unit)? = null

    fun setEnsurer(block: () -> Unit) { ensurer = block }

    fun setReporter(block: (String, Boolean?, String, String?) -> Unit) { reporter = block }

    fun clear() { ensurer = null; reporter = null }

    fun ensureHost() {
        runCatching { ensurer?.invoke() }
    }

    fun report(stage: String, ok: Boolean?, message: String, detail: String? = null) {
        runCatching { reporter?.invoke(stage, ok, message, detail) }
    }
}

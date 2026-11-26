package lobos.kernel

/**
 * Host lifecycle hook. The host captures its own Context in the registered
 * callback; kernel code never passes Android Context here.
 * Logging goes through KernelServices.logger() only (single channel).
 */
object KernelHooks {

    @Volatile
    private var ensurer: (() -> Unit)? = null

    fun setEnsurer(block: () -> Unit) { ensurer = block }

    fun clear() { ensurer = null }

    fun ensureHost() {
        runCatching { ensurer?.invoke() }
    }
}

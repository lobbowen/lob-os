package lobos.kernel.compat

object LobosEnv {

    private var ok = false

    private fun bind(): Boolean {
        if (ok) return true
        return try {
            System.loadLibrary("lobosposix")
            ok = true
        } catch (_: Throwable) {
            ok = false
        }
    }

    fun set(key: String, value: String): Boolean {
        if (!bind()) return false
        return runCatching { nativeSetenv(key, value) > 0 }.getOrDefault(false)
    }

    fun get(key: String): String? {
        if (!bind()) return null
        return runCatching { nativeGetenv(key) }.getOrNull()
    }

    private external fun nativeSetenv(key: String, value: String): Int

    private external fun nativeGetenv(key: String): String?
}

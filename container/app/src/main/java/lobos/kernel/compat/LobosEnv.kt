package lobos.kernel.compat

object LobosEnv {

    private var ok = false

    private fun bind(): Boolean {
        if (ok) return true
        ok = try {
            System.loadLibrary("lobosposix")
            true
        } catch (_: Throwable) {
            false
        }
        return ok
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

    fun spawnProbe(script: String): String =
        if (!bind()) "loadLibrary failed" else runCatching { nativeSpawnProbe(script) }.getOrDefault("threw")

    private external fun nativeSpawnProbe(script: String): String
}

package lobos.kernel.time

object Heartbeat {

    const val TTL_MS = 60_000L

    fun fresh(updatedAt: Long, nowMs: Long): Boolean =
        updatedAt > 0L && nowMs - updatedAt in 0 until TTL_MS
}

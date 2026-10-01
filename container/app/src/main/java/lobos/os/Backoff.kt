package lobos.os

object Backoff {
    fun exponential(attempt: Int, baseMs: Long, maxMs: Long, maxShift: Int = 5): Long =
        minOf(baseMs shl attempt.coerceIn(0, maxShift), maxMs)
}

package lobos.services.supervise

object Backoff {
    fun exponential(attempt: Int, baseMs: Long, maxMs: Long): Long {
        if (baseMs <= 0L) return maxMs.coerceAtLeast(1L)
        var v = baseMs
        val n = attempt.coerceAtLeast(0)
        for (i in 0 until n) {
            if (v >= maxMs) return maxMs
            v = if (v > Long.MAX_VALUE / 2) maxMs else v * 2
        }
        return minOf(v, maxMs)
    }
}

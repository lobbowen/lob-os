package lobos.ota

object ProgramOtaVersions {

    fun compare(a: String, b: String): Int {
        val ta = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val tb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until maxOf(ta.size, tb.size)) {
            val x = ta.getOrNull(i) ?: return -1
            val y = tb.getOrNull(i) ?: return 1
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> 1
                ny != null -> -1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, current: String?): Boolean =
        current == null || compare(candidate, current) > 0

    fun isBelowFloor(candidate: String, floor: String?): Boolean =
        floor != null && compare(candidate, floor) < 0
}

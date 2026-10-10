package lobos.services.supply

object VersionRange {

    fun satisfies(version: String, range: String): Boolean {
        val v = parse(version) ?: return false
        val tokens = range.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        for (t in tokens) {
            val op = when {
                t.startsWith(">=") -> ">="
                t.startsWith("<=") -> "<="
                t.startsWith(">") -> ">"
                t.startsWith("<") -> "<"
                t.startsWith("=") -> "="
                else -> "="
            }
            val raw = t.removePrefix(op).trim()
            val bound = parse(raw) ?: return false
            val cmp = compare(v, bound)
            val ok = when (op) {
                ">=" -> cmp >= 0
                "<=" -> cmp <= 0
                ">" -> cmp > 0
                "<" -> cmp < 0
                else -> cmp == 0
            }
            if (!ok) return false
        }
        return true
    }

    private fun parse(s: String): IntArray? {
        val clean = s.trim().removePrefix("v").substringBefore("-").substringBefore("+")
        if (clean.isBlank()) return null
        val parts = clean.split(".").map { it.toIntOrNull() ?: return null }
        return intArrayOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 },
        )
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in 0 until 3) {
            if (a[i] != b[i]) return if (a[i] > b[i]) 1 else -1
        }
        return 0
    }
}

package com.spizganed.mtty.term

/** One search hit: the cells [start]..[end] (both included) of [line]. A line is [TermCore.scrolledRows] plus the row. */
data class FindHit(val line: Long, val start: Int, val end: Int)

/**
 * The hits of [query] in the history and on the screen, oldest first, case-insensitive. A hit does not cross the end of a
 * row. A wide character is one character of the text and covers its 2 cells.
 */
fun TermCore.find(query: String): List<FindHit> {
    if (query.isEmpty()) return emptyList()
    val q = query.lowercase()
    val out = ArrayList<FindHit>()
    val sb = StringBuilder()
    var cells = IntArray(0)
    for (i in -historySize until rows) {
        val row = row(i)
        sb.setLength(0)
        if (cells.size < row.columns * 2) cells = IntArray(row.columns * 2)
        for (x in 0 until row.columns) {
            val cp = row.cp[x]
            if (cp == Row.WIDE_TAIL) continue
            val from = sb.length
            sb.appendCodePoint(if (cp <= 0) ' '.code else cp)
            for (k in from until sb.length) cells[k] = x
        }
        val text = sb.toString().lowercase()
        if (text.length != sb.length) continue // a lowercase form of another length (rare): the cells would not match
        var at = text.indexOf(q)
        while (at >= 0) {
            val last = at + q.length - 1
            val endCell = cells[last] + wideEnd(row, cells[last])
            out += FindHit(scrolledRows + i, cells[at], endCell)
            at = text.indexOf(q, at + maxOf(1, q.length))
        }
    }
    return out
}

/** The last cell of the character in [x]: [x] + 1 for a wide character, else [x]. */
private fun wideEnd(row: Row, x: Int) = if (x + 1 < row.columns && row.cp[x + 1] == Row.WIDE_TAIL) 1 else 0

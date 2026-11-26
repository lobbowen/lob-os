package com.spizganed.mtty.term

/**
 * One row of cells. A cell holds one code point ([cp]) and its style. 0 is an empty cell (it shows as a space).
 * A wide character uses 2 cells: the code point, then [WIDE_TAIL]. Combining marks and other code points of
 * the same grapheme go into [extra] for the cell.
 */
class Row(columns: Int) {
    val cp = IntArray(columns)
    val style = LongArray(columns)

    /** The code points after the first one, for each cell that has more. Null while no cell has more. */
    var extra: Array<String?>? = null
        private set

    /** The OSC 8 hyperlink id of each cell (see [TermCore.hyperlink]), 0 for none. Null while no cell has one. */
    var link: IntArray? = null
        private set

    /** The underline color of each cell (a [Color]), 0 for the text color. Null while no cell has one. */
    var underlineColor: IntArray? = null
        private set

    /** Shell integration marks (OSC 133) on this row: the MARK_* bits. */
    var marks = 0

    /** The exit code of the command that ended on this row (OSC 133 D), or null. */
    var exitCode: Int? = null

    /** True if the text continues on the next row (an automatic wrap, not a newline). */
    var wrapped = false

    /** Counts each change, so that a renderer can keep a drawn row while it does not change. */
    var version = 0L
        private set

    val columns: Int get() = cp.size

    fun touch() { version++ }

    fun set(x: Int, codePoint: Int, s: Long) {
        cp[x] = codePoint
        style[x] = s
        extra?.set(x, null)
        link?.set(x, 0)
        underlineColor?.set(x, 0)
        version++
    }

    /** Adds a combining code point to the cell. A cell keeps at most [MAX_EXTRA] UTF-16 chars of extras. */
    fun addExtra(x: Int, codePoint: Int) {
        val e = extra ?: arrayOfNulls<String>(cp.size).also { extra = it }
        val old = e[x] ?: ""
        if (old.length >= MAX_EXTRA) return
        e[x] = old + String(Character.toChars(codePoint))
        version++
    }

    fun extraAt(x: Int): String? = extra?.get(x)

    fun linkAt(x: Int): Int = link?.get(x) ?: 0

    fun underlineColorAt(x: Int): Int = underlineColor?.get(x) ?: 0

    /** Sets the hyperlink and the underline color of a cell. The arrays come only when a value is not 0. */
    fun setMeta(x: Int, linkId: Int, ulColor: Int) {
        if (linkId != 0 || link != null) (link ?: IntArray(cp.size).also { link = it })[x] = linkId
        if (ulColor != 0 || underlineColor != null) (underlineColor ?: IntArray(cp.size).also { underlineColor = it })[x] = ulColor
    }

    /** The number of cells up to the last one that is not empty, with no style (the app: a full row continues a link). */
    fun usedColumns(): Int {
        var n = cp.size
        while (n > 0 && cp[n - 1] == 0 && style[n - 1] == 0L) n--
        return n
    }

    /** The text of the cells from x until end (exclusive). Empty cells are spaces. */
    fun text(from: Int = 0, until: Int = cp.size, out: StringBuilder = StringBuilder()): StringBuilder {
        for (x in from until minOf(until, cp.size)) {
            val c = cp[x]
            when {
                c == WIDE_TAIL -> {}
                c == 0 -> out.append(' ')
                else -> {
                    out.appendCodePoint(c)
                    extra?.get(x)?.let { out.append(it) }
                }
            }
        }
        return out
    }

    override fun toString() = text().toString()

    companion object {
        /** The second cell of a wide character. */
        const val WIDE_TAIL = -1
        const val MAX_EXTRA = 32

        // OSC 133 marks: a prompt starts (A), the command input starts (B), the output starts (C), the command ended (D).
        const val MARK_PROMPT = 1
        const val MARK_INPUT = 2
        const val MARK_OUTPUT = 4
        const val MARK_END = 8
    }
}

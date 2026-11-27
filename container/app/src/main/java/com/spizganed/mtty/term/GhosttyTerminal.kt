package com.spizganed.mtty.term

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [TermCore] on libghostty-vt: the library parses the bytes and keeps the screen. This class copies the rows
 * that the view reads into [Row] objects for [TermView] and [Keys].
 * Rows are read when the view asks, not after each feed, so many feeds cost one read.
 */
class GhosttyTerminal(
    columns: Int,
    rows: Int,
    private val historyRows: Int = 2000,
    private val host: TerminalHost = object : TerminalHost {},
) : TermCore {
    init {
        check(GhosttyNative.available) { "libmtty-vt.so did not load" }
    }

    private var titleChanged = false

    private val callbacks = object : GhosttyNative.Callbacks {
        override fun write(data: ByteArray) = host.write(String(data, Charsets.UTF_8))
        override fun bell() = host.bell()
        override fun titleChanged() { titleChanged = true }
        override fun clipboard(data: ByteArray) = host.clipboard(String(data, Charsets.UTF_8))
    }

    // The library loads when `available` is first read. Without this check the first terminal fails with UnsatisfiedLinkError.
    private var handle = GhosttyNative.nCreate(columns, rows, maxOf(historyRows, 0).toLong(), callbacks).also {
        check(it != 0L) { "libghostty-vt did not start" }
        GhosttyNative.nSetDefaults(it, Palette.DEFAULTS)
    }

    override var columns = columns
        private set
    override var rows = rows
        private set
    override var cellWidth = 0
        set(v) { field = v; GhosttyNative.nSetCell(handle, v, cellHeight) }
    override var cellHeight = 0
        set(v) { field = v; GhosttyNative.nSetCell(handle, cellWidth, v) }
    private val colorsOf = Palette()
    override val palette: Palette get() = run { sync(); colorsOf }

    private val info = IntArray(12)
    private val colors = IntArray(Palette.SIZE)
    private var dirty = true
    private var screen = Array(rows) { Row(columns) }
    private var buffer = ByteBuffer.allocateDirect(bufferSize(columns, rows)).order(ByteOrder.nativeOrder())
    /** The buffer for one history row: one for all reads, made again after a resize. */
    private var rowBuffer = ByteBuffer.allocateDirect(bufferSize(columns, 1)).order(ByteOrder.nativeOrder())
    private val oldRows = object : LinkedHashMap<Long, Row>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Row>) = size > OLD_ROWS
    }

    private fun bufferSize(columns: Int, rows: Int) = rows * (4 + columns * CELL) + 4 + EXTRA_ROOM

    /** Refreshes the cursor, the modes, the colors and the screen rows when something changed since the last read. */
    private fun sync() {
        if (!dirty) return
        dirty = false
        GhosttyNative.nInfo(handle, info)
        syncColors()
        val used = GhosttyNative.nReadRows(handle, GhosttyNative.TAG_ACTIVE, 0, rows, buffer)
        if (used >= 0) for (y in 0 until rows) load(screen[y], y, rows, buffer)
    }

    private fun syncColors() {
        GhosttyNative.nPalette(handle, colors)
        var changed = false
        for (i in 0 until Palette.SIZE) {
            val want = if (colors[i] == -1) Palette.DEFAULTS[i] else colors[i]
            if (colorsOf.colors[i] != want) { colorsOf.colors[i] = want; changed = true }
        }
        if (changed) host.colorsChanged()
    }

    /** Copies row [index] of [count] rows in [buf] into [row]. Cells that did not change keep their data, and the row keeps its version. */
    private fun load(row: Row, index: Int, count: Int, buf: ByteBuffer) {
        val rowBytes = 4 + columns * CELL
        val base = index * rowBytes
        val wrapped = buf.getInt(base) and 1 != 0
        if (row.wrapped != wrapped) { row.wrapped = wrapped; row.touch() }
        val flags = buf.getInt(base)
        val marks = (flags ushr 4) and 15
        val exit = (flags ushr 16).let { if (it == 0) null else it - 1 }
        if (row.marks != marks || row.exitCode != exit) { row.marks = marks; row.exitCode = exit; row.touch() }
        // The extra code points of cells (combining marks) come after the rows.
        var extras: Array<String>? = null
        var at = count * rowBytes
        val extraCount = buf.getInt(at); at += 4
        if (extraCount > 0) {
            extras = Array(extraCount) {
                val n = buf.getInt(at); at += 4
                val sb = StringBuilder()
                repeat(n) { sb.appendCodePoint(buf.getInt(at)); at += 4 }
                sb.toString()
            }
        }
        for (x in 0 until columns) {
            val p = base + 4 + x * CELL
            val cp = buf.getInt(p)
            val extra = buf.getInt(p + 4).let { if (it > 0 && extras != null) extras[it - 1] else null }
            val style = buf.getLong(p + 8)
            val code = if (cp == -1) Row.WIDE_TAIL else cp
            val link = buf.getInt(p + 16)
            val ul = buf.getInt(p + 20)
            if (row.cp[x] == code && row.style[x] == style && row.extraAt(x) == extra && row.linkAt(x) == link && row.underlineColorAt(x) == ul) continue
            row.set(x, code, style)
            if (link != 0 || ul != 0) row.setMeta(x, link, ul)
            if (extra != null) for (c in extra.codePoints()) row.addExtra(x, c)
        }
    }

    override val isAlt get() = run { sync(); info[5] != 0 }
    override val cursorX get() = run { sync(); info[0] }
    override val cursorY get() = run { sync(); info[1] }
    override val cursorVisible get() = run { sync(); info[2] != 0 }
    override val cursorShape get() = run { sync(); info[3] }
    override val cursorBlink get() = run { sync(); info[4] != 0 }
    override val mouseMode get() = run { sync(); info[7] }
    /** Only the flags the key encoder does (see [Keys.KITTY_SUPPORTED]): libghostty keeps all 5, and a program that asks for more must not get what we cannot send. */
    override val keyboardFlags get() = run { sync(); info[8] and Keys.KITTY_SUPPORTED }
    private fun mode(bit: Int) = run { sync(); info[9] and bit != 0 }
    override val cursorKeysApp get() = mode(1)
    override val keypadApp get() = mode(2)
    override val bracketedPaste get() = mode(4)
    override val reverseVideo get() = mode(8)
    override val focusEvents get() = mode(16)
    override val syncOutput get() = mode(32)
    override val mouseSgr get() = mode(64)
    override val newlineMode get() = mode(1024)

    override val title: String? get() = GhosttyNative.nTitle(handle)
    override val workingDirectory: String? get() = GhosttyNative.nPwd(handle)
    override val scrolledRows get() = GhosttyNative.nScrolled(handle)
    /** The library prunes whole pages, so it can keep more rows than asked: the view sees only [historyRows]. */
    override val historySize get() = run { sync(); minOf(info[6], maxOf(historyRows, 0)) }

    override fun row(i: Int): Row {
        sync()
        if (i >= 0) return screen[i]
        // A history row. A line (scrolledRows plus the row) names it for good, so the cache holds it while the text stays.
        val line = scrolledRows + i
        return oldRows.getOrPut(line) {
            val row = Row(columns)
            val y = info[6] + i
            val one = rowBuffer
            if (y >= 0 && GhosttyNative.nReadRows(handle, GhosttyNative.TAG_HISTORY, y, 1, one) >= 0) load(row, 0, 1, one)
            row
        }
    }

    override fun hyperlink(id: Int): String? = if (id == 0) null else GhosttyNative.nLink(handle, id)

    override fun feed(data: ByteArray, offset: Int, length: Int) {
        GhosttyNative.nFeed(handle, data, offset, length)
        dirty = true
        if (titleChanged) { titleChanged = false; host.title(title) }
    }

    override fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) return
        GhosttyNative.nResize(handle, newColumns, newRows)
        columns = newColumns
        rows = newRows
        screen = Array(rows) { Row(columns) }
        buffer = ByteBuffer.allocateDirect(bufferSize(columns, rows)).order(ByteOrder.nativeOrder())
        rowBuffer = ByteBuffer.allocateDirect(bufferSize(columns, 1)).order(ByteOrder.nativeOrder())
        oldRows.clear()  // a reflow changes the history rows
        dirty = true
    }

    override fun text(startRow: Int, startCol: Int, endRow: Int, endCol: Int) = textBetween(startRow, startCol, endRow, endCol)

    override fun screenText(): String = (0 until rows).joinToString("\n") { row(it).toString().trimEnd() }

    override fun paste(text: String) = host.write(pasteText(text, bracketedPaste))

    /** Frees the native terminal. Do not use this object after that. */
    fun close() {
        if (handle != 0L) { GhosttyNative.nFree(handle); handle = 0 }
    }

    protected fun finalize() = close()

    companion object {
        private const val CELL = 24
        private const val EXTRA_ROOM = 16 * 1024
        private const val OLD_ROWS = 256

        /** True when the native library loads on this machine. */
        val available get() = GhosttyNative.available
    }
}

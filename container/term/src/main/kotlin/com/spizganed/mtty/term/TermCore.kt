package com.spizganed.mtty.term

/** What the terminal sends back to the app. All functions have empty defaults. */
interface TerminalHost {
    /** Bytes for the program: replies to queries. */
    fun write(text: String) {}
    fun title(title: String?) {}
    fun bell() {}

    /** OSC 52: the program puts text on the clipboard. */
    fun clipboard(text: String) {}

    /** OSC 4, 10, 11, 12, 104 or 110 to 112 changed the palette. */
    fun colorsChanged() {}
}

/**
 * What [TermView], [Keys] and the app need from a terminal core: the screen, the history, the cursor, the modes
 * and the way in for bytes. [GhosttyTerminal] is the one core: it runs on libghostty-vt.
 * Call all functions on one thread.
 */
interface TermCore {
    val columns: Int
    val rows: Int

    /** True while the alternate screen shows. It has no history. */
    val isAlt: Boolean
    val cursorX: Int
    val cursorY: Int
    val cursorVisible: Boolean

    /** 0 block, 1 underline, 2 bar. */
    val cursorShape: Int
    val cursorBlink: Boolean
    val reverseVideo: Boolean
    val syncOutput: Boolean
    val newlineMode: Boolean
    val cursorKeysApp: Boolean
    val keypadApp: Boolean
    val bracketedPaste: Boolean
    val focusEvents: Boolean

    /** 0 off, 1000 press and release, 1002 also motion with a button down, 1003 all motion. */
    val mouseMode: Int
    val mouseSgr: Boolean

    /** The Kitty keyboard protocol flags of the active screen. */
    val keyboardFlags: Int
    val title: String?
    val workingDirectory: String?
    val palette: Palette

    /** Set by the view: the size of a cell in pixels, for size reports. */
    var cellWidth: Int
    var cellHeight: Int

    /** How many rows have scrolled into the history so far. It only grows. A line is [scrolledRows] plus the row. */
    val scrolledRows: Long

    /** The rows in the history. */
    val historySize: Int

    /** A row of the screen (0 until [rows]) or of the history (-1 is the newest history row). */
    fun row(i: Int): Row
    fun hyperlink(id: Int): String?
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset)
    fun resize(newColumns: Int, newRows: Int)

    /** The text from (startRow, startCol) to (endRow, endCol), both ends included, with the rules of [textBetween]. */
    fun text(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String
    fun screenText(): String

    /** Sends text to the program as a paste: brackets it when the program asked for that. */
    fun paste(text: String)
}

/** The text of [TermCore.text]: a wrapped row joins the next one with no newline, trailing spaces are cut. */
internal fun TermCore.textBetween(startRow: Int, startCol: Int, endRow: Int, endCol: Int): String {
    val sb = StringBuilder()
    for (y in startRow..endRow) {
        val r = row(y)
        val from = if (y == startRow) startCol else 0
        val until = if (y == endRow) minOf(endCol + 1, r.columns) else r.columns
        val lineStart = sb.length
        r.text(from, until, sb)
        if (!r.wrapped || y == endRow) {
            var e = sb.length
            while (e > lineStart && sb[e - 1] == ' ') e--
            sb.setLength(e)
            if (y != endRow) sb.append('\n')
        }
    }
    return sb.toString()
}

/** The bytes of a paste: no escape or C1 control, line ends as CR, and the brackets when the program asked for them. */
internal fun pasteText(text: String, bracketed: Boolean): String {
    val clean = text.replace(Regex("[\u001b\u0080-\u009f]"), "").replace(Regex("\r?\n"), "\r")
    return if (bracketed) "\u001b[200~$clean\u001b[201~" else clean
}

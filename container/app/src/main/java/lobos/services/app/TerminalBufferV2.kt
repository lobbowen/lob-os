package lobos.services.app

import com.spizganed.mtty.term.GhosttyTerminal
import com.spizganed.mtty.term.Color
import com.spizganed.mtty.term.Style
import lobos.ui.terminal.StyledChar
import lobos.ui.terminal.TerminalLine

/**
 * Adapter from mtty-term's libghostty-backed terminal to LobOS's UI line model.
 * Replaces the hand-rolled 105-line TerminalBuffer (which dropped every CSI
 * except SGR) with a real VT emulator: cursor moves, alt screen, 256/truecolor,
 * wide chars, UTF-8 streaming, OSC — all handled by libghostty-vt.
 *
 * Keeps the same `feed(bytes)` + `snapshot` surface as TerminalBuffer so the
 * migration is contained. View: docs/spec/TERMINAL-RESEARCH-DECISION.md
 */
class TerminalBufferV2(
    columns: Int = 80,
    rows: Int = 24,
) {
    private val vt = GhosttyTerminal(columns, rows, 2000)

    fun feed(bytes: ByteArray) = vt.feed(bytes, 0, bytes.size)

    fun resize(cols: Int, rowCount: Int) = vt.resize(cols, rowCount)

    val cursorX: Int get() = vt.cursorX
    val cursorY: Int get() = vt.cursorY
    val isAlt: Boolean get() = vt.isAlt

    /** Screen rows rendered into the existing UI line model.
     * Trailing blank rows are dropped so the UI does not scroll past the
     * visible prompt into an empty screen. */
    val snapshot: List<TerminalLine>
        get() {
            val all = (0 until vt.rows).map { y -> renderRow(vt.row(y)) }
            val last = all.indexOfLast { it.text.trim().isNotEmpty() }
            return if (last < 0) listOf(all.last()) else all.subList(0, last + 1)
        }

    private fun renderRow(row: com.spizganed.mtty.term.Row): TerminalLine {
        val chars = ArrayList<StyledChar>(row.columns)
        for (x in 0 until row.columns) {
            val cp = row.cp[x]
            val style = row.style[x]
            val c = when {
                cp <= 0 -> ' '
                cp in 0xD800..0xDFFF -> ' ' // wide-char tail: no standalone glyph
                cp > 0xFFFF -> ' '          // surrogate pair would need 2 cells; render placeholder
                else -> Character.toChars(cp)[0]
            }
            val fg = Style.fg(style)
            val color = if (Color.isDefault(fg)) null else vt.palette.argb(fg, true)
            chars.add(StyledChar(c, color, style and Style.BOLD != 0L))
        }
        return TerminalLine(chars)
    }

    fun screenText(): String = vt.screenText()
}

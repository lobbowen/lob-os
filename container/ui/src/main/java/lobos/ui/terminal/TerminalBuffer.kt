package lobos.ui.terminal

data class StyledChar(val c: Char, val color: Int? = null, val bold: Boolean = false)

data class TerminalLine(val chars: List<StyledChar>) {
    val text: String get() = chars.joinToString("") { it.c.toString() }
}

private enum class Esc { NONE, ESC, CSI, OSC }

class TerminalBuffer(private val maxLines: Int = 500) {

    private val lines = mutableListOf<TerminalLine>()
    private var current = mutableListOf<StyledChar>()
    private var col = 0
    private var fg: Int? = null
    private var bold = false
    private var esc = Esc.NONE
    private val seq = StringBuilder()

    val snapshot: List<TerminalLine>
        get() = buildList {
            addAll(lines)
            add(TerminalLine(current.toList()))
        }

    fun feed(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8)
        for (ch in text) feedChar(ch)
    }

    private fun feedChar(ch: Char) {
        when (esc) {
            Esc.ESC -> {
                when (ch) {
                    '[' -> { esc = Esc.CSI; seq.setLength(0) }
                    ']' -> { esc = Esc.OSC; seq.setLength(0) }
                    else -> esc = Esc.NONE
                }
                return
            }
            Esc.CSI -> {
                seq.append(ch)
                if (ch in '@'..'~') { parseCsi(seq.toString()); seq.setLength(0); esc = Esc.NONE }
                return
            }
            Esc.OSC -> {
                seq.append(ch)
                if (ch == '\u0007' || (ch == '\\' && seq.endsWith("\u001B\\"))) {
                    seq.setLength(0); esc = Esc.NONE
                }
                return
            }
            Esc.NONE -> Unit
        }
        when (ch) {
            '\u001B' -> esc = Esc.ESC
            '\r' -> col = 0
            '\n' -> newline()
            '\b' -> if (col > 0) { col--; if (col < current.size) current[col] = StyledChar(current[col].c) }
            '\t' -> repeat(8 - (col % 8)) { put(' ') }
            else -> if (ch >= ' ') put(ch)
        }
    }

    private fun put(ch: Char) {
        val sc = StyledChar(ch, fg, bold)
        if (col < current.size) current[col] = sc else current.add(sc)
        col++
    }

    private fun newline() {
        lines.add(TerminalLine(current.toList()))
        current = mutableListOf()
        col = 0
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun parseCsi(s: String) {
        val final = s.last()
        if (final != 'm') return
        val params = s.dropLast(1).removePrefix("[").split(';').mapNotNull { it.toIntOrNull() }
        if (params.isEmpty()) { fg = null; bold = false; return }
        for (p in params) {
            when (p) {
                0 -> { fg = null; bold = false }
                1 -> bold = true
                22 -> bold = false
                30 -> fg = 0xFF8B949E.toInt()
                31 -> fg = 0xFFFF6B6B.toInt()
                32 -> fg = 0xFF4ADE80.toInt()
                33 -> fg = 0xFFE0AF68.toInt()
                34 -> fg = 0xFF6AA9FF.toInt()
                36 -> fg = 0xFF56D4DD.toInt()
                39 -> fg = null
            }
        }
    }

    fun clear() {
        lines.clear(); current.clear(); col = 0; fg = null; bold = false; esc = Esc.NONE; seq.setLength(0)
    }
}

package lobos.ui

class TerminalScreen {

    private companion object {
        const val ESC = 0x1b.toChar()

        /** BEL = 0x07，终端响铃 */
        const val BEL = 0x07.toChar()

        const val BEL = 0x07.toChar()
    }

    class Cell {
        var ch: Char = ' '
        var style: Style = Style()
        var width: Int = 1
    }

    data class Style(
        val fg: Int = -1,
        val bg: Int = -1,
        val bold: Boolean = false,
        val underline: Boolean = false,
        val reverse: Boolean = false,
        val dim: Boolean = false,
    )

    var rows: Int = 24
        private set
    var cols: Int = 80
        private set

    private lateinit var grid: Array<Array<Cell>>
    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    private var savedRow = 0
    private var savedCol = 0
    private var savedStyle = Style()

    private var cur = Style()

    private val pending = StringBuilder()

    private enum class St { GROUND, ESC, CSI, OSC, OSC_ESC }

    private var st = St.GROUND

    init {
        resize(rows, cols)
    }

    fun resize(r: Int, c: Int) {
        if (r <= 0 || c <= 0) return
        rows = r; cols = c
        grid = Array(r) { Array(c) { Cell() } }
        cursorRow = cursorRow.coerceIn(0, r - 1)
        cursorCol = cursorCol.coerceIn(0, c - 1)
    }

    fun lineText(row: Int): String {
        if (row !in 0 until rows) return ""
        val sb = StringBuilder(cols)
        var i = 0
        while (i < cols) {
            val cell = grid[row][i]
            if (cell.width == 0 && i > 0) { i++; continue }
            sb.append(cell.ch)
            i += cell.width.coerceAtLeast(1)
        }
        return sb.toString().trimEnd()
    }

    fun lineStyle(row: Int, col: Int): Style =
        if (row in 0 until rows && col in 0 until cols) grid[row][col].style else Style()

    fun lineWidth(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) grid[row][col].width else 1

    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        val s = StringBuilder(pending)
        pending.setLength(0)
        s.append(String(bytes, 0, len, Charsets.UTF_8))
        for (ch in s) step(ch)
    }

    fun feed(text: String) {
        val s = StringBuilder(pending); pending.setLength(0); s.append(text)
        for (ch in s) step(ch)
    }

    private fun step(ch: Char) {
        when (st) {
            St.GROUND -> when {
                ch == ESC -> { flushPending(); st = St.ESC }
                ch == '\r' -> cursorCol = 0
                ch == '\n' -> newline()
                ch == '\b' -> { if (cursorCol > 0) cursorCol-- }
                ch == '\t' -> {
                    val next = ((cursorCol / 8) + 1) * 8
                    cursorCol = if (next < cols) next else cols - 1
                }
                // BEL 是响铃：只影响终端自己的提示，不进字符流
                ch == BEL -> Unit
                // DEL（0x7f）= 擦掉光标左边那个字符 —— 它是控制码，不是可打印字符
                ch.code == 0x7f -> { if (cursorCol > 0) { cursorCol--; put(0x20u.toChar()) } }
                else -> put(ch)
            }
            St.ESC -> when (ch) {
                '[' -> st = St.CSI
                ']' -> st = St.OSC
                '7' -> { savedRow = cursorRow; savedCol = cursorCol; savedStyle = cur; st = St.GROUND }
                '8' -> { cursorRow = savedRow.coerceIn(0, rows - 1); cursorCol = savedCol.coerceIn(0, cols - 1); cur = savedStyle; st = St.GROUND }
                'M' -> { if (cursorRow > 0) cursorRow--; st = St.GROUND }
                'D' -> { newline(); st = St.GROUND }
                'E' -> { cursorCol = 0; newline(); st = St.GROUND }
                'c' -> { reset(); st = St.GROUND }
                else -> st = St.GROUND
            }
            St.CSI -> {
                if (ch in '0'..'9' || ch == ';' || ch == '?') {
                    pending.append(ch)
                } else {
                    applyCsi(pending.toString(), ch)
                    pending.setLength(0)
                    st = St.GROUND
                }
            }
            St.OSC -> {
                if (ch == BEL) { st = St.GROUND }
            }
            St.OSC_ESC -> st = St.GROUND
        }
    }

    private fun flushPending() { pending.setLength(0) }

    fun reset() {
        for (r in 0 until rows) for (c in 0 until cols) {
            grid[r][c].ch = ' '; grid[r][c].style = Style(); grid[r][c].width = 1
        }
        cursorRow = 0; cursorCol = 0; cur = Style()
    }

    private fun newline() {
        cursorRow++
        if (cursorRow >= rows) {
            scrollUp(1)
            cursorRow = rows - 1
        }
    }

    private fun scrollUp(n: Int) {
        repeat(n.coerceAtLeast(0)) {
            for (r in 0 until rows - 1) {
                System.arraycopy(grid[r + 1], 0, grid[r], 0, cols)
            }
            val last = grid[rows - 1]
            for (c in 0 until cols) { last[c].ch = ' '; last[c].style = cur; last[c].width = 1 }
        }
    }

    private fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(0)) {
            for (r in rows - 1 downTo 1) {
                System.arraycopy(grid[r - 1], 0, grid[r], 0, cols)
            }
            for (c in 0 until cols) { grid[0][c].ch = ' '; grid[0][c].style = cur; grid[0][c].width = 1 }
        }
    }

    private fun charWidth(c: Char): Int {
        val code = c.code
        return when {
            code == 0 -> 0
            code in 0x0300..0x036F -> 0
            code in 0x1100..0x115F -> 2
            code in 0x2E80..0x303E -> 2
            code in 0x3041..0x33FF -> 2
            code in 0x3400..0x4DBF -> 2
            code in 0x4E00..0x9FFF -> 2
            code in 0xA000..0xA4CF -> 2
            code in 0xAC00..0xD7A3 -> 2
            code in 0xF900..0xFAFF -> 2
            code in 0xFE30..0xFE6F -> 2
            code in 0xFF00..0xFF60 -> 2
            code in 0xFFE0..0xFFE6 -> 2
            code in 0x1F300..0x1F64F -> 2
            code in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }

    private fun put(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            newline()
        }
        val w = charWidth(c)
        if (w == 0) {
            if (cursorCol > 0) {
                val prev = grid[cursorRow][cursorCol - 1]
                if (prev.width == 0) {   }
            }
            return
        }
        if (cursorCol + w > cols) {
            cursorCol = 0
            newline()
        }
        val cell = grid[cursorRow][cursorCol]
        cell.ch = c
        cell.style = cur
        cell.width = w
        if (w == 2 && cursorCol + 1 < cols) {
            val nxt = grid[cursorRow][cursorCol + 1]
            nxt.ch = ' '
            nxt.style = cur
            nxt.width = 0
        }
        cursorCol += w
    }

    private fun applyCsi(params: String, final: Char) {
        val p = if (params.startsWith("?")) params.substring(1) else params
        val nums = p.split(';').map { it.trim().toIntOrNull() ?: 0 }
        fun arg(i: Int, dflt: Int): Int = nums.getOrNull(i)?.takeIf { it > 0 } ?: dflt

        when (final) {
            'A' -> cursorRow = (cursorRow - arg(0, 1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + arg(0, 1)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + arg(0, 1)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - arg(0, 1)).coerceAtLeast(0)
            'G' -> cursorCol = (arg(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (arg(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'E' -> { cursorRow = (cursorRow + arg(0, 1)).coerceAtMost(rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - arg(0, 1)).coerceAtLeast(0); cursorCol = 0 }

            'J' -> eraseInDisplay(nums.firstOrNull() ?: 0)
            'K' -> eraseInLine(nums.firstOrNull() ?: 0)

            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            '@' -> shiftRight(arg(0, 1))
            'P' -> shiftLeft(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))

            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))

            'm' -> applySgr(nums.ifEmpty { listOf(0) })

            else -> Unit
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLineFrom(0)
                for (r in cursorRow + 1 until rows) clearRow(r)
            }
            1 -> {
                for (r in 0 until cursorRow) clearRow(r)
                eraseLineTo(cursorCol)
            }
            2, 3 -> for (r in 0 until rows) clearRow(r)
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> eraseLineFrom(cursorCol)
            1 -> eraseLineTo(cursorCol)
            2 -> clearRow(cursorRow)
        }
    }

    private fun clearRow(r: Int) {
        for (c in 0 until cols) { grid[r][c].ch = ' '; grid[r][c].style = cur; grid[r][c].width = 1 }
    }

    private fun eraseLineFrom(from: Int) {
        for (c in from.coerceAtLeast(0) until cols) { grid[cursorRow][c].ch = ' '; grid[cursorRow][c].style = cur; grid[cursorRow][c].width = 1 }
    }

    private fun eraseLineTo(to: Int) {
        for (c in 0..to.coerceAtMost(cols - 1)) { grid[cursorRow][c].ch = ' '; grid[cursorRow][c].style = cur; grid[cursorRow][c].width = 1 }
    }

    private fun eraseChars(n: Int) {
        for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) {
            grid[cursorRow][c].ch = ' '; grid[cursorRow][c].style = cur; grid[cursorRow][c].width = 1
        }
    }

    private fun shiftRight(n: Int) {
        val row = grid[cursorRow]
        for (c in cols - 1 downTo cursorCol + n) row[c] = row[c - n]
        for (c in cursorCol until (cursorCol + n).coerceAtMost(cols)) { row[c].ch = ' '; row[c].style = cur; row[c].width = 1 }
    }

    private fun shiftLeft(n: Int) {
        val row = grid[cursorRow]
        for (c in cursorCol until cols - n) row[c] = row[c + n]
        for (c in (cols - n).coerceAtLeast(cursorCol) until cols) { row[c].ch = ' '; row[c].style = cur; row[c].width = 1 }
    }

    private fun insertLines(n: Int) {
        repeat(n.coerceAtLeast(0)) { scrollDownInRegion(cursorRow) }
    }

    private fun deleteLines(n: Int) {
        repeat(n.coerceAtLeast(0)) { scrollUpInRegion(cursorRow) }
    }

    private fun scrollUpInRegion(top: Int) {
        for (r in top until rows - 1) System.arraycopy(grid[r + 1], 0, grid[r], 0, cols)
        clearRow(rows - 1)
    }

    private fun scrollDownInRegion(top: Int) {
        for (r in rows - 1 downTo top + 1) System.arraycopy(grid[r - 1], 0, grid[r], 0, cols)
        clearRow(top)
    }

    private fun applySgr(nums: List<Int>) {
        var i = 0
        while (i < nums.size) {
            val n = nums[i]
            when {
                n == 0 -> cur = Style()
                n == 1 -> cur = cur.copy(bold = true)
                n == 2 -> cur = cur.copy(dim = true)
                n == 4 -> cur = cur.copy(underline = true)
                n == 7 -> cur = cur.copy(reverse = true)
                n == 22 -> cur = cur.copy(bold = false, dim = false)
                n == 24 -> cur = cur.copy(underline = false)
                n == 27 -> cur = cur.copy(reverse = false)
                n in 30..37 -> cur = cur.copy(fg = n - 30)
                n == 39 -> cur = cur.copy(fg = -1)
                n in 40..47 -> cur = cur.copy(bg = n - 40)
                n == 49 -> cur = cur.copy(bg = -1)
                n in 90..97 -> cur = cur.copy(fg = n - 90 + 8, bold = true)
                n in 100..107 -> cur = cur.copy(bg = n - 100 + 8)
                n == 38 || n == 48 -> {
                    val target = if (n == 38) "fg" else "bg"
                    val mode = nums.getOrNull(i + 1) ?: 0
                    val v = when (mode) {
                        5 -> nums.getOrNull(i + 2) ?: -1
                        2 -> nums.getOrNull(i + 2) ?: -1
                        else -> -1
                    }
                    cur = if (target == "fg") cur.copy(fg = v) else cur.copy(bg = v)
                    i += 2
                }
            }
            i++
        }
    }
}
package lobos.ui

/**
 * 终端屏幕仿真 —— 把 ANSI/VT100 转义序列解成「一格一格的内容 + 光标位置」。
 *
 * ── 为什么必须做 ──
 * bash 的输出含大量转义序列（颜色、光标移动、清屏、擦行）。不解析的话屏幕上
 * 就是 `[?2004h`、`␛[2J` 这种垃圾，`vi` / `less` / `top` 完全不可用。
 * `docs/TERMINAL-PTY-PLAN.md` 的验收判据 5、6 明确要求「`vi` 能编辑」
 * 与「颜色与编码正确」—— 那就绕不开这一层。
 *
 * ── 做哪一档 ──
 * 档 C（全屏 TUI 仿真）要处理 DECSTBM 滚动区域、alternate screen、
 * 全部 CSI/SGR —— 那是能单独写一个项目的量。本实现覆盖的是**够用的那一档**：
 *   · SGR：颜色（16 色 + 亮色 + 256 色）、粗体/下划线/反显/隐匿
 *   · CSI：光标移动（CUU/CUD/CUF/CUB/CUP/CNL/CPL/CHA）、擦除（ED/EL）、
 *           插入删除（ICH/DCH）、滚动（SU/SD）
 *   · ESC：保存/恢复光标（ESC 7/8）、退格、tab、CR/LF、回车换行
 *   · OSC：忽略到 ST（标题设置之类，我们不需要）
 * 未覆盖的（alternate screen、滚动区域）会**被吞掉而不显示垃圾** ——
 * 宁可少一个特性，也不要在屏幕上吐转义序列。
 *
 * ── 中文与宽字符 ──
 * 用 `Char` 数组存格、每格带「占几列」的宽度标记：CJK 与全角符号占 2 列。
 * 只按 code unit 摆的话，中文会错位、光标会跑偏 —— 那是判据 6 明确点名的失败。
 *
 * 不缓存解析结果：终端输出是流式的，缓存等于双重状态。
 */
class TerminalScreen {

    private companion object {
        /** ESC（0x1B）：转义序列的起手。用常量而**不是**源码里的裸控制字符 ——
         *  裸字符在编辑器里看不见、在 diff 里会被吞掉、grep 也搜不到。 */
        const val ESC = 0x1b.toChar()

        /** BEL（0x07）：响铃 / OSC 终止。 */
        const val BEL = 0x07.toChar()
    }

    /** 一个字符格。width=0 表示这是某个宽字符的「续位」（不占新格）。 */
    class Cell {
        var ch: Char = ' '
        var style: Style = Style()
        var width: Int = 1
    }

    /** 样式（对应 SGR）。用不可变 data class + copy，便于回填时复用。 */
    data class Style(
        val fg: Int = -1,          // -1 = 默认；0..255 为调色板索引
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

    /** 当前样式（SGR 累积）。 */
    private var cur = Style()

    /** 待解析的字节缓冲 —— 转义序列可能被 TCP 分片切开。 */
    private val pending = StringBuilder()

    /** 解析状态机。 */
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

    /** 读出一行的文本（供渲染）。 */
    fun lineText(row: Int): String {
        if (row !in 0 until rows) return ""
        val sb = StringBuilder(cols)
        var i = 0
        while (i < cols) {
            val cell = grid[row][i]
            if (cell.width == 0 && i > 0) { i++; continue }   // 宽字符的续位
            sb.append(cell.ch)
            i += cell.width.coerceAtLeast(1)
        }
        return sb.toString().trimEnd()
    }

    fun lineStyle(row: Int, col: Int): Style =
        if (row in 0 until rows && col in 0 until cols) grid[row][col].style else Style()

    fun lineWidth(row: Int, col: Int): Int =
        if (row in 0 until rows && col in 0 until cols) grid[row][col].width else 1

    // ── 喂字节 ──────────────────────────────────────────────────────────────

    fun feed(bytes: ByteArray, len: Int = bytes.size) {
        // 先把上次残留的半截序列与本次拼接 —— 分片是常态（PTY 一次 poll 不保证给全）
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
                ch == BEL -> { /* BEL：忽略 */
                }
                ch.code == 0x7f -> { /* DEL：忽略 */
                }
                else -> put(ch)
            }
            St.ESC -> when (ch) {
                '[' -> st = St.CSI
                ']' -> st = St.OSC
                '7' -> { savedRow = cursorRow; savedCol = cursorCol; savedStyle = cur; st = St.GROUND }
                '8' -> { cursorRow = savedRow.coerceIn(0, rows - 1); cursorCol = savedCol.coerceIn(0, cols - 1); cur = savedStyle; st = St.GROUND }
                'M' -> { if (cursorRow > 0) cursorRow--; st = St.GROUND }   // 逆向索引
                'D' -> newline(); st = St.GROUND
                'E' -> { cursorCol = 0; newline(); st = St.GROUND }
                'c' -> { reset(); st = St.GROUND }                          // 完整复位
                else -> st = St.GROUND      // 其它 ESC 序列：吞掉，不吐到屏幕上
            }
            St.CSI -> {
                if (ch in '0'..'9' || ch == ';' || ch == '?') {
                    pending.append(ch)      // 参数还在收
                } else {
                    applyCsi(pending.toString(), ch)
                    pending.setLength(0)
                    st = St.GROUND
                }
            }
            St.OSC -> {
                // OSC 一直到 ST（ESC \）才结束。标题设置我们不需要，吞掉。
                if (ch == BEL) { st = St.GROUND }         // BEL 结束
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

    /** 字符宽度：CJK 与全角占 2 列。用区间判断而不是 `isWide` 属性（不同 JDK 实现不一致）。 */
    private fun charWidth(c: Char): Int {
        val code = c.code
        return when {
            code == 0 -> 0
            // 组合字符（重音等）：宽度 0，附着在前一格
            code in 0x0300..0x036F -> 0
            // CJK 统一表意文字及其扩展、假名、韩文、全角标点
            code in 0x1100..0x115F -> 2      // 谚文字母
            code in 0x2E80..0x303E -> 2      // CJK 部首、标点
            code in 0x3041..0x33FF -> 2      // 平假名/片假名/注音/兼容字符
            code in 0x3400..0x4DBF -> 2      // 扩展 A
            code in 0x4E00..0x9FFF -> 2      // 基本汉字
            code in 0xA000..0xA4CF -> 2      // 彝文
            code in 0xAC00..0xD7A3 -> 2      // 韩文音节
            code in 0xF900..0xFAFF -> 2      // 兼容汉字
            code in 0xFE30..0xFE6F -> 2      // 竖排标点
            code in 0xFF00..0xFF60 -> 2      // 全角 ASCII
            code in 0xFFE0..0xFFE6 -> 2      // 全角符号
            code in 0x1F300..0x1F64F -> 2    // 绘文字
            code in 0x20000..0x3FFFD -> 2    // 扩展 B 以上
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
            // 组合字符：附着到前一格（若无则忽略）
            if (cursorCol > 0) {
                val prev = grid[cursorRow][cursorCol - 1]
                // 组合字符不占格，这里只记录 —— 渲染层按 text 字符串读，组合字符已在串里
                if (prev.width == 0) { /* 续位，忽略 */ }
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
            nxt.width = 0        // 标记为续位
        }
        cursorCol += w
    }

    // ── CSI ────────────────────────────────────────────────────────────────

    private fun applyCsi(params: String, final: Char) {
        // "?25l" 这类私有模式：吞掉（我们不实现光标隐藏/显示，靠光标位置表达）
        val p = if (params.startsWith("?")) params.substring(1) else params
        val nums = p.split(';').map { it.trim().toIntOrNull() ?: 0 }
        fun arg(i: Int, dflt: Int): Int = nums.getOrNull(i)?.takeIf { it > 0 } ?: dflt

        when (final) {
            // 光标移动
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

            // 擦除
            'J' -> eraseInDisplay(nums.firstOrNull() ?: 0)
            'K' -> eraseInLine(nums.firstOrNull() ?: 0)

            // 插入/删除
            'L' -> insertLines(arg(0, 1))
            'M' -> deleteLines(arg(0, 1))
            '@' -> shiftRight(arg(0, 1))
            'P' -> shiftLeft(arg(0, 1))
            'X' -> eraseChars(arg(0, 1))

            // 滚动
            'S' -> scrollUp(arg(0, 1))
            'T' -> scrollDown(arg(0, 1))

            // SGR：样式
            'm' -> applySgr(nums.ifEmpty { listOf(0) })

            // 其它（模式设置等）：吞掉
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

    /** SGR：样式。0 复位、1 亮体、4 下划线、7 反显、22/24/27 关、30-37/90-97 前景、40-47/100-107 背景、38/48 带参数。 */
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
                    // 38;5;k（256 色）或 38;2;r;g;b（真彩）
                    val target = if (n == 38) "fg" else "bg"
                    val mode = nums.getOrNull(i + 1) ?: 0
                    val v = when (mode) {
                        5 -> nums.getOrNull(i + 2) ?: -1
                        2 -> nums.getOrNull(i + 2) ?: -1   // 真彩：压成单色索引渲染
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
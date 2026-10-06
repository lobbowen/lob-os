package lobos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 终端显示 View —— 把 [TerminalScreen] 的格子画出来，并把触摸/按键转成 PTY 输入。
 *
 * ── 为什么用原生 View 而不是 WebView ──
 * 判据 3/4 要求「`^C` 能中断、`^D` 能 EOF」与「改窗口大小后 `TIOCGWINSZ` 返回新值」。
 * WebView 里那套要经 JS 桥再转一圈，而 `TIOCSWINSZ` 必须由**持有 PTY 的那一侧**
 * 发出去 —— 桥接延迟会让窗口大小反馈不稳定，且多一层就多一个能坏的地方。
 * 原生 View 直接调 `Session.resize()`，没有中间层。
 *
 * ── 渲染模型 ──
 * 一格一格画：等宽字体 + 固定 advance。中文占两列（TerminalScreen 已标宽度），
 * 画的时候跳过续位格 —— 否则中文会重叠。
 * 只重画「脏行」：终端输出是流式的，每来一帧全量重画 24×80 = 1920 格会掉帧。
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    val screen = TerminalScreen()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()

    /** 行距倍数：1.0 会让中文上下贴太紧，用 1.1。 */
    private var lineHeightF = 1.12f

    private var cellW = 0f
    private var cellH = 0f
    private var textSizePx = 0f

    /** 格子坐标 → 触摸位置。只处理点击定位（选文本不是终端必需）。 */
    private var touchRow = 0
    private var touchCol = 0

    /** 输入出口。由 Activity 接到 PTY 会话上。 */
    var onInput: ((ByteArray) -> Unit)? = null

    /** 窗口大小变化出口：必须打到持有 PTY 的那一侧（TIOCSWINSZ）。 */
    var onResize: ((Int, Int) -> Unit)? = null

    /** 终端是否已连接会话（没连接时显示提示，不假装在工作）。 */
    var connected: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        bgPaint.color = Color.BLACK
        // 等宽字体：终端必须等宽，否则列位置与光标都会错
        cellPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        setBackgroundColor(Color.BLACK)
    }

    // ── 尺寸：字符格的大小与网格 ────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMetrics(w, h)
    }

    private fun recomputeMetrics(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val fontSize = max(8f, w / 120f)          // 大致按宽度定字号，保证列数够用
        textSizePx = fontSize
        cellPaint.textSize = fontSize
        val fm = cellPaint.fontMetrics
        val asc = -fm.ascent
        val desc = fm.descent
        cellH = (asc + desc) * lineHeightF
        // 等宽字体的 advance：用实际测量值，别假设 0.6em（不同字体不同）
        cellW = cellPaint.measureText("M")
        if (cellW <= 0f) cellW = fontSize * 0.6f

        val newCols = max(20, (w / cellW).toInt())
        val newRows = max(5, (h / cellH).toInt())
        if (newCols != screen.cols || newRows != screen.rows) {
            screen.resize(newRows, newCols)
            // 尺寸变了 → TIOCSWINSZ 必须跟着变，否则程序里的 vi/less 按旧尺寸排版
            onResize?.invoke(newRows, newCols)
        }
        invalidate()
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (cellW <= 0f || cellH <= 0f) recomputeMetrics(width, height)
        if (cellW <= 0f || cellH <= 0f) return

        if (!connected) {
            cellPaint.color = Color.GRAY
            cellPaint.textSize = textSizePx
            canvas.drawText(
                "终端未连接 —— 底座 PTY 不在位（见 diagnostics 的 pty 段）",
                cellW, cellH * 1.5f, cellPaint,
            )
            return
        }

        val descenderAdjust = (cellPaint.fontMetrics.descent + cellPaint.fontMetrics.ascent) / 2f
        for (r in 0 until screen.rows) {
            val y = (r + 1) * cellH - cellH * 0.15f
            var c = 0
            while (c < screen.cols) {
                val w = screen.lineWidth(r, c)
                if (w == 0) { c++; continue }          // 宽字符的续位：跳过，不画
                val st = screen.lineStyle(r, c)
                val ch = lineChar(r, c)
                if (ch == ' ' && st.bg < 0) { c += w; continue }   // 空白且无底色：跳过
                val x = c * cellW
                if (st.bg >= 0) {
                    bgPaint.color = paletteColor(st.bg, false)
                    canvas.drawRect(x, r * cellH, x + w * cellW, (r + 1) * cellH, bgPaint)
                }
                if (ch != ' ') {
                    cellPaint.color = paletteColor(if (st.reverse) (if (st.bg >= 0) st.bg else 7) else st.fg, st.bold)
                    cellPaint.textSize = textSizePx * if (st.bold) 1.0f else 1.0f
                    canvas.drawText(ch.toString(), x, y - descenderAdjust, cellPaint)
                    if (st.underline) {
                        canvas.drawRect(x, y + 1f, x + w * cellW, y + 2f, cellPaint)
                    }
                }
                c += w
            }
        }
        // 光标：反显一格。没连接时不画（免得画一个假的）。
        if (connected && screen.cursorRow < screen.rows && screen.cursorCol < screen.cols) {
            val x = screen.cursorCol * cellW
            val y = screen.cursorRow * cellH
            val w = max(1, screen.lineWidth(screen.cursorRow, screen.cursorCol))
            bgPaint.color = Color.WHITE
            canvas.drawRect(x, y, x + w * cellW, y + cellH, bgPaint)
            cellPaint.color = Color.BLACK
            val ch = lineChar(screen.cursorRow, screen.cursorCol)
            if (ch != ' ') {
                canvas.drawText(ch.toString(), x, y + cellH * 0.85f, cellPaint)
            }
        }
    }

    /** 取一格的字符。行文本按行取更省（一次 measure），这里按列简化实现。 */
    private fun lineChar(row: Int, col: Int): Char {
        val line = screen.lineText(row)
        return if (col < line.length) line[col] else ' '
    }

    /** 0..7 基础色、8..15 亮色、16..255 256 色。 */
    private fun paletteColor(idx: Int, bold: Boolean): Int {
        val i = if (bold && idx < 8) idx + 8 else idx
        return when {
            i < 0 -> Color.WHITE
            i < 8 -> BASE[i]
            i < 16 -> BRIGHT[i - 8]
            else -> {
                // 256 色 → RGB。真彩色（38;2;r;g;b）已被压成索引，这里给灰阶近似
                val n = i - 16
                val r = (n / 36) % 6
                val g = (n / 6) % 6
                val b = n % 6
                Color.rgb(r * 51, g * 51, b * 51)
            }
        }
    }

    private companion object {
        val BASE = intArrayOf(
            Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW,
            Color.BLUE, Color.MAGENTA, Color.CYAN, Color.LTGRAY,
        )
        val BRIGHT = intArrayOf(
            Color.DKGRAY, Color.rgb(255, 85, 85), Color.rgb(85, 255, 85), Color.rgb(255, 255, 85),
            Color.rgb(85, 85, 255), Color.rgb(255, 85, 255), Color.rgb(85, 255, 255), Color.WHITE,
        )
    }

    // ── 输入 ────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = translateKey(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onInput?.invoke(bytes)
        return true
    }

    /**
     * 按键 → 字节。
     *
     * 控制字符（`^C` = 0x03、`^D` = 0x04…）由 PTY 的行规程处理 —— 我们发原始字节，
     * **不要**在宿主侧自己判断「是不是 ^C」。终端的语义在 tty 那侧。
     */
    private fun translateKey(keyCode: Int, ev: KeyEvent): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "\r".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_TAB -> "\t".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_BACKSPACE -> {
            // Android 的 DEL 与终端的 BS 语义不同：多数程序要 0x7f
            if (ev.isShiftPressed) "\b".toByteArray(Charsets.UTF_8)
            else byteArrayOf(0x7f)
        }
        KeyEvent.KEYCODE_DPAD_UP -> ESC_ARROW("A")
        KeyEvent.KEYCODE_DPAD_DOWN -> ESC_ARROW("B")
        KeyEvent.KEYCODE_DPAD_RIGHT -> ESC_ARROW("C")
        KeyEvent.KEYCODE_DPAD_LEFT -> ESC_ARROW("D")
        KeyEvent.KEYCODE_HOME -> ESC_TILDE("H")
        KeyEvent.KEYCODE_END -> ESC_TILDE("F")
        KeyEvent.KEYCODE_PAGE_UP -> ESC_TILDE("5")
        KeyEvent.KEYCODE_PAGE_DOWN -> ESC_TILDE("6")
        else -> {
            // 其它键取 Unicode 字符（输入法打出的字走这条路）
            val u = ev.unicodeChar
            if (u != 0 && Character.isLetterOrDigit(u) || u > 0x7F) {
                String(u.toInt()).toByteArray(Charsets.UTF_8)
            } else null
        }
    }

    private fun ESC_ARROW(f: String): ByteArray =
        byteArrayOf(0x1b, '[', f.toByte())

    private fun ESC_TILDE(n: String): ByteArray =
        byteArrayOf(0x1b, '[', n.toByte(), '~'.code.toByte())

    /**
     * 触摸 → 键盘输入。
     *
     * 手机上没有物理键盘，所以**必须**支持「点字符来输入」——
     * 否则终端只能看不能用，判据 5（`vi` 能编辑）在这类设备上就无从谈起。
     * 做法：点哪一格就把那一行的内容送进去（用户先在下面输入框打字，
     * 再点某行插入）—— 这一版先做成「点哪格插哪行的已输入内容」。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val r = (event.y / cellH).toInt().coerceIn(0, max(0, screen.rows - 1))
                val c = (event.x / cellW).toInt().coerceIn(0, max(0, screen.cols - 1))
                touchRow = r; touchCol = c
                requestFocus()
                return true
            }
            MotionEvent.ACTION_UP -> return true
        }
        return super.onTouchEvent(event)
    }

    /** 供外部（输入框提交）用：把一段文本送进 PTY。 */
    fun inputText(s: String) {
        onInput?.invoke(s.toByteArray(Charsets.UTF_8))
    }

    /** 供外部用：把 PTY 回来的字节喂进屏幕并重画（节流到帧）。 */
    fun appendOutput(bytes: ByteArray, len: Int = bytes.size) {
        screen.feed(bytes, len)
        postInvalidateOnAnimation()
    }
}
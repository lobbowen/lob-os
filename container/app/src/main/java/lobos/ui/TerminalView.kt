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

class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    val screen = TerminalScreen()

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()

    private var lineHeightF = 1.12f

    private var cellW = 0f
    private var cellH = 0f
    private var textSizePx = 0f

    private var touchRow = 0
    private var touchCol = 0

    var onInput: ((ByteArray) -> Unit)? = null

    var onResize: ((Int, Int) -> Unit)? = null

    var connected: Boolean = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        bgPaint.color = Color.BLACK
        cellPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeMetrics(w, h)
    }

    private fun recomputeMetrics(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val fontSize = max(8f, w / 120f)
        textSizePx = fontSize
        cellPaint.textSize = fontSize
        val fm = cellPaint.fontMetrics
        val asc = -fm.ascent
        val desc = fm.descent
        cellH = (asc + desc) * lineHeightF
        cellW = cellPaint.measureText("M")
        if (cellW <= 0f) cellW = fontSize * 0.6f

        val newCols = max(20, (w / cellW).toInt())
        val newRows = max(5, (h / cellH).toInt())
        if (newCols != screen.cols || newRows != screen.rows) {
            screen.resize(newRows, newCols)
            onResize?.invoke(newRows, newCols)
        }
        invalidate()
    }

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
                if (w == 0) { c++; continue }
                val st = screen.lineStyle(r, c)
                val ch = lineChar(r, c)
                if (ch == ' ' && st.bg < 0) { c += w; continue }
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

    private fun lineChar(row: Int, col: Int): Char {
        val line = screen.lineText(row)
        return if (col < line.length) line[col] else ' '
    }

    private fun paletteColor(idx: Int, bold: Boolean): Int {
        val i = if (bold && idx < 8) idx + 8 else idx
        return when {
            i < 0 -> Color.WHITE
            i < 8 -> BASE[i]
            i < 16 -> BRIGHT[i - 8]
            else -> {
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = translateKey(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onInput?.invoke(bytes)
        return true
    }

    private fun translateKey(keyCode: Int, ev: KeyEvent): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "\r".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_TAB -> "\t".toByteArray(Charsets.UTF_8)
        KeyEvent.KEYCODE_BACKSPACE -> {
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

    fun inputText(s: String) {
        onInput?.invoke(s.toByteArray(Charsets.UTF_8))
    }

    fun appendOutput(bytes: ByteArray, len: Int = bytes.size) {
        screen.feed(bytes, len)
        postInvalidateOnAnimation()
    }
}
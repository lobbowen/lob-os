package com.spizganed.mtty.term

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a [TermCore] and turns touch, keys and the soft keyboard into input for it.
 * The app side (sizes, links, the clipboard, the keyboard) goes through the [Client].
 */
@SuppressLint("ViewConstructor")
class TermView(context: Context, private val client: Client) : View(context) {

    interface Client {
        /** The view has a new size in cells. */
        fun onResize(columns: Int, rows: Int)

        /** Bytes for the program. */
        fun write(text: String)

        /** A tap on the cell (row as in [TermCore.row]). */
        fun onTap(column: Int, row: Int)

        /** A long press on the cell. Return true if the client used it; else the view selects the word. */
        fun onLongPress(column: Int, row: Int): Boolean

        /** The text size in pixels that a pinch of [scale] gives, inside the limits of the client. */
        fun zoomSize(scale: Float): Int

        /** The pinch ended: the client sets the text size to [px]. */
        fun zoomTo(px: Int)

        /** Ctrl, Alt and Shift from the app's key row, as [Keys] modifier bits. */
        fun latchedModifiers(): Int

        /** A key was typed: the key row can release its latches. */
        fun onTyped()

        fun copy(text: String)
        fun paste()

        /** The label of the menu action that copies the selection as one line ([oneLine]). */
        fun copyOneLineLabel(): String

        /** The selection ended (copy, cancel, a tap). The client can feed the output that waited. */
        fun onSelectionEnd()

        /** False for a read-only window (a log): the menu has no Paste. */
        fun canPaste(): Boolean = true
    }

    var terminal: TermCore? = null
        set(t) {
            field = t
            scrollRows = 0
            clearSelection()
            lastScrolled = t?.scrolledRows ?: 0
            invalidateAll()
        }

    /** The accent of the app as ARGB, or 0. It colors the cursor while the program keeps the default cursor color. */
    var cursorColor = 0

    /** The size in cells: 0 until the view has a size. */
    var columns = 0
        private set
    var rows = 0
        private set

    /** The color of links and the selection handles, from the app theme. The selection is the same color, see-through. */
    var linkColor = 0xFF00A0FF.toInt()
        set(c) { field = c; invalidateAll() }
    // A white wash: text in the accent colour (links, a path, the colours of fish) stays readable inside a selection.
    private val selectionColor get() = SELECTION_COLOR

    // ---- Font and cells ----

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var faces = arrayOf(Typeface.MONOSPACE, Typeface.MONOSPACE, Typeface.MONOSPACE, Typeface.MONOSPACE)

    var cellWidth = 1f
        private set
    var cellHeight = 1
        private set
    private var baseline = 0f

    /** Sets the text size in pixels. The cell size and the column and row counts follow. */
    fun setTextSize(px: Int) {
        paint.textSize = px.toFloat()
        measureCells()
    }

    /** Sets the faces of a font that has its own bold. Italic is a slant of each face. */
    fun setFaces(regular: Typeface, bold: Typeface) {
        faces = arrayOf(regular, bold, Typeface.create(regular, Typeface.ITALIC), Typeface.create(bold, Typeface.ITALIC))
        measureCells()
    }

    fun setTypeface(base: Typeface) {
        faces = arrayOf(
            Typeface.create(base, Typeface.NORMAL), Typeface.create(base, Typeface.BOLD),
            Typeface.create(base, Typeface.ITALIC), Typeface.create(base, Typeface.BOLD_ITALIC),
        )
        measureCells()
    }

    private fun measureCells() {
        paint.typeface = faces[0]
        cellWidth = paint.measureText("M")
        val fm = paint.fontMetricsInt
        cellHeight = maxOf(1, fm.descent - fm.ascent)
        baseline = -fm.ascent.toFloat()
        line.strokeWidth = maxOf(1f, cellHeight / 16f)
        hasGlyph.clear() // another font has other glyphs
        updateGrid()
        invalidateAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGrid()

    private fun updateGrid() {
        if (width == 0 || height == 0) return
        val c = maxOf(2, (width / cellWidth).toInt())
        val r = maxOf(1, height / cellHeight)
        if (c == columns && r == rows) return
        columns = c
        rows = r
        freeze()
        client.onResize(c, r)
    }

    // ---- No flash on a resize ----

    /**
     * After a resize the program redraws the screen (tmux clears it first). Until that output stops, the view
     * keeps the last frame, so the user does not see a half-drawn screen. At most FREEZE_MS.
     */
    private var frozen = false
    private var frozenAt = 0L
    private var frozenCellHeight = 0
    private val unfreeze = Runnable { frozen = false; invalidate() }

    private fun freeze() {
        if (!frozen) { frozenAt = System.currentTimeMillis(); frozenCellHeight = drawnCellHeight }
        frozen = true
        removeCallbacks(unfreeze)
        postDelayed(unfreeze, FREEZE_MS)
    }

    /** Output came while frozen: wait until it is quiet for QUIET_MS, but not past FREEZE_MS in all. */
    private fun outputWhileFrozen() {
        removeCallbacks(unfreeze)
        val left = FREEZE_MS - (System.currentTimeMillis() - frozenAt)
        if (left <= 0) unfreeze.run() else postDelayed(unfreeze, minOf(QUIET_MS, left))
    }

    // ---- Output ----

    /** How many rows the view is scrolled back into the history. 0 shows the screen. */
    private var scrollRows = 0
    private var lastScrolled = 0L
    private val syncTimeout = Runnable { invalidate() }

    // ---- Cursor blink ----

    /** True: the cursor blinks also when the program asks for a steady one (fish and tmux do), so it is easy to see. */
    var alwaysBlink = true

    /** False while the blinking cursor is in its off phase. */
    private var blinkOn = true
    private val blink = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    /** Shows the cursor and starts the blink again: after input and output, the cursor stays on for a moment. */
    private fun restartBlink() {
        removeCallbacks(blink)
        if (!blinkOn) { blinkOn = true; invalidate() }
        val t = terminal ?: return
        // Reduced motion (animations off in the system settings): no blink.
        val animations = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if ((t.cursorBlink || alwaysBlink) && t.cursorVisible && hasFocus() && isAttachedToWindow && animations) postDelayed(blink, BLINK_MS)
    }

    /** Back from the background: draw every row again (the cached drawings may be gone). */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidateAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blink)
    }

    /** Call after new output. Keeps a scrolled-back view on the same text, and waits for synchronized output. */
    fun onScreenUpdated() {
        restartBlink()
        if (frozen) outputWhileFrozen()
        val t = terminal ?: return
        val added = t.scrolledRows - lastScrolled
        lastScrolled = t.scrolledRows
        if (scrollRows > 0 && added > 0) scrollRows = minOf(scrollRows + added.toInt(), t.historySize)
        if (t.isAlt) scrollRows = 0
        // DECSET 2026: the program draws a whole frame first. Draw at the end, or after at most SYNC_MS.
        removeCallbacks(syncTimeout)
        if (t.syncOutput) postDelayed(syncTimeout, SYNC_MS) else invalidate()
    }

    // ---- Drawing ----

    /** One cached drawing for each row slot of the view (Android 10 and up). */
    private var nodes = arrayOfNulls<RenderNode>(0)
    private var nodeKeys = arrayOfNulls<Any>(0)
    private var nodeVersions = LongArray(0)
    private var drawVersion = 0L

    /** Drops all cached rows: after a change of font, colors or size. */
    fun invalidateAll() {
        drawVersion++
        invalidate()
    }

    /** The cell height of the last full frame: a frozen frame draws its rows at that height. */
    private var drawnCellHeight = 0

    override fun onDraw(canvas: Canvas) {
        val t = terminal ?: return
        val start = System.nanoTime()
        val top = -scrollRows
        val useNodes = Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated
        if (frozen && useNodes && nodes.isNotEmpty() && nodes.all { it == null || it.hasDisplayList() }) {
            // The last frame as it was: the cached rows, not recorded again.
            for (y in nodes.indices) {
                val node = nodes[y] ?: continue
                canvas.save()
                canvas.translate(0f, (y * frozenCellHeight).toFloat())
                canvas.drawRenderNode(node)
                canvas.restore()
            }
            return
        }
        drawnCellHeight = cellHeight
        if (useNodes && nodes.size != rows) {
            nodes = arrayOfNulls(rows); nodeKeys = arrayOfNulls(rows); nodeVersions = LongArray(rows)
        }
        for (y in 0 until rows) {
            val i = top + y
            if (i >= t.rows) break
            val row = t.row(i)
            val py = y * cellHeight
            if (useNodes) {
                // A row redraws only when its content or the drawing settings change.
                val version = row.version * 31 + drawVersion
                var node = nodes[y]
                // Android frees the drawings of a hidden app: a node with no display list draws again.
                if (node == null || nodeKeys[y] !== row || nodeVersions[y] != version || !node.hasDisplayList()) {
                    node = node ?: RenderNode("row$y")
                    node.setPosition(0, 0, width, cellHeight)
                    val c = node.beginRecording()
                    drawRow(c, t, row, 0f)
                    node.endRecording()
                    nodes[y] = node; nodeKeys[y] = row; nodeVersions[y] = version
                }
                canvas.save()
                canvas.translate(0f, py.toFloat())
                canvas.drawRenderNode(node)
                canvas.restore()
            } else {
                drawRow(canvas, t, row, py.toFloat())
            }
        }
        drawHits(canvas, t)
        drawSelection(canvas, t)
        drawCursor(canvas, t)
        if (TermPerf.enabled) TermPerf.render(System.nanoTime() - start)
    }

    private fun inverted(t: TermCore, style: Long) = (style and Style.INVERSE != 0L) != t.reverseVideo

    /** The text color of a style. */
    private fun fgOf(t: TermCore, style: Long): Int {
        val inv = inverted(t, style)
        val bg = t.palette.argb(if (inv) Style.fg(style) else Style.bg(style), inv)
        if (style and Style.INVISIBLE != 0L) return bg
        val fg = t.palette.argb(if (inv) Style.bg(style) else Style.fg(style), !inv)
        return if (style and Style.DIM != 0L) blend(fg, bg) else fg
    }

    /** The background color of a style, or 0 for the default: the app ground shows through. */
    private fun bgOf(t: TermCore, style: Long): Int {
        val inv = inverted(t, style)
        if (!inv && Color.isDefault(Style.bg(style))) return 0
        return t.palette.argb(if (inv) Style.fg(style) else Style.bg(style), inv)
    }

    private fun blend(a: Int, b: Int): Int {
        fun ch(s: Int) = (((a shr s) and 255) + ((b shr s) and 255)) / 2
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val glyphs = Glyphs()
    private val hasGlyph = HashMap<Int, Boolean>()
    private val fontHas: (Int) -> Boolean = { cp -> hasGlyph.getOrPut(cp) { paint.typeface = faces[0]; paint.hasGlyph(String(Character.toChars(cp))) } }

    private var chars = CharArray(512)
    private var linkCells = BooleanArray(512)
    private val curly = Path()

    private fun drawRow(c: Canvas, t: TermCore, row: Row, py: Float) {
        val n = minOf(row.columns, columns)
        if (n > chars.size) { chars = CharArray(n); linkCells = BooleanArray(n) }
        findLinks(row, n)
        // Backgrounds: one rect for each run of the same color.
        var x = 0
        while (x < n) {
            val bg = bgOf(t, row.style[x])
            var e = x + 1
            while (e < n && (row.style[e] == row.style[x] || bgOf(t, row.style[e]) == bg)) e++
            if (bg != 0) {
                fill.color = bg
                c.drawRect(x * cellWidth, py, e * cellWidth, py + cellHeight, fill)
            }
            x = e
        }
        // Text: runs of ASCII with one style go out in one call. Other chars go one by one, fitted to their cells.
        x = 0
        while (x < n) {
            val cp = row.cp[x]
            val style = row.style[x]
            val ul = row.underlineColorAt(x)
            if (cp <= 0 || cp == ' '.code) {
                decorate(c, t, style, x, x + 1, py, linkCells[x], ul)
                x++
                continue
            }
            val link = linkCells[x]
            setStyle(t, style, link)
            if (cp < 0x80 && row.extraAt(x) == null) {
                var e = x
                var k = 0
                while (e < n && row.style[e] == style && row.cp[e] in 0x21..0x7E && row.extraAt(e) == null && linkCells[e] == link &&
                    row.underlineColorAt(e) == ul
                ) {
                    chars[k++] = row.cp[e].toChar(); e++
                }
                c.drawText(chars, 0, k, x * cellWidth, py + baseline, paint)
                decorate(c, t, style, x, e, py, link, ul)
                x = e
            } else if (glyphs.handles(cp, fontHas)) {
                val w = if (x + 1 < n && row.cp[x + 1] == Row.WIDE_TAIL) 2 else 1
                glyphs.draw(c, cp, x * cellWidth, py, w * cellWidth, cellHeight.toFloat(), line.strokeWidth, paint.color)
                decorate(c, t, style, x, x + w, py, link, ul)
                x += w
            } else {
                val w = if (x + 1 < n && row.cp[x + 1] == Row.WIDE_TAIL) 2 else 1
                val text = String(Character.toChars(cp)) + (row.extraAt(x) ?: "")
                val target = w * cellWidth
                val measured = paint.measureText(text)
                c.save()
                var left = x * cellWidth
                if (measured > target) {
                    c.scale(target / measured, 1f, left, 0f)
                } else {
                    left += (target - measured) / 2
                }
                c.drawText(text, left, py + baseline, paint)
                c.restore()
                decorate(c, t, style, x, x + w, py, link, ul)
                x += w
            }
        }
    }

    private fun setStyle(t: TermCore, style: Long, link: Boolean) {
        val bold = style and Style.BOLD != 0L
        val italic = style and Style.ITALIC != 0L
        paint.typeface = faces[(if (bold) 1 else 0) + (if (italic) 2 else 0)]
        paint.color = if (link && Color.isDefault(Style.fg(style))) linkColor else fgOf(t, style)
    }

    /** Underline (all styles), strikethrough and overline for the cells x until end. */
    private fun decorate(c: Canvas, t: TermCore, style: Long, x: Int, end: Int, py: Float, link: Boolean, ul: Int) {
        val kind = if (link) maxOf(Style.underline(style), Style.UNDERLINE_SINGLE) else Style.underline(style)
        val strike = style and Style.STRIKE != 0L
        val over = style and Style.OVERLINE != 0L
        if (kind == 0 && !strike && !over) return
        line.color = when {
            link && Color.isDefault(Style.fg(style)) -> linkColor
            else -> fgOf(t, style)
        }
        val lineColor = line.color
        // SGR 58 colors only the underline.
        if (ul != 0 && kind != 0) line.color = t.palette.argb(ul, true)
        line.pathEffect = null
        val l = x * cellWidth
        val r = end * cellWidth
        val sw = line.strokeWidth
        val under = py + cellHeight - sw * 1.5f
        when (kind) {
            Style.UNDERLINE_SINGLE -> c.drawLine(l, under, r, under, line)
            Style.UNDERLINE_DOUBLE -> { c.drawLine(l, under, r, under, line); c.drawLine(l, under - 2 * sw, r, under - 2 * sw, line) }
            Style.UNDERLINE_CURLY -> {
                val path = curly.apply { reset() }
                val amp = sw * 1.2f
                val step = cellWidth / 2
                path.moveTo(l, under)
                var px = l
                var up = true
                while (px < r) {
                    path.quadTo(px + step / 2, under + if (up) -amp * 2 else amp * 2, px + step, under)
                    px += step; up = !up
                }
                c.drawPath(path, line)
            }
            Style.UNDERLINE_DOTTED -> { line.pathEffect = DashPathEffect(floatArrayOf(sw, sw * 2), 0f); c.drawLine(l, under, r, under, line) }
            Style.UNDERLINE_DASHED -> { line.pathEffect = DashPathEffect(floatArrayOf(sw * 4, sw * 3), 0f); c.drawLine(l, under, r, under, line) }
        }
        line.pathEffect = null
        line.color = lineColor
        if (strike) c.drawLine(l, py + cellHeight / 2f, r, py + cellHeight / 2f, line)
        if (over) c.drawLine(l, py + sw, r, py + sw, line)
    }

    /** Marks the cells of the row that are part of a URL. */
    private fun findLinks(row: Row, n: Int) {
        linkCells.fill(false, 0, n)
        if (row.link != null) for (x in 0 until n) if (row.linkAt(x) != 0) linkCells[x] = true
        val sb = StringBuilder(n)
        val col = IntArray(n + 1)
        for (x in 0 until n) {
            val cp = row.cp[x]
            if (cp == Row.WIDE_TAIL) continue
            col[sb.length] = x
            sb.append(if (cp in 0x21..0x7E) cp.toChar() else ' ')
        }
        if (sb.indexOf("://") < 0) return
        for (m in LINK.findAll(sb)) {
            if (m.range.last >= n) continue
            val first = col[m.range.first]
            val last = col[m.range.last]
            for (x in first..last) linkCells[x] = true
        }
    }

    private fun drawCursor(canvas: Canvas, t: TermCore) {
        val y = t.cursorY + scrollRows
        if (!t.cursorVisible || y >= rows) return
        val row = t.row(t.cursorY)
        val x = t.cursorX
        val wide = x + 1 < row.columns && row.cp[x + 1] == Row.WIDE_TAIL
        val l = x * cellWidth
        val r = (x + if (wide) 2 else 1) * cellWidth
        val top = (y * cellHeight).toFloat()
        val bottom = top + cellHeight
        // The cursor takes the accent of the app, unless the program set its own cursor color (OSC 12).
        fill.color = if (cursorColor != 0 && t.palette.colors[Palette.CURSOR] == Palette.DEFAULTS[Palette.CURSOR]) cursorColor else t.palette.colors[Palette.CURSOR]
        val composing = input?.composing ?: ""
        if (composing.isNotEmpty()) {
            // The soft keyboard composes a word: show it at the cursor, underlined.
            setStyle(t, 0, false)
            val w = paint.measureText(composing)
            fill.color = t.palette.colors[Palette.BACKGROUND]
            canvas.drawRect(l, top, l + w, bottom, fill)
            canvas.drawText(composing, l, top + baseline, paint)
            line.color = paint.color
            canvas.drawLine(l, bottom - line.strokeWidth, l + w, bottom - line.strokeWidth, line)
            return
        }
        if (!blinkOn) return
        when {
            !hasFocus() -> {
                line.color = fill.color
                canvas.drawRect(l + line.strokeWidth / 2, top + line.strokeWidth / 2, r - line.strokeWidth / 2, bottom - line.strokeWidth / 2, line)
            }
            t.cursorShape == 1 -> canvas.drawRect(l, bottom - cellHeight / 8f, r, bottom, fill)
            t.cursorShape == 2 -> canvas.drawRect(l, top, l + maxOf(2f, cellWidth / 6), bottom, fill)
            else -> {
                canvas.drawRect(l, top, r, bottom, fill)
                val cp = row.cp[x]
                if (cp > 0 && cp != ' '.code) {
                    setStyle(t, row.style[x], false)
                    paint.color = t.palette.colors[Palette.BACKGROUND]
                    canvas.drawText(String(Character.toChars(cp)) + (row.extraAt(x) ?: ""), l, top + baseline, paint)
                }
            }
        }
    }

    // ---- Selection ----

    /** Selection ends as (line, column). A line is scrolledRows + row, so it stays on its text while output scrolls. */
    private var selStart: Pair<Long, Int>? = null
    private var selEnd: Pair<Long, Int>? = null
    private var actionMode: ActionMode? = null
    private var dragging = 0 // 0 none, 1 the start handle, 2 the end handle.

    /** From the finger to the point the handle marks (the cell edge, at the middle of its row), while it drags. */
    private var grabDx = 0f
    private var grabDy = 0f
    private val handleRadius get() = HANDLE_DP * resources.displayMetrics.density

    /**
     * The handles of the platform (the theme attributes textSelectHandleLeft and Right), tinted with the accent: the same
     * teardrops as in every text field. The hot spot of the left one is at 3/4 of its width, of the right one at 1/4:
     * the point touches the corner of the selection. Null when the theme has none: then a dot shows.
     */
    private val handleLeft by lazy { themeDrawable(android.R.attr.textSelectHandleLeft) }
    private val handleRight by lazy { themeDrawable(android.R.attr.textSelectHandleRight) }
    private fun themeDrawable(attr: Int): android.graphics.drawable.Drawable? {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        return try { a.getDrawable(0)?.mutate() } finally { a.recycle() }
    }

    /** The loupe over the text while a handle moves, as in a text field. */
    private val magnifier by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 29) android.widget.Magnifier.Builder(this).build() else android.widget.Magnifier(this)
    }

    /** Long press, then drag: the finger moves the end of the selection until it lifts. [longAnchor] is the word it selected. */
    private var longDrag = false
    private var longAnchor: Pair<Pair<Long, Int>, Pair<Long, Int>>? = null
    /** The last cell a handle marked: a haptic tick on each new one. */
    private var lastMark: Pair<Long, Int>? = null

    val hasSelection get() = selStart != null

    private fun lineOf(t: TermCore, viewRow: Int) = t.scrolledRows + viewRow - scrollRows

    /** The view row of a line, or null if it is not on the view. */
    private fun viewRowOf(t: TermCore, line: Long): Int? {
        val r = (line - t.scrolledRows + scrollRows).toInt()
        return if (r in 0 until rows) r else null
    }

    private fun ordered(): Pair<Pair<Long, Int>, Pair<Long, Int>>? {
        val a = selStart ?: return null
        val b = selEnd ?: return null
        return if (a.first < b.first || (a.first == b.first && a.second <= b.second)) a to b else b to a
    }

    private fun selectWord(t: TermCore, col: Int, viewRow: Int) {
        val row = t.row(viewRow - scrollRows)
        fun blank(x: Int) = row.cp[x] <= 0 || row.cp[x] == ' '.code
        var a = col.coerceIn(0, row.columns - 1)
        var b = a
        if (!blank(a)) {
            while (a > 0 && (!blank(a - 1) || row.cp[a - 1] == Row.WIDE_TAIL)) a--
            while (b < row.columns - 1 && (!blank(b + 1) || row.cp[b + 1] == Row.WIDE_TAIL)) b++
        }
        val line = lineOf(t, viewRow)
        selStart = line to a
        selEnd = line to b
        showActions()
        invalidate()
    }

    fun clearSelection() {
        val had = selStart != null
        selStart = null
        selEnd = null
        if (had) client.onSelectionEnd()
        if (longDrag || dragging != 0) { longDrag = false; dragging = 0; magnifier.dismiss(); lastMark = null }
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    fun selectedText(): String {
        val t = terminal ?: return ""
        val (a, b) = ordered() ?: return ""
        fun rowIndex(line: Long) = (line - t.scrolledRows).toInt().coerceIn(-t.historySize, t.rows - 1)
        return t.text(rowIndex(a.first), a.second, rowIndex(b.first), b.second)
    }

    // ---- Find ----

    /** The hits of the last [find], oldest first, and the one that shows (-1: none). */
    private var findHits: List<FindHit> = emptyList()
    private var findAt = -1
    val findCount get() = findHits.size
    /** The hit that shows, from 0, or -1. */
    val findIndex get() = findAt

    /** Searches the history and the screen for [query] and shows the newest hit. Returns the number of hits. */
    fun find(query: String): Int {
        val t = terminal ?: return 0
        findHits = t.find(query)
        findAt = findHits.lastIndex
        showHit(t)
        return findHits.size
    }

    /** Shows the next hit ([forward]: newer) or the one before; at the end it goes round. */
    fun findStep(forward: Boolean) {
        val t = terminal ?: return
        if (findHits.isEmpty()) return
        findAt = (findAt + if (forward) 1 else -1).mod(findHits.size)
        showHit(t)
    }

    fun findClear() {
        if (findHits.isEmpty() && findAt < 0) return
        findHits = emptyList(); findAt = -1
        invalidate()
    }

    /** Scrolls the hit into the middle of the view when it is not on it. The alternate screen has no history to scroll. */
    private fun showHit(t: TermCore) {
        val h = findHits.getOrNull(findAt)
        if (h != null && !t.isAlt && viewRowOf(t, h.line) == null) setScroll(rows / 2 - (h.line - t.scrolledRows).toInt())
        invalidate()
    }

    /** Every hit on the view gets a wash of the accent, the one that shows a stronger one: the same layer as the selection. */
    private fun drawHits(canvas: Canvas, t: TermCore) {
        if (findHits.isEmpty()) return
        val first = lineOf(t, 0)
        val last = first + rows - 1
        for ((k, h) in findHits.withIndex()) {
            if (h.line < first || h.line > last) continue
            val y = viewRowOf(t, h.line) ?: continue
            fill.color = (linkColor and 0xFFFFFF) or (if (k == findAt) HIT_NOW_ALPHA else HIT_ALPHA)
            canvas.drawRect(h.start * cellWidth, (y * cellHeight).toFloat(), (h.end + 1) * cellWidth, ((y + 1) * cellHeight).toFloat(), fill)
        }
    }

    private fun drawSelection(canvas: Canvas, t: TermCore) {
        val (a, b) = ordered() ?: return
        fill.color = selectionColor
        for (y in 0 until rows) {
            val line = lineOf(t, y)
            if (line < a.first || line > b.first) continue
            val from = if (line == a.first) a.second else 0
            val to = if (line == b.first) b.second + 1 else columns
            canvas.drawRect(from * cellWidth, (y * cellHeight).toFloat(), to * cellWidth, ((y + 1) * cellHeight).toFloat(), fill)
        }
        // The two handles under the start and the end. No handles during a long-press drag: the finger is the end.
        if (longDrag) return
        fill.color = linkColor
        viewRowOf(t, a.first)?.let { drawHandle(canvas, handleLeft, a.second * cellWidth, (it + 1) * cellHeight, 0.75f) }
        viewRowOf(t, b.first)?.let { drawHandle(canvas, handleRight, (b.second + 1) * cellWidth, (it + 1) * cellHeight, 0.25f) }
    }

    /** Draws a handle with its hot spot ([hot] of its width, at its top) on ([x], [y]). */
    private fun drawHandle(canvas: Canvas, d: android.graphics.drawable.Drawable?, x: Float, y: Int, hot: Float) {
        if (d == null) { canvas.drawCircle(x, y + handleRadius, handleRadius, fill); return }
        val w = d.intrinsicWidth
        val left = (x - w * hot).roundToInt()
        d.setBounds(left, y, left + w, y + d.intrinsicHeight)
        d.setTint(linkColor)
        d.draw(canvas)
    }

    private fun handleAt(t: TermCore, x: Float, y: Float): Int {
        val (a, b) = ordered() ?: return 0
        // The touch area: the handle, at least 48 dp wide and high.
        val minTouch = 48 * resources.displayMetrics.density
        fun near(p: Pair<Long, Int>, dx: Int): Boolean {
            val vr = viewRowOf(t, p.first) ?: return false
            val hx = (p.second + dx) * cellWidth
            val d = if (dx == 0) handleLeft else handleRight
            val hw = maxOf(d?.intrinsicWidth?.toFloat() ?: handleRadius * 2, minTouch) / 2
            val hh = maxOf(d?.intrinsicHeight?.toFloat() ?: handleRadius * 2, minTouch)
            // The middle of the handle: left of the point for the start handle, right of it for the end handle.
            val cx = hx + (if (d == null) 0f else d.intrinsicWidth * (if (dx == 0) -0.25f else 0.25f))
            val top = ((vr + 1) * cellHeight).toFloat()
            if (abs(x - cx) >= hw || y < top - cellHeight / 2f || y >= top + hh) return false
            grabDx = hx - x
            grabDy = (vr + 0.5f) * cellHeight - y
            return true
        }
        return when {
            near(a, 0) -> { selStart = a; selEnd = b; 1 }
            near(b, 1) -> { selStart = a; selEnd = b; 2 }
            else -> 0
        }
    }

    private fun showActions() {
        if (actionMode != null) { actionMode?.invalidateContentRect(); return }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 1, 0, android.R.string.copy)
                menu.add(0, 4, 1, client.copyOneLineLabel())
                if (client.canPaste()) menu.add(0, 2, 2, android.R.string.paste)
                menu.add(0, 3, 3, android.R.string.selectAll)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    1 -> client.copy(selectedText())
                    4 -> client.copy(oneLine(selectedText()))
                    2 -> client.paste()
                    3 -> {
                        // The whole history and the screen. The menu stays: Copy is the next step.
                        val t = terminal ?: return true
                        selStart = (t.scrolledRows - t.historySize) to 0
                        selEnd = (t.scrolledRows + t.rows - 1) to (columns - 1)
                        invalidate()
                        return true
                    }
                }
                clearSelection()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selStart != null) { selStart = null; selEnd = null; invalidate(); client.onSelectionEnd() }
            }
            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val t = terminal
                val (a, b) = ordered() ?: return
                val top = t?.let { viewRowOf(it, a.first) } ?: 0
                val bottom = t?.let { viewRowOf(it, b.first) } ?: (rows - 1)
                outRect.set(0, top * cellHeight, width, (bottom + 1) * cellHeight)
            }
        }, ActionMode.TYPE_FLOATING)
    }

    /** A handle or the finger marks a cell at ([x], [y]): the loupe follows, and a new cell gives a tick. */
    private fun mark(p: Pair<Long, Int>, x: Float, y: Float) {
        if (p != lastMark) { if (lastMark != null) performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE); lastMark = p }
        magnifier.show(x.coerceIn(0f, width.toFloat()), y)
    }

    /** The drag ended: no loupe, and the toolbar comes back next to the new selection. */
    private fun endDrag() {
        magnifier.dismiss()
        lastMark = null
        actionMode?.invalidateContentRect()
        actionMode?.hide(0)
    }

    // ---- Touch ----

    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f

    private fun cell(e: MotionEvent): Pair<Int, Int> {
        val col = (e.x / cellWidth).toInt().coerceIn(0, maxOf(columns - 1, 0))
        val row = (e.y / cellHeight).toInt().coerceIn(0, maxOf(rows - 1, 0))
        return col to row
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val t = terminal ?: return true
            if (hasSelection) { clearSelection(); return true }
            // A tap is no mouse click: tmux always reads the mouse, so a click moved the cursor of the program
            // (the input box of Claude Code) to the tap. The cursor stays where the program has it; the arrow keys move it.
            val (col, row) = cell(e)
            client.onTap(col, row - scrollRows)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val t = terminal ?: return
            val (col, row) = cell(e)
            if (client.onLongPress(col, row - scrollRows)) return
            performHapticFeedback(HAPTIC_SELECT)
            selectWord(t, col, row)
            longAnchor = ordered()
            longDrag = true
            actionMode?.hide(TOOLBAR_HIDE_MS)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollBy(dy, e2)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val t = terminal ?: return true
            if (t.mouseMode != 0 || t.isAlt) return true
            scroller.fling(0, scrollRows * cellHeight, 0, vy.toInt(), 0, 0, 0, t.historySize * cellHeight)
            postInvalidateOnAnimation()
            return true
        }
    })

    // ---- Pinch zoom ----
    // During the pinch the view only scales its picture around the fingers: no reflow, no resize of tmux. A label shows the
    // size and the grid that the pinch gives, and each step of 1 sp gives a haptic tick. When the fingers lift, the text
    // size changes once.

    private var zoomPx = 0
    private var zoomLabel: String? = null
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var scale = 1f
        private var minScale = 1f
        private var maxScale = 1f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scale = 1f
            minScale = client.zoomSize(0f) / paint.textSize
            maxScale = client.zoomSize(Float.MAX_VALUE) / paint.textSize
            zoomPx = paint.textSize.toInt()
            pivotX = detector.focusX
            pivotY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            // The scale stays inside the limits of the text size. Do not round it to the whole pixels of px:
            // one step of a pinch is 1 to 3 %, so a rounded scale falls back to 1 and a zoom in never grows.
            scale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val px = client.zoomSize(scale)
            scaleX = scale
            scaleY = scale
            if (px != zoomPx) {
                val sp = { v: Int -> (v / (resources.displayMetrics.density * resources.configuration.fontScale)).roundToInt() }
                if (sp(px) != sp(zoomPx)) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                zoomPx = px
            }
            val sp = (px / (resources.displayMetrics.density * resources.configuration.fontScale)).roundToInt()
            zoomLabel = "$sp sp · ${(width / (cellWidth * scale)).toInt()}×${(height / (cellHeight * scale)).toInt()}"
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaleX = 1f
            scaleY = 1f
            zoomLabel = null
            if (zoomPx != paint.textSize.toInt()) client.zoomTo(zoomPx) else invalidate()
        }
    })

    /** The zoom label, over the text, at the top centre. It undoes the scale of the view, so it keeps its size. */
    override fun onDrawForeground(canvas: Canvas) {
        super.onDrawForeground(canvas)
        val label = zoomLabel ?: return
        canvas.save()
        canvas.scale(1 / scaleX, 1 / scaleY, pivotX, pivotY)
        val d = resources.displayMetrics.density
        labelPaint.textSize = 14 * d * resources.configuration.fontScale
        labelPaint.typeface = faces[0]
        labelPaint.color = Palette.DEFAULTS[Palette.FOREGROUND]
        labelFill.color = cursorColor.takeIf { it != 0 } ?: Palette.DEFAULTS[Palette.CURSOR]
        val w = labelPaint.measureText(label) + 24 * d
        val h = 36 * d
        val left = (width - w) / 2
        val top = 16 * d
        labelFill.alpha = 230
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, labelFill)
        labelPaint.color = Palette.DEFAULTS[Palette.BACKGROUND]
        val fm = labelPaint.fontMetrics
        canvas.drawText(label, left + 12 * d, top + h / 2 - (fm.ascent + fm.descent) / 2, labelPaint)
        canvas.restore()
    }

    /** Called when the view sends wheel events to a program (tmux): the slider reads the new position. */
    var onWheel: (() -> Unit)? = null

    /** Called when the view scrolls its own history: the rows up from the bottom and the history size. A slider follows it. */
    var onScroll: ((rows: Int, history: Int) -> Unit)? = null

    /** Shows the history [rows] up from the bottom (0 is the live screen). */
    fun scrollTo(rows: Int) = setScroll(rows)

    /** A drag: scrolls the history, or sends wheel events or arrow keys when a program takes them. */
    private fun scrollBy(dy: Float, e: MotionEvent) {
        val t = terminal ?: return
        scrollRemainder += dy
        val n = (scrollRemainder / cellHeight).toInt()
        if (n == 0) return
        scrollRemainder -= n * cellHeight
        when {
            t.mouseMode != 0 -> {
                val (col, row) = cell(e)
                val button = if (n < 0) Keys.Button.WHEEL_UP else Keys.Button.WHEEL_DOWN
                repeat(abs(n)) { Keys.mouse(t, button, Keys.MouseAction.PRESS, col, row)?.let(client::write) }
                onWheel?.invoke()
            }
            t.isAlt -> repeat(abs(n)) { Keys.key(t, if (n < 0) Key.UP else Key.DOWN)?.let(client::write) }
            else -> setScroll(scrollRows - n)
        }
    }

    private fun setScroll(r: Int) {
        val t = terminal ?: return
        val v = r.coerceIn(0, t.historySize)
        if (v != scrollRows) { scrollRows = v; invalidate(); onScroll?.invoke(v, t.historySize) }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            setScroll((scroller.currY / cellHeight.toFloat()).roundToInt())
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = terminal
        if (t != null && longDrag) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    // The word stays selected; the finger moves the far end, before or after it.
                    val (wa, wb) = longAnchor ?: return true
                    val col = (e.x / cellWidth).toInt().coerceIn(0, columns - 1)
                    val vr = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
                    val p = lineOf(t, vr) to col
                    val before = p.first < wa.first || (p.first == wa.first && p.second < wa.second)
                    if (before) { selStart = p; selEnd = wb } else { selStart = wa; selEnd = maxOf(p, wb, compareBy({ it.first }, { it.second })) }
                    mark(p, e.x, (vr + 0.5f) * cellHeight)
                    actionMode?.hide(TOOLBAR_HIDE_MS)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag()
                    longDrag = false
                    invalidate()
                    return true
                }
            }
        }
        if (t != null && hasSelection) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = handleAt(t, e.x, e.y)
                    // A touch on a handle is only a drag: the gesture detector must not see it as a long press.
                    if (dragging != 0) { actionMode?.hide(TOOLBAR_HIDE_MS); return true }
                }
                MotionEvent.ACTION_MOVE -> if (dragging != 0) {
                    // The finger holds the dot under the row: move the point the handle marks by the same amount.
                    val col = ((e.x + grabDx) / cellWidth).roundToInt().coerceIn(0, columns)
                    val vr = ((e.y + grabDy) / cellHeight).toInt().coerceIn(0, rows - 1)
                    val line = lineOf(t, vr)
                    if (dragging == 1) selStart = line to col.coerceAtMost(columns - 1)
                    else selEnd = line to (col - 1).coerceAtLeast(0)
                    mark(line to col, col * cellWidth, (vr + 0.5f) * cellHeight)
                    actionMode?.hide(TOOLBAR_HIDE_MS)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging != 0) {
                    dragging = 0
                    endDrag()
                    return true
                }
            }
        }
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    // ---- Keys and the soft keyboard ----

    private var input: Input? = null

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        // No suggestions and no auto-correct: the keyboard sends each key at once.
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        info.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        return Input().also { input = it }
    }

    /** Sends typed text: Enter for a newline, and the latched Ctrl, Alt and Shift of the key row. */
    private fun sendText(text: CharSequence) {
        val t = terminal ?: return
        if (text.isEmpty()) return
        val mods = client.latchedModifiers()
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            // A latched Shift makes a letter upper case, as a keyboard does: the program gets the letter, not the Shift.
            // Enter keeps the Shift (Shift+Enter is a new line in Claude Code).
            val shift = mods and Keys.SHIFT != 0
            val c = if (shift && Character.isLetter(cp)) Character.toUpperCase(cp) else cp
            val out = if (cp == '\n'.code) Keys.key(t, Key.ENTER, mods) else Keys.text(t, c, mods and Keys.SHIFT.inv())
            out?.let(client::write)
        }
        scrollToBottom()
        restartBlink()
        client.onTyped()
    }

    private fun scrollToBottom() {
        if (scrollRows != 0) setScroll(0)
        if (hasSelection) clearSelection()
    }

    private inner class Input : BaseInputConnection(this@TermView, true) {
        var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            sendText(text)
            editable?.clear()
            invalidate()
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = text.toString()
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            val c = composing
            composing = ""
            sendText(c)
            editable?.clear()
            invalidate()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val t = terminal ?: return true
            repeat(beforeLength) { Keys.key(t, Key.BACKSPACE)?.let(client::write) }
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(keyCode, event, KeyAction.PRESS) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = key(keyCode, event, KeyAction.RELEASE) || super.onKeyUp(keyCode, event)

    private fun key(keyCode: Int, event: KeyEvent, action0: KeyAction): Boolean {
        val t = terminal ?: return false
        if (keyCode == KeyEvent.KEYCODE_BACK || event.isSystem && KEYS[keyCode] == null) return false
        val action = if (action0 == KeyAction.PRESS && event.repeatCount > 0) KeyAction.REPEAT else action0
        var mods = client.latchedModifiers()
        if (event.isShiftPressed) mods = mods or Keys.SHIFT
        if (event.isAltPressed) mods = mods or Keys.ALT
        if (event.isCtrlPressed) mods = mods or Keys.CTRL
        if (event.isMetaPressed) mods = mods or Keys.SUPER
        val k = KEYS[keyCode]
        val out = if (k != null) {
            Keys.key(t, k, mods, action)
        } else {
            val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK).inv()
            val cp = event.getUnicodeChar(meta)
            if (cp == 0 || cp and android.view.KeyCharacterMap.COMBINING_ACCENT != 0) return false
            // The latched Shift of the key row is not in the meta state: it makes the letter upper case here.
            Keys.text(t, if (mods and Keys.SHIFT != 0 && Character.isLetter(cp)) Character.toUpperCase(cp) else cp, mods and Keys.SHIFT.inv(), action)
        }
        if (action != KeyAction.RELEASE) {
            restartBlink()
            scrollToBottom()
            client.onTyped()
        }
        out?.let(client::write)
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: Rect?) {
        super.onFocusChanged(gainFocus, direction, previous)
        terminal?.let { Keys.focus(it, gainFocus)?.let(client::write) }
        restartBlink()
        invalidate()
    }

    companion object {
        private const val SYNC_MS = 150L
        private const val FREEZE_MS = 300L
        private const val QUIET_MS = 40L
        private const val BLINK_MS = 530L
        private const val HANDLE_DP = 9f
        /** How long a drag hides the toolbar: the longest time Android allows. Each move starts it again. */
        private const val TOOLBAR_HIDE_MS = 3000L
        private const val SELECTION_COLOR = 0x59FFFFFF
        /** The alpha of a search hit, and of the hit that shows: the text stays readable through both. */
        private const val HIT_ALPHA = 0x55000000
        private const val HIT_NOW_ALPHA = 0xA0000000.toInt()
        private val HAPTIC_SELECT = android.view.HapticFeedbackConstants.LONG_PRESS
        private val LINK = Regex("""https?://[^\s<>"'`]+[^\s<>"'`.,;:)\]}]""")

        private val KEYS: Map<Int, Key> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Key.UP, KeyEvent.KEYCODE_DPAD_DOWN to Key.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to Key.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to Key.RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to Key.HOME, KeyEvent.KEYCODE_MOVE_END to Key.END,
            KeyEvent.KEYCODE_INSERT to Key.INSERT, KeyEvent.KEYCODE_FORWARD_DEL to Key.DELETE,
            KeyEvent.KEYCODE_PAGE_UP to Key.PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN to Key.PAGE_DOWN,
            KeyEvent.KEYCODE_F1 to Key.F1, KeyEvent.KEYCODE_F2 to Key.F2, KeyEvent.KEYCODE_F3 to Key.F3,
            KeyEvent.KEYCODE_F4 to Key.F4, KeyEvent.KEYCODE_F5 to Key.F5, KeyEvent.KEYCODE_F6 to Key.F6,
            KeyEvent.KEYCODE_F7 to Key.F7, KeyEvent.KEYCODE_F8 to Key.F8, KeyEvent.KEYCODE_F9 to Key.F9,
            KeyEvent.KEYCODE_F10 to Key.F10, KeyEvent.KEYCODE_F11 to Key.F11, KeyEvent.KEYCODE_F12 to Key.F12,
            KeyEvent.KEYCODE_ENTER to Key.ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER to Key.KP_ENTER,
            KeyEvent.KEYCODE_TAB to Key.TAB, KeyEvent.KEYCODE_DEL to Key.BACKSPACE, KeyEvent.KEYCODE_ESCAPE to Key.ESCAPE,
            KeyEvent.KEYCODE_NUMPAD_0 to Key.KP_0, KeyEvent.KEYCODE_NUMPAD_1 to Key.KP_1, KeyEvent.KEYCODE_NUMPAD_2 to Key.KP_2,
            KeyEvent.KEYCODE_NUMPAD_3 to Key.KP_3, KeyEvent.KEYCODE_NUMPAD_4 to Key.KP_4, KeyEvent.KEYCODE_NUMPAD_5 to Key.KP_5,
            KeyEvent.KEYCODE_NUMPAD_6 to Key.KP_6, KeyEvent.KEYCODE_NUMPAD_7 to Key.KP_7, KeyEvent.KEYCODE_NUMPAD_8 to Key.KP_8,
            KeyEvent.KEYCODE_NUMPAD_9 to Key.KP_9, KeyEvent.KEYCODE_NUMPAD_DOT to Key.KP_DECIMAL,
            KeyEvent.KEYCODE_NUMPAD_DIVIDE to Key.KP_DIVIDE, KeyEvent.KEYCODE_NUMPAD_MULTIPLY to Key.KP_MULTIPLY,
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT to Key.KP_SUBTRACT, KeyEvent.KEYCODE_NUMPAD_ADD to Key.KP_ADD,
            KeyEvent.KEYCODE_NUMPAD_EQUALS to Key.KP_EQUAL,
        )
    }
}

/** Main-thread time of the terminal (parse and draw), logged once a second while it works. Debug builds only. */
object TermPerf {
    @JvmField var enabled = false
    private var parseNs = 0L
    private var bytes = 0L
    private var renderNs = 0L
    private var frames = 0
    private var windowStart = 0L

    fun parse(ns: Long, n: Int) { parseNs += ns; bytes += n; flush() }
    fun render(ns: Long) { renderNs += ns; frames++; flush() }

    private fun flush() {
        val now = System.nanoTime()
        if (windowStart == 0L) windowStart = now
        if (now - windowStart < 1_000_000_000L) return
        Log.d("TermPerf", "parse ${parseNs / 1_000_000} ms (${bytes / 1024} KB), render ${renderNs / 1_000_000} ms ($frames frames)")
        parseNs = 0; bytes = 0; renderNs = 0; frames = 0; windowStart = now
    }
}

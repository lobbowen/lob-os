package com.spizganed.mtty.term

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.floor
import kotlin.math.max

/**
 * Draws some chars as shapes, not with the font, so that they fill the cell and join the next cell:
 * box drawing, block elements, Powerline separators, and a few symbols that phone fonts often lack.
 */
internal class Glyphs {
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val smooth = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val oval = RectF()

    /** True if the view must draw the char as a shape. [fontHas] says if the font has a glyph for it. */
    fun handles(cp: Int, fontHas: (Int) -> Boolean): Boolean = when (cp) {
        in 0x2500..0x259F -> true
        0xE0B0, 0xE0B1, 0xE0B2, 0xE0B3, 0xE0B4, 0xE0B6 -> true
        in 0x23F4..0x23FA -> !fontHas(cp)
        else -> false
    }

    /**
     * Draws the char in the cell (left, top, width w, height h). [light] is the width of a light line in pixels.
     */
    fun draw(c: Canvas, cp: Int, left: Float, top: Float, w: Float, h: Float, light: Float, color: Int) {
        // Snap to pixels, so that lines in the next cells meet with no gap or overlap.
        val l = floor(left)
        val t = floor(top)
        val r = floor(left + w)
        val b = floor(top + h)
        fill.color = color; stroke.color = color; smooth.color = color
        when (cp) {
            in 0x2500..0x257F -> lines(c, BoxTable.LINES[cp - 0x2500], l, t, r, b, light)
            in 0x2580..0x259F -> block(c, BoxTable.BLOCKS[cp - 0x2580], l, t, r, b, color)
            else -> symbol(c, cp, l, t, r, b, light)
        }
    }

    private fun lines(c: Canvas, v: Int, l: Float, t: Float, r: Float, b: Float, light: Float) {
        val cx = floor((l + r) / 2)
        val cy = floor((t + b) / 2)
        val lw = max(1f, floor(light))
        fun width(weight: Int) = if (weight == 2) lw * 2 else lw

        val diag = (v shr 12) and 3
        if (diag != 0) {
            stroke.strokeWidth = width((v shr 14) and 3)
            if (diag and 1 != 0) c.drawLine(r, t, l, b, stroke)
            if (diag and 2 != 0) c.drawLine(l, t, r, b, stroke)
            return
        }
        val up = v and 3
        val right = (v shr 2) and 3
        val down = (v shr 4) and 3
        val left = (v shr 6) and 3

        if (v and 0x800 != 0) { // An arc: a rounded corner of a light line.
            stroke.strokeWidth = lw
            val rad = minOf(r - l, b - t) / 2
            // The middle of the stroke, where the straight lines have it.
            val x = cx - floor(lw / 2) + lw / 2
            val y = cy - floor(lw / 2) + lw / 2
            path.reset()
            val vy = if (up != 0) t else b
            val hx = if (left != 0) l else r
            path.moveTo(x, vy)
            path.lineTo(x, y + if (up != 0) -rad else rad)
            path.quadTo(x, y, x + if (left != 0) -rad else rad, y)
            path.lineTo(hx, y)
            c.drawPath(path, stroke)
            return
        }

        val dashes = (v shr 8) and 7
        if (dashes != 0) {
            val horizontal = right != 0
            val weight = if (horizontal) right else up
            val thick = width(weight)
            val len = if (horizontal) r - l else b - t
            val seg = len / dashes
            val gap = max(1f, floor(seg / 3))
            for (i in 0 until dashes) {
                val a = floor(seg * i)
                val e = floor(seg * (i + 1) - gap)
                if (horizontal) c.drawRect(l + a, cy - floor(thick / 2), l + e, cy - floor(thick / 2) + thick, fill)
                else c.drawRect(cx - floor(thick / 2), t + a, cx - floor(thick / 2) + thick, t + e, fill)
            }
            return
        }

        // Solid arms. Each arm runs from the cell edge to the center, past it by half of the widest cross arm,
        // so that the joints are filled.
        val hReach = max(if (up == 3 || down == 3) lw * 1.5f else width(maxOf(up, down)) / 2, 0f)
        val vReach = max(if (left == 3 || right == 3) lw * 1.5f else width(maxOf(left, right)) / 2, 0f)
        fun vertical(weight: Int, from: Float, to: Float) {
            if (weight == 0) return
            val a = minOf(from, to)
            val e = maxOf(from, to)
            if (weight == 3) {
                c.drawRect(cx - lw * 1.5f, a, cx - lw * 0.5f, e, fill)
                c.drawRect(cx + lw * 0.5f, a, cx + lw * 1.5f, e, fill)
            } else {
                val th = width(weight)
                c.drawRect(cx - floor(th / 2), a, cx - floor(th / 2) + th, e, fill)
            }
        }
        fun horizontal(weight: Int, from: Float, to: Float) {
            if (weight == 0) return
            val a = minOf(from, to)
            val e = maxOf(from, to)
            if (weight == 3) {
                c.drawRect(a, cy - lw * 1.5f, e, cy - lw * 0.5f, fill)
                c.drawRect(a, cy + lw * 0.5f, e, cy + lw * 1.5f, fill)
            } else {
                val th = width(weight)
                c.drawRect(a, cy - floor(th / 2), e, cy - floor(th / 2) + th, fill)
            }
        }
        vertical(up, t, cy + vReach)
        vertical(down, cy - vReach, b)
        horizontal(left, l, cx + hReach)
        horizontal(right, cx - hReach, r)
    }

    private fun block(c: Canvas, spec: IntArray, l: Float, t: Float, r: Float, b: Float, color: Int) {
        if (spec.size == 1) { // A shade: the color at 25, 50 or 75 percent.
            val alpha = (-spec[0]) * 64
            fill.color = (color and 0x00FFFFFF) or (alpha shl 24)
            c.drawRect(l, t, r, b, fill)
            fill.color = color
            return
        }
        val w = r - l
        val h = b - t
        var i = 0
        while (i < spec.size) {
            c.drawRect(l + floor(w * spec[i] / 8), t + floor(h * spec[i + 1] / 8), l + floor(w * spec[i + 2] / 8), t + floor(h * spec[i + 3] / 8), fill)
            i += 4
        }
    }

    private fun symbol(c: Canvas, cp: Int, l: Float, t: Float, r: Float, b: Float, light: Float) {
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        path.reset()
        when (cp) {
            // Powerline: solid and thin arrows, and half circles.
            0xE0B0 -> { path.moveTo(l, t); path.lineTo(r, cy); path.lineTo(l, b); path.close(); c.drawPath(path, smooth) }
            0xE0B2 -> { path.moveTo(r, t); path.lineTo(l, cy); path.lineTo(r, b); path.close(); c.drawPath(path, smooth) }
            0xE0B1 -> { stroke.strokeWidth = max(1f, light); path.moveTo(l, t); path.lineTo(r, cy); path.lineTo(l, b); c.drawPath(path, stroke) }
            0xE0B3 -> { stroke.strokeWidth = max(1f, light); path.moveTo(r, t); path.lineTo(l, cy); path.lineTo(r, b); c.drawPath(path, stroke) }
            0xE0B4 -> { oval.set(l - (r - l), t, r, b); c.drawArc(oval, -90f, 180f, true, smooth) }
            0xE0B6 -> { oval.set(l, t, r + (r - l), b); c.drawArc(oval, 90f, 180f, true, smooth) }
            else -> {
                // The media symbols U+23F4 to U+23FA, in a square of 60 percent of the cell width.
                val s = (r - l) * 0.3f
                when (cp) {
                    0x23F4 -> { path.moveTo(cx + s, cy - s); path.lineTo(cx - s, cy); path.lineTo(cx + s, cy + s); path.close(); c.drawPath(path, smooth) }
                    0x23F5 -> { path.moveTo(cx - s, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s, cy + s); path.close(); c.drawPath(path, smooth) }
                    0x23F6 -> { path.moveTo(cx - s, cy + s); path.lineTo(cx, cy - s); path.lineTo(cx + s, cy + s); path.close(); c.drawPath(path, smooth) }
                    0x23F7 -> { path.moveTo(cx - s, cy - s); path.lineTo(cx, cy + s); path.lineTo(cx + s, cy - s); path.close(); c.drawPath(path, smooth) }
                    0x23F8 -> { c.drawRect(cx - s, cy - s, cx - s / 3, cy + s, smooth); c.drawRect(cx + s / 3, cy - s, cx + s, cy + s, smooth) }
                    0x23F9 -> c.drawRect(cx - s, cy - s, cx + s, cy + s, smooth)
                    0x23FA -> c.drawCircle(cx, cy, s, smooth)
                }
            }
        }
    }
}

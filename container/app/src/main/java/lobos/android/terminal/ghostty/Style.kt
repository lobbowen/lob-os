package com.spizganed.mtty.term

/**
 * The style of a cell, packed into one Long. 0 is the default style: default colors and no attributes.
 * - bits 0 to 24: the foreground color. bits 25 to 49: the background color. See [Color].
 * - bits 50 to 63: the attribute flags.
 */
object Style {
    const val BOLD = 1L shl 50
    const val DIM = 1L shl 51
    const val ITALIC = 1L shl 52
    const val BLINK = 1L shl 53
    const val INVERSE = 1L shl 54
    const val INVISIBLE = 1L shl 55
    const val STRIKE = 1L shl 56
    const val OVERLINE = 1L shl 57

    /** DECSCA: selective erase (DECSED, DECSEL, DECSERA) keeps this cell. */
    const val PROTECTED = 1L shl 58

    /** The underline style, bits 59 to 61: one of the UNDERLINE_* values. */
    private const val UNDERLINE_SHIFT = 59
    const val UNDERLINE_MASK = 7L shl UNDERLINE_SHIFT
    const val UNDERLINE_NONE = 0
    const val UNDERLINE_SINGLE = 1
    const val UNDERLINE_DOUBLE = 2
    const val UNDERLINE_CURLY = 3
    const val UNDERLINE_DOTTED = 4
    const val UNDERLINE_DASHED = 5

    private const val COLOR_MASK = (1L shl 25) - 1
    private const val BG_SHIFT = 25

    fun fg(style: Long): Int = (style and COLOR_MASK).toInt()
    fun bg(style: Long): Int = ((style ushr BG_SHIFT) and COLOR_MASK).toInt()
    fun underline(style: Long): Int = ((style and UNDERLINE_MASK) ushr UNDERLINE_SHIFT).toInt()

    fun withFg(style: Long, color: Int): Long = (style and COLOR_MASK.inv()) or color.toLong()
    fun withBg(style: Long, color: Int): Long = (style and (COLOR_MASK shl BG_SHIFT).inv()) or (color.toLong() shl BG_SHIFT)
    fun withUnderline(style: Long, kind: Int): Long = (style and UNDERLINE_MASK.inv()) or (kind.toLong() shl UNDERLINE_SHIFT)

    /** The style of an erased cell: only the background color stays (xterm "bce"). */
    fun blank(style: Long): Long = style and (COLOR_MASK shl BG_SHIFT)
}

/**
 * A color in 25 bits: 0 is the default color, 1 to 256 a palette index plus 1, and a 24-bit color has bit 24 set.
 */
object Color {
    const val DEFAULT = 0
    private const val RGB_FLAG = 1 shl 24

    fun indexed(i: Int): Int = i + 1
    fun rgb(r: Int, g: Int, b: Int): Int = RGB_FLAG or (r shl 16) or (g shl 8) or b

    fun isDefault(c: Int) = c == 0
    fun isRgb(c: Int) = c and RGB_FLAG != 0

    /** The palette index of an indexed color. */
    fun index(c: Int) = c - 1

    /** The 0xRRGGBB value of a 24-bit color. */
    fun rgbOf(c: Int) = c and 0xFFFFFF
}

/** The 256 colors and the default foreground, background and cursor colors. OSC 4, 10, 11 and 12 change them. */
class Palette {
    /** 0xFFRRGGBB values: 0 to 255, then [FOREGROUND], [BACKGROUND], [CURSOR]. */
    val colors = DEFAULTS.copyOf()

    fun reset() = DEFAULTS.copyInto(colors)
    fun reset(i: Int) { colors[i] = DEFAULTS[i] }

    /** The ARGB value of a cell color. foreground: true for a foreground, so that DEFAULT means the foreground. */
    fun argb(c: Int, foreground: Boolean): Int = when {
        Color.isDefault(c) -> colors[if (foreground) FOREGROUND else BACKGROUND]
        Color.isRgb(c) -> OPAQUE or Color.rgbOf(c)
        else -> colors[Color.index(c)]
    }

    companion object {
        const val FOREGROUND = 256
        const val BACKGROUND = 257
        const val CURSOR = 258
        const val SIZE = 259
        private const val OPAQUE = 0xFF000000.toInt()

        /**
         * The default colors: the 16 base colors are warm, muted tones that read on a black ground and sit next to the
         * cat orange of the app: coral for red, sage for green, sand for yellow. The bright colors are a small step
         * lighter, not a jump to full saturation. The 6x6x6 cube and the 24 greys are those of xterm. The text is a warm
         * off-white, as the chat text.
         */
        val DEFAULTS = IntArray(SIZE).also { c ->
            val base = intArrayOf(
                0x1C1B1A, 0xD9776B, 0x9DB87A, 0xE0B565, 0x7FA6D6, 0xC28FC9, 0x76B5B0, 0xD6D2CB,
                0x6B6862, 0xE8918A, 0xB3CC92, 0xF0C980, 0x9BBCE6, 0xD3A7D9, 0x92C9C4, 0xF2EFEA,
            )
            for (i in 0 until 16) c[i] = base[i]
            for (i in 0 until 216) {
                fun level(v: Int) = if (v == 0) 0 else 55 + 40 * v
                c[16 + i] = (level(i / 36) shl 16) or (level(i / 6 % 6) shl 8) or level(i % 6)
            }
            for (i in 0 until 24) c[232 + i] = (8 + 10 * i) * 0x010101
            c[FOREGROUND] = 0xE3E1DC
            c[BACKGROUND] = 0x000000
            c[CURSOR] = 0xC88814
            for (i in c.indices) c[i] = c[i] or OPAQUE
        }
    }
}

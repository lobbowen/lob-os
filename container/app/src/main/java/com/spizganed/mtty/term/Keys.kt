package com.spizganed.mtty.term

/** The keys that are not text. The view maps the platform key codes to these. */
enum class Key {
    UP, DOWN, RIGHT, LEFT, HOME, END, INSERT, DELETE, PAGE_UP, PAGE_DOWN,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    ENTER, TAB, BACKSPACE, ESCAPE,
    KP_0, KP_1, KP_2, KP_3, KP_4, KP_5, KP_6, KP_7, KP_8, KP_9,
    KP_DECIMAL, KP_DIVIDE, KP_MULTIPLY, KP_SUBTRACT, KP_ADD, KP_ENTER, KP_EQUAL,
}

/** A key press, a repeat while the key stays down, or the release. */
enum class KeyAction { PRESS, REPEAT, RELEASE }

/**
 * Turns keys, text and mouse events into the bytes the program expects, from the modes of the [TermCore].
 * References: xterm "PC-Style Function Keys" (https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) and
 * the Kitty keyboard protocol (https://sw.kovidgoyal.net/kitty/keyboard-protocol/).
 */
object Keys {
    // Modifier bits, as xterm and Kitty count them. The sequence sends 1 + the bits.
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
    const val SUPER = 8

    // Kitty progressive enhancement flags.
    const val KITTY_DISAMBIGUATE = 1
    const val KITTY_EVENT_TYPES = 2
    const val KITTY_ALL_AS_ESCAPES = 8

    /** The flags this encoder does. A program that asks for more gets only these. */
    const val KITTY_SUPPORTED = KITTY_DISAMBIGUATE or KITTY_EVENT_TYPES or KITTY_ALL_AS_ESCAPES

    private const val ESC = "\u001b"
    private const val CSI = "\u001b["
    private const val SS3 = "\u001bO"

    /** The bytes for a key, or null if the key sends nothing (for example a release in legacy mode). */
    fun key(t: TermCore, key: Key, mods: Int = 0, action: KeyAction = KeyAction.PRESS): String? {
        val flags = t.keyboardFlags
        if (flags != 0) return kittyKey(t, key, mods, action, flags)
        if (action == KeyAction.RELEASE) return null
        val m = if (mods == 0) "" else "1;${mods + 1}"
        return when (key) {
            Key.UP -> cursor(t, 'A', mods)
            Key.DOWN -> cursor(t, 'B', mods)
            Key.RIGHT -> cursor(t, 'C', mods)
            Key.LEFT -> cursor(t, 'D', mods)
            Key.HOME -> cursor(t, 'H', mods)
            Key.END -> cursor(t, 'F', mods)
            Key.INSERT -> tilde(2, mods)
            Key.DELETE -> tilde(3, mods)
            Key.PAGE_UP -> tilde(5, mods)
            Key.PAGE_DOWN -> tilde(6, mods)
            Key.F1 -> if (mods == 0) "${SS3}P" else "$CSI${m}P"
            Key.F2 -> if (mods == 0) "${SS3}Q" else "$CSI${m}Q"
            Key.F3 -> if (mods == 0) "${SS3}R" else "$CSI${m}R"
            Key.F4 -> if (mods == 0) "${SS3}S" else "$CSI${m}S"
            Key.F5 -> tilde(15, mods)
            Key.F6 -> tilde(17, mods)
            Key.F7 -> tilde(18, mods)
            Key.F8 -> tilde(19, mods)
            Key.F9 -> tilde(20, mods)
            Key.F10 -> tilde(21, mods)
            Key.F11 -> tilde(23, mods)
            Key.F12 -> tilde(24, mods)
            Key.ENTER -> alt(mods, if (t.newlineMode) "\r\n" else "\r")
            Key.TAB -> if (mods and SHIFT != 0) "${CSI}Z" else alt(mods, "\t")
            // xterm and most terminals: DEL, or BS with Ctrl.
            Key.BACKSPACE -> alt(mods, if (mods and CTRL != 0) "\b" else "\u007f")
            Key.ESCAPE -> alt(mods, ESC)
            else -> keypad(t, key, mods)
        }
    }

    /** The cursor keys: SS3 in cursor key mode (DECCKM), else CSI. With modifiers: CSI 1;m x. */
    private fun cursor(t: TermCore, c: Char, mods: Int) = when {
        mods != 0 -> "${CSI}1;${mods + 1}$c"
        t.cursorKeysApp -> "$SS3$c"
        else -> "$CSI$c"
    }

    private fun tilde(n: Int, mods: Int) = if (mods == 0) "$CSI$n~" else "$CSI$n;${mods + 1}~"

    /** Alt sends ESC before the key. */
    private fun alt(mods: Int, s: String) = if (mods and ALT != 0) ESC + s else s

    private fun keypad(t: TermCore, key: Key, mods: Int): String {
        val i = key.ordinal - Key.KP_0.ordinal
        val app = "pqrstuvwxynojmkMX"[i]
        val text = "0123456789./*-+\r="[i].toString()
        return if (t.keypadApp) (if (mods == 0) "$SS3$app" else "$CSI${1};${mods + 1}$app") else alt(mods, text)
    }

    /**
     * Typed text: one code point, as the keyboard made it (Shift is already in it). Ctrl turns it into a control
     * char, Alt puts ESC before it.
     */
    fun text(t: TermCore, cp: Int, mods: Int = 0, action: KeyAction = KeyAction.PRESS): String? {
        val flags = t.keyboardFlags
        if (flags != 0) return kittyText(cp, mods, action, flags)
        if (action == KeyAction.RELEASE) return null
        if (mods and CTRL != 0) {
            val c = control(cp)
            if (c >= 0) return alt(mods, c.toChar().toString())
        }
        return alt(mods, String(Character.toChars(cp)))
    }

    /** The control char for Ctrl and the char, as a VT220 keyboard makes it, or -1. */
    fun control(cp: Int): Int = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in '@'.code..'_'.code -> cp - '@'.code // @ A-Z [ \ ] ^ _
        ' '.code, '2'.code -> 0
        in '3'.code..'7'.code -> cp - '3'.code + 27 // ESC, FS, GS, RS, US.
        '8'.code, '?'.code -> 127
        '/'.code -> 31
        else -> -1
    }

    // ---- Kitty ----

    /** The mods and event type part: "", ";m" or ";m:e". */
    private fun kittyMods(mods: Int, action: KeyAction, flags: Int): String {
        val event = if (flags and KITTY_EVENT_TYPES != 0) action.ordinal + 1 else 1
        return when {
            event != 1 -> ";${mods + 1}:$event"
            mods != 0 -> ";${mods + 1}"
            else -> ""
        }
    }

    private fun kittyKey(t: TermCore, key: Key, mods: Int, action: KeyAction, flags: Int): String? {
        val all = flags and KITTY_ALL_AS_ESCAPES != 0
        if (action == KeyAction.RELEASE && flags and KITTY_EVENT_TYPES == 0) return null
        if (action == KeyAction.REPEAT && flags and KITTY_EVENT_TYPES == 0) return kittyKey(t, key, mods, KeyAction.PRESS, flags)
        val tail = kittyMods(mods, action, flags)
        // "CSI 1;m x" for the letter forms, "CSI x" with no mods and no event type.
        fun letter(c: Char) = if (tail.isEmpty()) "$CSI$c" else "${CSI}1$tail$c"
        fun tildeK(n: Int) = "$CSI$n$tail~"
        fun u(code: Int) = "$CSI$code${tail}u"
        return when (key) {
            Key.UP -> letter('A')
            Key.DOWN -> letter('B')
            Key.RIGHT -> letter('C')
            Key.LEFT -> letter('D')
            Key.HOME -> letter('H')
            Key.END -> letter('F')
            Key.INSERT -> tildeK(2)
            Key.DELETE -> tildeK(3)
            Key.PAGE_UP -> tildeK(5)
            Key.PAGE_DOWN -> tildeK(6)
            Key.F1 -> letter('P')
            Key.F2 -> letter('Q')
            Key.F3 -> tildeK(13) // Not CSI R: that is a cursor position report.
            Key.F4 -> letter('S')
            Key.F5 -> tildeK(15)
            Key.F6 -> tildeK(17)
            Key.F7 -> tildeK(18)
            Key.F8 -> tildeK(19)
            Key.F9 -> tildeK(20)
            Key.F10 -> tildeK(21)
            Key.F11 -> tildeK(23)
            Key.F12 -> tildeK(24)
            Key.ESCAPE -> u(27)
            // Enter, Tab and Backspace stay legacy unless they have mods, an event type, or all keys are escapes.
            Key.ENTER, Key.TAB, Key.BACKSPACE -> {
                val code = when (key) { Key.ENTER -> 13; Key.TAB -> 9; else -> 127 }
                if (!all && tail.isEmpty()) key(DefaultModes, key, 0) else if (!all && action == KeyAction.RELEASE) null else u(code)
            }
            else -> u(57399 + key.ordinal - Key.KP_0.ordinal) // The keypad keys: Unicode private use codes.
        }
    }

    private fun kittyText(cp: Int, mods: Int, action: KeyAction, flags: Int): String? {
        val all = flags and KITTY_ALL_AS_ESCAPES != 0
        // Without "all keys as escapes", text with no Ctrl or Alt stays text, and its release sends nothing.
        if (!all && mods and (CTRL or ALT or SUPER) == 0) {
            return if (action == KeyAction.RELEASE) null else String(Character.toChars(cp))
        }
        if (action != KeyAction.PRESS && flags and KITTY_EVENT_TYPES == 0) {
            return if (action == KeyAction.RELEASE) null else kittyText(cp, mods, KeyAction.PRESS, flags)
        }
        // The key code is the char without Shift. Only letters show the Shift: the layout is not known here.
        val lower = Character.toLowerCase(cp)
        val m = if (lower != cp) mods or SHIFT else mods
        return "$CSI$lower${kittyMods(m, action, flags)}u"
    }

    /** A terminal in its default modes, with no screen: the legacy encoding of Enter, Tab and Backspace. */
    private object DefaultModes : TermCore {
        override val columns = 2
        override val rows = 1
        override val isAlt = false
        override val cursorX = 0
        override val cursorY = 0
        override val cursorVisible = true
        override val cursorShape = 0
        override val cursorBlink = false
        override val reverseVideo = false
        override val syncOutput = false
        override val newlineMode = false
        override val cursorKeysApp = false
        override val keypadApp = false
        override val bracketedPaste = false
        override val focusEvents = false
        override val mouseMode = 0
        override val mouseSgr = false
        override val keyboardFlags = 0
        override val title: String? = null
        override val workingDirectory: String? = null
        override val palette: Palette get() = error("no screen")
        override var cellWidth = 0
        override var cellHeight = 0
        override val scrolledRows = 0L
        override val historySize = 0
        override fun row(i: Int): Row = error("no screen")
        override fun hyperlink(id: Int): String? = null
        override fun feed(data: ByteArray, offset: Int, length: Int) {}
        override fun resize(newColumns: Int, newRows: Int) {}
        override fun text(startRow: Int, startCol: Int, endRow: Int, endCol: Int) = ""
        override fun screenText() = ""
        override fun paste(text: String) {}
    }

    // ---- Mouse ----

    /** A mouse button: left, middle, right, or the wheel. */
    enum class Button(val code: Int) { LEFT(0), MIDDLE(1), RIGHT(2), WHEEL_UP(64), WHEEL_DOWN(65) }

    enum class MouseAction { PRESS, RELEASE, MOTION }

    /**
     * The report for a mouse event at the cell (column, row, 0-based), or null if the mode does not report it.
     * [button] is null for a motion with no button down. mods uses SHIFT, ALT and CTRL.
     */
    fun mouse(t: TermCore, button: Button?, action: MouseAction, column: Int, row: Int, mods: Int = 0): String? {
        val mode = t.mouseMode
        if (mode == 0) return null
        if (action == MouseAction.MOTION) {
            if (mode == 1000) return null
            if (mode == 1002 && button == null) return null
        }
        var b = button?.code ?: 3
        if (action == MouseAction.MOTION) b += 32
        if (mods and SHIFT != 0) b += 4
        if (mods and ALT != 0) b += 8
        if (mods and CTRL != 0) b += 16
        val x = column + 1
        val y = row + 1
        if (t.mouseSgr) return "$CSI<$b;$x;$y${if (action == MouseAction.RELEASE) 'm' else 'M'}"
        // The X10 form: one byte each, and no button on a release. The text goes out as UTF-8, so a byte
        // above 127 would become 2 bytes: positions above 95 send nothing. Programs use SGR (1006) today.
        if (action == MouseAction.RELEASE) b = 3 or (b and (4 or 8 or 16))
        if (x > 95 || y > 95 || b > 95) return null
        return "${CSI}M${(32 + b).toChar()}${(32 + x).toChar()}${(32 + y).toChar()}"
    }

    /** Focus in and out (DECSET 1004). */
    fun focus(t: TermCore, focused: Boolean): String? = if (!t.focusEvents) null else if (focused) "${CSI}I" else "${CSI}O"
}

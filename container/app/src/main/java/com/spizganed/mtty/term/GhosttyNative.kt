package com.spizganed.mtty.term

import java.nio.ByteBuffer

/**
 * The calls into libghostty-vt through the JNI shim (`tools/libghostty/shim.c`). A handle is one terminal.
 * Call everything for one handle on one thread.
 */
internal object GhosttyNative {
    /** The shim calls these while it runs [nFeed] or [nResize], on the same thread. */
    interface Callbacks {
        fun write(data: ByteArray)
        fun bell()
        fun titleChanged()
        fun clipboard(data: ByteArray)
    }

    /** True when the shim library loads. The app has it as libmtty-vt.so. A JVM test sets -Dmtty.vt.host=PATH. */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("mtty-vt")
            true
        } catch (e: UnsatisfiedLinkError) {
            val path = System.getProperty("mtty.vt.host") ?: System.getenv("MTTY_VT_HOST")
            if (path == null) false else try { System.load(path); true } catch (e: UnsatisfiedLinkError) { false }
        }
    }

    @JvmStatic external fun nCreate(columns: Int, rows: Int, scrollbackLines: Long, callbacks: Callbacks): Long
    @JvmStatic external fun nFree(handle: Long)
    @JvmStatic external fun nFeed(handle: Long, data: ByteArray, offset: Int, length: Int)
    @JvmStatic external fun nResize(handle: Long, columns: Int, rows: Int)

    /** Rows [first] until first + count of [tag] (0 active screen, 3 history) into a direct buffer. Returns the bytes used, or -1. */
    @JvmStatic external fun nReadRows(handle: Long, tag: Int, first: Int, count: Int, buffer: ByteBuffer): Int
    @JvmStatic external fun nInfo(handle: Long, out: IntArray)
    @JvmStatic external fun nPalette(handle: Long, out: IntArray)
    @JvmStatic external fun nScrolled(handle: Long): Long
    @JvmStatic external fun nTitle(handle: Long): String?
    @JvmStatic external fun nPwd(handle: Long): String?
    @JvmStatic external fun nSetDefaults(handle: Long, colors: IntArray)
    @JvmStatic external fun nSetCell(handle: Long, width: Int, height: Int)
    @JvmStatic external fun nLink(handle: Long, id: Int): String?

    const val TAG_ACTIVE = 0
    const val TAG_HISTORY = 3
}

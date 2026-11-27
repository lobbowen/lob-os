package lobos.android.terminal
import lobos.android.terminal.TerminalBufferV2

import android.content.Context


object TerminalRuntime {

    const val SESSION_ID = "ui-terminal"

    val buffer = TerminalBufferV2()
    var connected: Boolean = false
        private set

    private val listeners = mutableListOf<() -> Unit>()

    fun subscribe(listener: () -> Unit) { synchronized(listeners) { listeners.add(listener) } }
    fun unsubscribe(listener: () -> Unit) { synchronized(listeners) { listeners.remove(listener) } }

    private fun notifyChanged() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    fun ensure(ctx: Context) {
        val existing = TerminalSessions.get(SESSION_ID)
        if (existing != null && existing.alive) return
        // V2 terminal runs Ubuntu bash from the rootfs; no V1 profile shims.
        TerminalSessions.close(SESSION_ID)
        buffer.feed(("lobos terminal · " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date()) + "\n").toByteArray(Charsets.UTF_8))
        runCatching { TerminalSessions.open(ctx, SESSION_ID) }
            .onSuccess { t ->
                connected = true
                t.onOutput = { d -> buffer.feed(d); notifyChanged() }
                t.onClosed = { _, _ -> connected = false; notifyChanged() }
                t.resize(24, 80)
            }
            .onFailure { e ->
                connected = false
                buffer.feed(("终端启动失败：${e.javaClass.simpleName}: ${e.message}\n").toByteArray(Charsets.UTF_8))
            }
        notifyChanged()
    }

    fun input(raw: String) {
        val t = TerminalSessions.get(SESSION_ID)
        if (t != null && t.alive) t.write(raw)
    }

      /** Encode a hardware key through the VT-aware key encoder and send to PTY. */
      fun sendKey(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
          val key = runCatching { com.spizganed.mtty.term.Key.valueOf(name) }.getOrNull() ?: return
          var mods = 0
          if (shift) mods = mods or com.spizganed.mtty.term.Keys.SHIFT
          if (alt) mods = mods or com.spizganed.mtty.term.Keys.ALT
          if (ctrl) mods = mods or com.spizganed.mtty.term.Keys.CTRL
          val bytes = com.spizganed.mtty.term.Keys.key(buffer.core, key, mods) ?: return
          input(bytes)
      }

    fun close() {
        TerminalSessions.close(SESSION_ID)
        connected = false
        notifyChanged()
    }
}

package lobos.services.app

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import lobos.kernel.ipc.PtySession

class TerminalSession private constructor(
    val session: PtySession.Session,

    val startedAtMs: Long,
) {

    @Volatile var exited: Boolean = false
        private set

    @Volatile var exitStatus: Int = -1
        private set

    @Volatile var exitSignal: Int = 0
        private set

    @Volatile var onOutput: ((ByteArray) -> Unit)? = null

    @Volatile var onClosed: ((status: Int, signal: Int) -> Unit)? = null

    fun write(text: String) = session.write(text)

    fun resize(rows: Int, cols: Int) = session.resize(rows, cols)

    fun close() = session.close()

    val alive: Boolean get() = !exited && !session.isClosed

    companion object {

        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        @Throws(IllegalStateException::class)
        fun start(
            ctx: Context,
            argv: List<String> = emptyList(),
            rows: Int = DEFAULT_ROWS,
            cols: Int = DEFAULT_COLS,
            timeoutMs: Long = 5_000,
        ): TerminalSession {

            val sh = lobos.services.supply.ShellEntry.require(ctx)

            val ptyBin = lobos.kernel.compat.Compat.ptyHost(ctx)
                ?: throw IllegalStateException(
                    "PTY 宿主不在（librivospty 缺失）—— 报缺，不猜（它逐字是那 APK 里那一份）",
                )

            val effArgv = if (argv.isEmpty()) listOf(sh.absolutePath, "-i") else argv
            val s = lobos.kernel.ipc.PtySession.openSession(ctx, ptyBin, effArgv, rows, cols, timeoutMs)
            val t = TerminalSession(s, System.currentTimeMillis())

            s.onData = { d -> t.onOutput?.invoke(d) }

            s.onExit = { status, signal ->
                t.exitStatus = status
                t.exitSignal = signal
                t.exited = true
                t.onClosed?.invoke(status, signal)
            }
            return t
        }
    }
}

object TerminalSessions {
    private val lock = Any()
    private val held = LinkedHashMap<String, TerminalSession>()

    fun open(ctx: Context, id: String, argv: List<String> = emptyList()): TerminalSession {
        val t = TerminalSession.start(ctx, argv)
        synchronized(lock) {
            held.put(id, t)?.let { old ->

                runCatching { old.close() }
            }
            held[id] = t
        }
        return t
    }

    fun list(): List<TerminalSession> = synchronized(lock) { held.values.toList() }

    fun get(id: String): TerminalSession? = synchronized(lock) { held[id] }

    fun close(id: String): Boolean {
        val t = synchronized(lock) { held.remove(id) } ?: return false
        runCatching { t.close() }
        return true
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val c = held.values.toList()
            held.clear()
            c
        }
        for (t in all) runCatching { t.close() }
    }
}

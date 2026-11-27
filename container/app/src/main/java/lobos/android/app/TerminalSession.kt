package lobos.android.app

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import lobos.system.exec.PtySession

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

    fun write(text: String): Boolean = session.write(text)

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

            val ptyBin = lobos.system.exec.Compat.ptyHost()
                ?: throw IllegalStateException("PTY 宿主不在（librivospty 缺失）")

            // V2: if Ubuntu rootfs + fastroot engine are ready, run the real
            // Ubuntu bash inside the PTY. PTY host execv()s argv[0] (the
            // launcher in nativeLibraryDir), which maps Ubuntu's ld.so + bash
            // with correct /proc/self/exe. Otherwise keep the legacy path.
            val ubuntuReady = lobos.android.systemdisk.UbuntuSystem.isReady(ctx)
            val (effArgv, sessionEnv, rawArgv) = if (ubuntuReady) {
                val base = lobos.android.systemdisk.UbuntuSystem.launchArgv(ctx, "/bin/bash", listOf("-i"))
                Triple(base, lobos.android.systemdisk.UbuntuSystem.environment(ctx) + mapOf(
                    "FR_EXE" to "/bin/bash",
                ), true)
            } else {
                  throw IllegalStateException("Ubuntu 系统盘未就绪，终端不可用")
            }
            val s = lobos.system.exec.PtySession.openSession(
                ptyBin, effArgv, rows, cols, timeoutMs,
                env = sessionEnv, rawArgv = rawArgv,
                cwd = lobos.android.systemdisk.UbuntuSystem.cwdHost(ctx),
            )
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

package lobos.runtime

import android.content.Context
import android.util.Log
import lobos.native.NativeAssetRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PtySession {

    private const val TAG = "PtySession"

    private const val F_OPEN = 1
    private const val F_READY = 2
    private const val F_DATA = 3
    private const val F_INPUT = 4
    private const val F_WINSIZE = 5
    private const val F_CLOSE = 6
    private const val F_EXITED = 7
    private const val F_EXIT = 8
    private const val F_ERROR = 9

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    class Session internal constructor(
        private val host: Host,
        val sid: Int,
        val slavePath: String,
        val initialRows: Int,
        val initialCols: Int,
    ) {
        private val closed = AtomicBoolean(false)

        @Volatile var onData: ((ByteArray) -> Unit)? = null

        @Volatile var onExit: ((status: Int, signal: Int) -> Unit)? = null

        @Volatile var exitStatus: Int = -1
            private set

        @Volatile var exitSignal: Int = 0
            private set

        internal fun recordExit(status: Int, signal: Int) {
            exitStatus = status
            exitSignal = signal
        }

        fun write(data: ByteArray) {
            if (closed.get()) return
            host.send(F_INPUT, sid, data)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) {
            if (closed.get()) return
            val b = java.nio.ByteBuffer.allocate(8)
            b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort(rows.toShort()).putShort(cols.toShort())
            b.putShort(0).putShort(0)
            host.send(F_WINSIZE, sid, b.array())
        }

        val isClosed: Boolean get() = closed.get()

        internal fun markClosed() { closed.set(true) }

        fun close() {
            if (closed.compareAndSet(false, true)) host.send(F_CLOSE, sid, ByteArray(0))
        }
    }

    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        private val proc: Process = pb.start()
        private val toHost = DataOutputStream(proc.outputStream)
        private val fromHost = DataInputStream(proc.inputStream)
        private val diag = StringBuilder()

        private val diagReader = Thread({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(diag) {
                        diag.append(line).append('\n')
                        if (diag.length > 8192) diag.setLength(0)
                    }
                }
            } catch (_: Exception) {
            }
        }, "lobos-pty-diag").apply { isDaemon = true; start() }

        private val sessions = ConcurrentHashMap<Int, Session>()

        private val ready = ConcurrentHashMap<Int, CountDownLatch>()

        @Volatile private var lastError: String = ""

        private val reader = Thread({ pump() }, "lobos-pty-reader").apply {
            isDaemon = true
            start()
        }

        fun start(argv: List<String>, rows: Int = DEFAULT_ROWS, cols: Int = DEFAULT_COLS,
                  timeoutMs: Long = 5_000): Session {
            if (argv.isEmpty()) throw IllegalArgumentException("argv 为空 —— 没有要执行的程序")
            val sid = allocSid()
            val latch = CountDownLatch(1)
            ready[sid] = latch
            val blob = ByteArrayOutputStream()
            for (a in argv) {
                blob.write(a.toByteArray(Charsets.UTF_8))
                blob.write(0)
            }
            send(F_OPEN, sid, blob.toByteArray())
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                ready.remove(sid)
                val d = diagnostics().trim()
                throw IllegalStateException(
                    "PTY 会话 ${sid} 在 ${timeoutMs}ms 内没就绪" +
                        (if (d.isNotEmpty()) "；librivospty 说：$d" else "（librivospty 无任何诊断输出）")
                )
            }
            val s = sessions[sid] ?: throw IllegalStateException("会话 $sid 建立后丢失")
            if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)
            return s
        }

        private fun allocSid(): Int {
            for (i in 0..255) if (!sessions.containsKey(i)) return i
            throw IllegalStateException("会话数已满（256）")
        }

        @Synchronized
        private fun send(kind: Int, sid: Int, payload: ByteArray) {
            try {
                val h = ByteArray(8)
                h[0] = kind.toByte(); h[1] = sid.toByte()
                h[2] = 0; h[3] = 0
                val len = payload.size
                h[4] = (len and 0xFF).toByte()
                h[5] = ((len shr 8) and 0xFF).toByte()
                h[6] = ((len shr 16) and 0xFF).toByte()
                h[7] = ((len shr 24) and 0xFF).toByte()
                toHost.write(h)
                if (payload.isNotEmpty()) toHost.write(payload)
                toHost.flush()
            } catch (e: Exception) {
                Log.w(TAG, "发帧失败 kind=$kind sid=$sid", e)
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        private fun pump() {
            try {
                while (true) {
                    val kind = fromHost.read()
                    if (kind < 0) break
                    val sid = fromHost.read()
                    val f1 = fromHost.read()
                    val f2 = fromHost.read()
                    if (f1 < 0 || f2 < 0) break
                    val b3 = fromHost.read(); val b4 = fromHost.read()
                    val b5 = fromHost.read(); val b6 = fromHost.read()
                    if (b3 < 0 || b4 < 0 || b5 < 0 || b6 < 0) break
                    val len = (b3.toInt() and 0xFF) or ((b4.toInt() and 0xFF) shl 8) or
                              ((b5.toInt() and 0xFF) shl 16) or ((b6.toInt() and 0xFF) shl 24)
                    val payload = if (len > 0) {
                        val buf = ByteArray(len)
                        fromHost.readFully(buf)
                        buf
                    } else ByteArray(0)

                    when (kind) {
                        F_READY -> onReady(sid, payload)
                        F_DATA -> onData(sid, payload)
                        F_EXITED -> onExited(sid, payload)
                        F_ERROR -> {
                            var n = payload.size
                            while (n > 0 && payload[n - 1] == 0.toByte()) n--
                            lastError = String(payload, 0, n, Charsets.UTF_8)
                            synchronized(diag) { diag.append("ERROR: ").append(lastError).append('\n') }
                            ctx?.let {
                                RuntimeDiagnostics.append(it, "pty", false, "PTY 原生侧报错", lastError)
                            }
                        }
                        else -> Log.d(TAG, "忽略未知帧 kind=$kind sid=$sid len=$len")
                    }
                }
            } catch (e: EOFException) {
            } catch (e: Exception) {
                Log.w(TAG, "读帧线程异常", e)
            } finally {
                for (s in sessions.values) s.markClosed()
                sessions.clear()
                for (l in ready.values) l.countDown()
            }
        }

        private fun onReady(sid: Int, payload: ByteArray) {
            val nul = payload.indexOf(0)
            if (nul < 0) { lastError = "READY 帧没有 slave 路径"; ready.remove(sid)?.countDown(); return }
            val slave = String(payload, 0, nul, Charsets.UTF_8)
            val rows: Int
            val cols: Int
            if (payload.size >= nul + 1 + 8) {
                val b = java.nio.ByteBuffer.wrap(payload, nul + 1, 8)
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                rows = b.short.toInt() and 0xFFFF
                cols = b.short.toInt() and 0xFFFF
            } else { rows = DEFAULT_ROWS; cols = DEFAULT_COLS }
            sessions[sid] = Session(this, sid, slave, rows, cols)
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {
            try { sessions[sid]?.onData?.invoke(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        private fun onExited(sid: Int, payload: ByteArray) {
            val s = sessions.remove(sid)
            var status = -1
            var signal = 0
            if (payload.size >= 8) {
                val b = java.nio.ByteBuffer.wrap(payload, 0, 8)
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                status = b.int
                signal = b.int
            }
            s?.recordExit(status, signal)
            s?.markClosed()
            try { s?.onExit?.invoke(status, signal) }
            catch (e: Exception) { Log.w(TAG, "EXITED 回调异常", e) }
        }

        fun diagnostics(): String = synchronized(diag) { diag.toString() }

        override fun close() {
            runCatching { send(F_EXIT, 0, ByteArray(0)) }
            runCatching { proc.destroy() }
            runCatching { toHost.close() }
            runCatching { fromHost.close() }
            for (s in sessions.values) s.markClosed()
            sessions.clear()
        }
    }

    @Volatile private var host: Host? = null
    private val hostLock = Any()

    fun openSession(
        ctx: Context,
        argv: List<String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        timeoutMs: Long = 5_000,
    ): Session = host(ctx).start(argv, rows, cols, timeoutMs)

    private fun host(ctx: Context): Host {
        host?.let { return it }
        synchronized(hostLock) {
            host?.let { return it }
            val bin = locateBin(ctx)
            val h = Host(ProcessBuilder(bin.absolutePath), ctx)
            host = h
            RuntimeDiagnostics.append(
                ctx, "pty", true, "PTY 会话宿主就位",
                bin.absolutePath + " （常驻；shell.exec 与终端共用）",
            )
            return h
        }
    }

    private fun locateBin(ctx: Context): File {
        val candidates = listOf(
            File(PrefixProvisioner.binDir(ctx), "pty-session"),
            File(ctx.applicationInfo.nativeLibraryDir, NativeAssetRegistry.libNameOf("ptysession")),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw IllegalStateException(
                "PTY 会话宿主不在位（找过：" +
                    candidates.joinToString(", ") { it.absolutePath } + "）—— " +
                    "shell.exec 与终端都依赖它。底座不完整，别静默退化。"
            )
    }

    fun probe(ctx: Context): Boolean = try {
        val h = host(ctx)
        val s = h.start(listOf("/system/bin/sh", "-c", "exit 0"), timeoutMs = 3_000)
        s.close()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "PTY 探针失败", e)
        false
    }

    fun runToCompletion(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
    ): Result {
        if (argv.isEmpty()) return Result(false, -1, "", "argv 为空", completed = false)
        val h = try {
            if (env.isEmpty() && cwd == null) host(ctx) else dedicatedHost(ctx, env, cwd)
        } catch (e: Throwable) {
            return Result(false, -1, "", e.message ?: e.javaClass.simpleName, completed = false)
        }
        val ownHost = env.isNotEmpty() || cwd != null
        return try {
            val s = h.start(argv, DEFAULT_ROWS, DEFAULT_COLS, timeoutMs.coerceAtLeast(1_000))
            val buf = java.io.ByteArrayOutputStream()
            val done = CountDownLatch(1)
            s.onData = { d -> synchronized(buf) { buf.write(d) } }
            s.onExit = { _, _ -> done.countDown() }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                s.close()
                return Result(false, -1, buf.toString(Charsets.UTF_8.name()), "超时 ${timeoutMs}ms", completed = false)
            }
            val out = buf.toString(Charsets.UTF_8.name())
            val code = s.exitStatus
            val sig = s.exitSignal
            if (sig != 0) {
                Result(false, 128 + sig, out, "被信号 $sig 终止（${signalName(sig)}）")
            } else if (code != 0) {
                Result(false, code, out, "退出码 $code")
            } else {
                Result(true, 0, out, null)
            }
        } catch (e: Throwable) {
            Result(false, -1, "", e.message ?: e.javaClass.simpleName, completed = false)
        } finally {
            if (ownHost) runCatching { h.close() }
        }
    }

    private fun signalName(sig: Int): String = when (sig) {
        1 -> "SIGHUP"; 2 -> "SIGINT"; 3 -> "SIGQUIT"; 9 -> "SIGKILL"
        11 -> "SIGSEGV"; 13 -> "SIGPIPE"; 15 -> "SIGTERM"
        else -> "信号 $sig"
    }

    data class Result(
        val ok: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String?,
        val completed: Boolean = ok || exitCode != -1,
    )

    private fun dedicatedHost(ctx: Context, env: Map<String, String>, cwd: File?): Host {
        val bin = locateBin(ctx)
        val pb = ProcessBuilder(bin.absolutePath)
        if (cwd != null) pb.directory(cwd)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        return Host(pb, ctx)
    }
}
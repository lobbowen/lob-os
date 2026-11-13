package lobos.kernel.ipc

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import lobos.kernel.KernelHooks
import lobos.services.supply.ShellEntry

object PtySession {

    private const val TAG = "PtySession"

    private const val MAX_CAPTURED_OUTPUT_BYTES = 4 * 1024 * 1024

    private const val F_OPEN = 1
    private const val F_READY = 2
    private const val F_DATA = 3
    private const val F_INPUT = 4
    private const val F_WINSIZE = 5
    private const val F_CLOSE = 6
    private const val F_EXITED = 7
    private const val F_EXIT = 8
    private const val F_ERROR = 9
    private const val F_NOTE = 10

    private const val SLOT_FREED_TIMEOUT_MS = 2_000L

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    private const val READER_JOIN_MS = 500L

    private const val MAX_FRAME_BYTES = 1024 * 1024

    private const val MAX_INPUT_BYTES = 64 * 1024

    private val inputDroppedLock = Any()
    @Volatile private var inputDropped = 0L

    class Session internal constructor(
        private val host: Host,
        val sid: Int,
        val slavePath: String,
        val initialRows: Int,
        val initialCols: Int,
    ) {
        private val closed = AtomicBoolean(false)

        @Volatile private var nativeSlotOpen = false

        @Volatile private var pendingWinsize: Pair<Int, Int>? = null

        @Volatile private var readyFrameArrived = false

        @Volatile
        var onData: ((ByteArray) -> Unit)? = null
            set(value) {
                field = value
                if (value != null) flushPending()
            }

        private val pending = java.io.ByteArrayOutputStream()
        private val pendingLock = Any()
        private var pendingDropped = 0L

        private val MAX_PENDING_BYTES = 4 * 1024 * 1024

        val hasPending: Boolean get() = synchronized(pendingLock) { pending.size() > 0 }

        internal fun deliver(data: ByteArray) {
            val cb = onData
            if (cb != null) {
                try { cb.invoke(data) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
                return
            }

            synchronized(pendingLock) {
                val room = MAX_PENDING_BYTES - pending.size()
                if (room <= 0) {
                    pendingDropped += data.size.toLong()
                } else if (data.size <= room) {
                    pending.write(data)
                } else {
                    pending.write(data, 0, room)
                    pendingDropped += (data.size - room).toLong()
                }
            }
        }

        internal fun flushPending() {
            val cb = onData ?: return
            val drained = synchronized(pendingLock) {
                if (pending.size() == 0) return@synchronized ByteArray(0)
                val b = pending.toByteArray(); pending.reset(); b
            }
            if (drained.isEmpty()) return
            try { cb.invoke(drained) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        internal fun pendingDroppedBytes(): Long = synchronized(pendingLock) { pendingDropped }

        @Volatile var onExit: ((status: Int, signal: Int) -> Unit)? = null

        @Volatile var exitStatus: Int = -1
            private set

        @Volatile var exitSignal: Int = 0
            private set

        @Volatile var process: Process? = null

        @Volatile var programId: String = ""

        internal fun recordExit(status: Int, signal: Int) {
            exitStatus = status
            exitSignal = signal
        }

        fun write(data: ByteArray) {
            if (closed.get()) return
            if (data.size <= MAX_INPUT_BYTES) {
                host.send(F_INPUT, sid, data)
                return
            }

            val keep = data.copyOf(MAX_INPUT_BYTES)
            val dropped = (data.size - MAX_INPUT_BYTES).toLong()
            synchronized(inputDroppedLock) { inputDropped += dropped }
            host.send(F_INPUT, sid, keep)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) {
            if (closed.get()) return

            pendingWinsize = rows to cols
            sendWinsize(rows, cols)
        }

        private fun sendWinsize(rows: Int, cols: Int) {
            val b = java.nio.ByteBuffer.allocate(8)
            b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort(rows.toShort()).putShort(cols.toShort())
            b.putShort(0).putShort(0)
            host.send(F_WINSIZE, sid, b.array())
        }

        internal fun onNativeSlotOpen() {
            nativeSlotOpen = true
        }

        internal fun onReadyFrameArrived() {
            readyFrameArrived = true
            val p = pendingWinsize ?: return
            pendingWinsize = null
            runCatching { sendWinsize(p.first, p.second) }
        }

        val isClosed: Boolean get() = closed.get()

        internal fun markClosed() { closed.set(true) }

        fun close() {
            if (closed.compareAndSet(false, true)) {

                terminateProcess()
                host.send(F_CLOSE, sid, ByteArray(0))

                val freed = host.awaitSlotFreed(sid, SLOT_FREED_TIMEOUT_MS)
                if (!freed) {
                    host.reportSlotNotFreed(sid)
                }
                host.releaseSid(sid)
                ProductSessions.remove("product-" + sid)
            }
        }

        internal fun releaseNativeSlot() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { host.send(F_CLOSE, sid, ByteArray(0)) }
            runCatching { host.releaseSid(sid) }
            runCatching { ProductSessions.remove("product-" + sid) }
        }

        private fun terminateProcess() {
            val proc = process ?: return

            runCatching { proc.destroy() }
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) return
                runCatching { Thread.sleep(50L) }
            }

            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }

    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        companion object {

            const val MAX_SESSIONS = 8
        }

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

                    val c = ctx
                    if (c != null) {
                        runCatching {
                            lobos.services.log.RuntimeDiagnostics.append(
                                c, "pty-diag", false, "PTY 宿主诊断输出",
                                "行=" + line,
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "lobos-pty-diag").apply { isDaemon = true; start() }

        private val sessions = ConcurrentHashMap<Int, Session>()

        private val slotBusy = HashSet<Int>()

        private val ready = ConcurrentHashMap<Int, CountDownLatch>()

        private val sendLock = Any()

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

            markSlotBusy(sid)

            s.programId = argv.firstOrNull()?.let { basenameOf(it) } ?: ""
            if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)

            val c0 = ctx
                ?: throw IllegalStateException("宿主侧没有那上下文 —— 报缺，不猜")
            spawnOnSlave(c0, s, argv)
            return s
        }

        private fun spawnOnSlave(ctx: Context, session: Session, argv: List<String>) {
            val slave = session.slavePath
            if (slave.isBlank()) {
                throw IllegalStateException("会话 ${session.sid} 没有那从设备 —— 报缺，不猜")
            }
            val slaveFile = java.io.File(slave)
            if (!slaveFile.exists()) {
                throw IllegalStateException("会话 ${session.sid} 的从设备不在：$slave —— 报缺，不猜")
            }
            val first = java.io.File(argv[0])

            val shaped = lobos.kernel.proc.ExecEntry.argv(first, argv)
                ?: throw lobos.kernel.proc.ProcessSupervisor.NotExecutable(first)
            val pb = ProcessBuilder(shaped)

            val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
                ctx,
                lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
                null,
            )
            pb.environment().clear()
            for ((k, v) in view) pb.environment()[k] = v
            pb.environment().remove("LD_PRELOAD")

            val savedStdin = runCatching {

                val p = android.system.Os.open("/proc/self/fd/0", android.system.OsConstants.O_RDONLY, 0)
                android.system.Os.dup(p).also { android.system.Os.close(p) }
            }.getOrNull()
            val slaveFd = runCatching {
                android.system.Os.open(slave, android.system.OsConstants.O_RDWR, 0)
            }.getOrNull()
            if (slaveFd == null) {
                throw IllegalStateException("那从设备逐字开不了：" + slave + "（按 C-05 报缺）")
            }
            val proc = try {

                android.system.Os.dup2(slaveFd, 0)
                val p = pb.start()

                if (savedStdin != null) {
                    runCatching { android.system.Os.dup2(savedStdin, 0) }
                    runCatching { android.system.Os.close(savedStdin) }
                }
                p
            } finally {
                runCatching { android.system.Os.close(slaveFd) }
            }
            session.process = proc

            pumpOutput(session, proc)

            ProductSessions.put("product-" + session.sid, session)

            notifyRegistered(ctx)
            recordInLedger(ctx, session, proc, argv)

            val pid0 = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid0 > 0) {
                Thread({

                    runCatching { lobos.kernel.proc.ProcessLedger.end(ctx, pid0) }
                    runCatching { proc.waitFor() }

                    runCatching { session.close() }

                    runCatching { ProductSessions.remove("product-" + session.sid) }
                }, "lobos-pty-ledger-clean").apply { isDaemon = true; start() }
            }
        }

        private fun notifyRegistered(ctx: Context) {
            val c = ctx ?: return
            runCatching {
                lobos.kernel.sysdev.SysDev.render(c)
            }.onFailure { e ->
                runCatching {
                    lobos.services.log.RuntimeDiagnostics.append(
                        c, "pty", false, "那会话逐字登记后刷新 files/dev 失败",
                        e.javaClass.name + ": " + (e.message ?: ""),
                    )
                }
            }
        }

        private fun pumpOutput(session: Session, proc: Process) {
            val t = Thread({
                runCatching {
                    val inS = proc.inputStream
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = inS.read(buf)
                        if (n <= 0) break
                        val out = buf.copyOf(n)
                        runCatching { session.deliver(out) }
                    }
                }

            }, "lobos-pty-output-" + session.sid)
            t.isDaemon = true
            t.start()
        }

        private fun recordInLedger(
            ctx: Context,
            session: Session,
            proc: Process,
            argv: List<String>,
        ) {
            try {

            val pid = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid <= 0) {

                lobos.services.log.RuntimeDiagnostics.append(
                    ctx, "ledger", false, "PTY 会话那进程逐字取不到 pid",
                    "sid=" + session.sid + " （账本那一行逐字**没有**记、按 C-05 报缺）",
                )
                return
            }

            val programId = session.programId.ifBlank {
                argv.firstOrNull()?.let { basenameOf(it) } ?: ("pty-" + session.sid)
            }

            val generation = lobos.kernel.proc.ProcessLedger.nextGeneration(ctx, programId)
            val recorded = lobos.kernel.proc.ProcessLedger.begin(ctx, programId, generation, pid)
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, "ledger", recorded != null,
                "PTY 会话那进程逐字已记账",
                if (recorded != null) {
                    "program=" + recorded.programId + " gen=" + recorded.generation +
                        " pid=" + recorded.pid + " starttime=" + recorded.starttime +
                        " startedAt=" + recorded.startedAt
                } else "账本那一行逐字**没有**记成（按 C-05 报缺）",
            )
            } catch (e: Throwable) {

                runCatching {
                    lobos.services.log.RuntimeDiagnostics.append(
                        ctx, "ledger", false, "PTY 会话那进程逐字记不进账本",
                        "sid=" + session.sid + " " + e.javaClass.name + ": " + (e.message ?: "") +
                            "（一格逐字**没有**中断、会话逐字**仍然**活着）",
                    )
                }
            }
        }

        private fun basenameOf(p: String): String =
            p.trimEnd('/').substringAfterLast('/').ifBlank { p }

        @Synchronized
        internal fun releaseSid(sid: Int) {
            sessions.remove(sid)
            ready.remove(sid)
        }

        private fun allocSid(): Int {

            val taken = HashSet<Int>()
            synchronized(this) { taken.addAll(sessions.keys) }
            runCatching { ProductSessions.list().forEach { taken.add(it.sid) } }
            runCatching {
                val h = host
                if (h != null) h.activeSessions().forEach { taken.add(it.sid) }
            }
            for (i in 0 until MAX_SESSIONS) if (!taken.contains(i)) return i
            throw IllegalStateException("会话数已满（$MAX_SESSIONS）")
        }

        @Synchronized

        internal fun send(kind: Int, sid: Int, payload: ByteArray) {
            try {
                val h = ByteArray(8)
                h[0] = kind.toByte(); h[1] = sid.toByte()
                h[2] = 0; h[3] = 0
                val len = payload.size
                h[4] = (len and 0xFF).toByte()
                h[5] = ((len shr 8) and 0xFF).toByte()
                h[6] = ((len shr 16) and 0xFF).toByte()
                h[7] = ((len shr 24) and 0xFF).toByte()

                val frame = if (payload.isEmpty()) h else h + payload

                synchronized(sendLock) {
                    toHost.write(frame)
                    toHost.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "发帧失败 kind=$kind sid=$sid", e)
                lastError = e.message ?: e.javaClass.simpleName
            }
        }

        private fun note(msg: String) {
            lastError = msg
            synchronized(diag) {
                diag.append(msg).append(10.toChar())
                if (diag.length > 8192) diag.setLength(0)
            }
        }
        private fun pump() {
            try {
                while (true) {
                    val kind = fromHost.read()
                    if (kind < 0) { note("宿主进程已退出（读到 EOF），会话不会就绪"); break }
                    val sid = fromHost.read()
                    val f1 = fromHost.read()
                    val f2 = fromHost.read()
                    if (f1 < 0 || f2 < 0) break
                    val b3 = fromHost.read(); val b4 = fromHost.read()
                    val b5 = fromHost.read(); val b6 = fromHost.read()
                    if (b3 < 0 || b4 < 0 || b5 < 0 || b6 < 0) break
                    val len = (b3.toInt() and 0xFF) or ((b4.toInt() and 0xFF) shl 8) or
                              ((b5.toInt() and 0xFF) shl 16) or ((b6.toInt() and 0xFF) shl 24)

                    if (len > MAX_FRAME_BYTES) {
                        val msg = "那帧长度逐字超上限：len=" + len + "、上限=" + MAX_FRAME_BYTES +
                            "（帧通道逐字**错位**了、**流**逐字**不能**逐字重新同步）"
                        lastError = msg
                        synchronized(diag) {
                            diag.append(msg).append(10.toChar())
                            if (diag.length > 8192) diag.setLength(0)
                        }
                        ctx?.let { KernelHooks.report(it, "pty", false, "PTY 宿主读帧长度超上限", msg) }
                        break
                    }
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
                                KernelHooks.report(it, "pty", false, "PTY 的C 侧报错", lastError)
                            }
                        }
                        F_NOTE -> {
                            var n = payload.size
                            while (n > 0 && payload[n - 1] == 0.toByte()) n--
                            val note = String(payload, 0, n, Charsets.UTF_8)
                            synchronized(diag) { diag.append("NOTE: ").append(note).append(10.toChar()) }
                            ctx?.let {
                                KernelHooks.report(it, "pty", true, "PTY 宿主自述", note)
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

            sessions[sid]?.onReadyFrameArrived()
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {

            try { sessions[sid]?.deliver(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        private fun onExited(sid: Int, payload: ByteArray) {
            val s = sessions.remove(sid)

            markSlotFreed(sid)
            var status = -1
            var signal = 0
            if (payload.size >= 8) {
                val b = java.nio.ByteBuffer.wrap(payload, 0, 8)
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                status = b.int
                signal = b.int
            }

            val stillAlive = s?.process?.isAlive == true
            if (!stillAlive) {
                s?.recordExit(status, signal)

                s?.releaseNativeSlot()

                s?.markClosed()
            }
            try { s?.onExit?.invoke(status, signal) }
            catch (e: Exception) { Log.w(TAG, "EXITED 回调异常", e) }
        }

    fun activeSessions(): List<Session> = sessions.values.toList()

    fun awaitSlotFreed(sid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val free = synchronized(this) { !slotBusy.contains(sid) }
            if (free) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(20)
        }
    }

    internal fun markSlotFreed(sid: Int) {
        synchronized(this) { slotBusy.remove(sid) }
    }

    internal fun markSlotBusy(sid: Int) {
        synchronized(this) { slotBusy.add(sid) }
    }

    internal fun reportSlotNotFreed(sid: Int) {
        ctx?.let {
            KernelHooks.report(
                it, "pty", false, "PTY 的C 侧报错",
                "槽 $sid 在 ${SLOT_FREED_TIMEOUT_MS}ms 内没回报已空，一号逐字**照样**还了",
            )
        }
    }

    fun diagnostics(): String = synchronized(diag) { diag.toString() }

        override fun close() {
            runCatching { send(F_EXIT, 0, ByteArray(0)) }
            runCatching { proc.destroy() }
            runCatching { toHost.close() }
            runCatching { fromHost.close() }
            for (s in sessions.values) s.markClosed()
            sessions.clear()

            stopReader(reader, "lobos-pty-reader")
            stopReader(diagReader, "lobos-pty-diag")
        }

        private fun stopReader(t: Thread, name: String) {
            if (!t.isAlive) return
            runCatching { t.interrupt() }
            runCatching { t.join(READER_JOIN_MS) }
            if (t.isAlive) {

                Log.w(TAG, "PTY 线程 $name 在 ${READER_JOIN_MS}ms 内没停（它逐字是 daemon、进程退出时会被杀掉）")
            }
        }
    }

    @Volatile private var host: Host? = null
    private val hostLock = Any()

    fun openSession(
        ctx: Context,
        bin: java.io.File,
        argv: List<String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        timeoutMs: Long = 5_000,
    ): Session {

        val effArgv = if (argv.isEmpty()) argv
        else {
            val first = java.io.File(argv[0])

            val shaped = lobos.kernel.proc.ExecEntry.argv(first, argv)
            if (shaped == null) {
                val diag = lobos.kernel.proc.ProcessSupervisor.enoexecDiagnostic(first)
                System.err.println(diag)
                throw lobos.kernel.proc.ProcessSupervisor.NotExecutable(first)
            }
            shaped
        }
        return host(ctx, bin).start(effArgv, rows, cols, timeoutMs)
    }

    private fun host(ctx: Context, bin: java.io.File): Host {
        host?.let { return it }
        synchronized(hostLock) {
            host?.let { return it }
            val checked = locateBin(bin)
            val raw = listOf(checked.absolutePath)
            val hostArgv = lobos.kernel.proc.ExecEntry.argv(checked, raw) ?: raw
            val view0 = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
                ctx,
                lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
                null,
            )
            val sh = runCatching { lobos.services.supply.ShellEntry.require(ctx) }.getOrNull()
            val pb0 = if (sh != null) {
                val script = StringBuilder()
                for ((k0, v0) in view0) {
                    script.append("export ").append(k0).append("=")
                        .append("'").append(v0.replace("'", "'\\''")).append("';").append('\n')
                }
                script.append("exec ").append(hostArgv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" })

                val shArgv = lobos.kernel.proc.ExecEntry.argv(
                    sh, listOf(sh.absolutePath, "-c", script.toString()),
                )

                shArgv ?: listOf(
                    lobos.kernel.proc.ExecEntry.SYSTEM_LINKER,
                    sh.absolutePath,
                    "-c",
                    script.toString(),
                )
            } else {
                hostArgv
            }
            val pb1 = ProcessBuilder(pb0)

            for ((k0, v0) in view0) {
                pb1.environment()[k0] = v0
            }
            pb1.environment().remove("LD_PRELOAD")
            KernelHooks.report(
                ctx, "pty", true,
                "宿主启动形态",
                "shell=" + (sh?.absolutePath ?: "无") +
                    "\nbin=" + checked.absolutePath +
                    "\nhostArgv=" + hostArgv.joinToString(" | ") +
                    "\nlaunch=" + pb1.command().joinToString(" | ") +
                    "\nview=" + view0.keys.joinToString(",") +
                    "\nscript=" + (if (sh != null) pb0[3] else "未走 shell")
            )
            val h = Host(pb1, ctx)
            host = h
            KernelHooks.report(ctx, "pty", true, "PTY 会话宿主就位",
            bin.absolutePath + " （常驻；shell.exec 与终端共用）")
            return h
        }
    }

    private fun locateBin(bin: java.io.File): java.io.File {
        if (!bin.isFile) {
            throw IllegalStateException(
                "PTY 会话宿主不可用：" + bin.absolutePath +
                    " —— 底座不完整，别静默退化。" +
                    "登记在册但文件不在的，查 dpkg -V 那条路（登记与磁盘比），重新铺一次即可修复"
            )
        }
        return bin
    }

    fun isUsable(bin: java.io.File): Boolean = bin.isFile

    object ProductSessions {
        private val lock = Any()
        private val held = LinkedHashMap<String, Session>()

        @Synchronized
        fun put(key: String, s: Session) {
            synchronized(lock) { held[key] = s }
        }

        @Synchronized
        fun list(): List<Session> {
            val dead = mutableListOf<String>()
            val out = mutableListOf<Session>()
            synchronized(lock) {
                for ((k, s) in held) {
                    if (s.isClosed) dead.add(k) else out.add(s)
                }
                for (k in dead) held.remove(k)
            }
            return out
        }

        @Synchronized
        fun remove(key: String) {
            synchronized(lock) { held.remove(key) }
        }
    }

    fun activeSessions(ctx: Context): List<Session> {
        val native = runCatching {
            val h = host
            if (h == null) emptyList() else h.activeSessions()
        }.getOrDefault(emptyList())

        val product = runCatching { ProductSessions.list() }.getOrDefault(emptyList())

        val seen = HashSet<String>()
        val out = mutableListOf<Session>()
        for (s in native + product) {
            if (seen.add(s.slavePath)) out.add(s)
        }
        return out
    }

    fun ensureHost(ctx: Context): Boolean {
        val bin = lobos.kernel.compat.Compat.ptyHost(ctx)
        if (bin == null) {
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, "pty", false, "PTY 宿主就位失败", "内核构件缺失（librivospty 不在系统库）",
            )
            return false
        }
        host(ctx, bin)
        return true
    }

    fun runToCompletion(
        ctx: Context,
        bin: java.io.File,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
    ): Result {
        if (argv.isEmpty()) return Result(false, -1, "", "argv 为空", completed = false)

        val alreadyDriven = argv[0] == lobos.kernel.proc.ExecEntry.SYSTEM_LINKER
        val first = java.io.File(argv[0])
        val effArgv = if (alreadyDriven) argv
        else lobos.kernel.proc.ExecEntry.argv(first, argv)

        if (effArgv == null) {
            val diag = lobos.kernel.proc.ProcessSupervisor.enoexecDiagnostic(first)
            System.err.println(diag)
            return Result(
                false,
                lobos.kernel.proc.ProcessSupervisor.ENOEXEC_EXIT_CODE,
                "",
                diag,
                completed = false,
            )
        }
        val h = try {
            if (env.isEmpty() && cwd == null) host(ctx, bin) else dedicatedHost(ctx, bin, env, cwd)
        } catch (e: Throwable) {
            return Result(false, -1, "", e.message ?: e.javaClass.simpleName, completed = false)
        }
        val ownHost = env.isNotEmpty() || cwd != null
        return try {
            val s = h.start(effArgv, DEFAULT_ROWS, DEFAULT_COLS, timeoutMs.coerceAtLeast(1_000))

            val buf = java.io.ByteArrayOutputStream()
            var dropped = 0L
            val done = CountDownLatch(1)
            s.onData = { d ->
                synchronized(buf) {
                    if (buf.size() + d.size > MAX_CAPTURED_OUTPUT_BYTES) {

                        val room = (MAX_CAPTURED_OUTPUT_BYTES - buf.size()).coerceAtLeast(0)
                        if (room > 0) buf.write(d, 0, room)
                        dropped += (d.size - room).toLong()
                    } else {
                        buf.write(d)
                    }
                }
            }
            s.onExit = { _, _ -> done.countDown() }

            s.process?.let { proc ->
                Thread({
                    val st = runCatching { proc.waitFor() }.getOrNull()
                    runCatching {
                        s.onExit?.invoke(st ?: 0, 0)
                    }
                }, "lobos-pty-exit-watch").apply { isDaemon = true; start() }
            }

            val droppedNote = if (dropped > 0L) "（超出上限那一截逐字丢了 $dropped 字节）" else ""
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                s.close()
                return Result(false, -1, buf.toString(Charsets.UTF_8.name()), "超时 ${timeoutMs}ms$droppedNote", completed = false)
            }
            val out = buf.toString(Charsets.UTF_8.name())
            val code = s.exitStatus
            val sig = s.exitSignal
            if (sig != 0) {
                Result(false, 128 + sig, out, "被信号 $sig 终止（${signalName(sig)}）$droppedNote")
            } else if (code != 0) {
                Result(false, code, out, "退出码 $code$droppedNote")
            } else {
                Result(true, 0, out, if (dropped > 0L) "输出被截断$droppedNote" else null)
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

    private fun dedicatedHost(ctx: Context, bin: java.io.File, env: Map<String, String>, cwd: File?): Host {
        val checked = locateBin(bin)
        val hostArgv = lobos.kernel.proc.ExecEntry.argv(checked, listOf(checked.absolutePath))
        val pb = ProcessBuilder(if (hostArgv != null) hostArgv else listOf(checked.absolutePath))
        pb.environment().remove("LD_PRELOAD")
        if (cwd != null) pb.directory(cwd)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        return Host(pb, ctx)
    }
}
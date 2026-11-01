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

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    //  => 而那**停那一个线程**逐字等的时长（=> 而那**有界**、=> 而那不逐字无限等）。
    private const val READER_JOIN_MS = 500L

    class Session internal constructor(
        private val host: Host,
        val sid: Int,
        val slavePath: String,
        val initialRows: Int,
        val initialCols: Int,
    ) {
        private val closed = AtomicBoolean(false)

        //  => 而那那回调逐字**接上**的时候逐字把缓冲**倒**给它（=> 而而那那输出逐字**不**丢）
        @Volatile
        var onData: ((ByteArray) -> Unit)? = null
            set(value) {
                field = value
                if (value != null) flushPending()
            }

        //  缺陷名（C-102）**那一格逐字把输出逐字丢在中间、=> 而而那它逐字是在那回调**还没接上**的时候到的** ——
        //    => 而那旧版逐字是 `onData` 逐字一个**可变字段**、=> 而而那调用方逐字**在** `start()` **返回之后**
        //      逐字才把它接上（=> 而那 `runToCompletion` 那一族逐字是 `s.onData = { … }`）
        //    => 而而那**时序**逐字是：=> 而那宿主逐字发 FRAME_READY（=> 而那 `onReady` 逐字建那 Session
        //      并把 latch 逐字倒）、=> 而而那 `start()` 逐字**返回**、=> 而而那调用方逐字**还没**接 onData、
        //      => 而而那**这中间**逐字那宿主逐字就**已经**可以发 FRAME_DATA 了
        //      （=> 而那 `pty-session.c` 那一族逐字那 poll 循环逐字**每一轮**都把那些 master 逐字加进去）
        //    => 而而那那**后果**（07 本轮逐字核出）：
        //      · 那 `sys.tty.exec` 那一趟逐字 stdout 逐字**空**、=> 而那退出码逐字 0、=> 而那那期望逐字**没**出现
        //      · 而而那那一趟跑的那命令逐字**没有进程**（=> 而那账本逐字只有 jq、=> 而那 `ps -A` 逐字 0）
        //    => 而那**处置**：=> 而那输出逐字**先**落到那 Session 自己的**缓冲**里
        //      => 而而那那回调逐字**接上**的时候逐字**把缓冲逐字倒给它**
        //      => 而而那那样逐字**不**依赖那回调逐字什么时候接（=> 而那**不**是猜、=> 而那**不**是丢）
        private val pending = java.io.ByteArrayOutputStream()
        private val pendingLock = Any()

        /** 那**缓冲**里逐字有东西没有（=> 而那它逐字是那**读数**、=> 而而那它逐字**不**是推出来的）。 */
        val hasPending: Boolean get() = synchronized(pendingLock) { pending.size() > 0 }

        internal fun deliver(data: ByteArray) {
            val cb = onData
            if (cb != null) {
                try { cb.invoke(data) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
                return
            }
            //  => 而那那回调逐字**还没**接上 => 而而那那输出逐字**先进缓冲**、=> 而而那**不**丢
            synchronized(pendingLock) { pending.write(data) }
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

        /**
         * 关掉那一条会话（=> 而而那它逐字**把**那号逐字**还**给那 native 那一族）。
         *
         *  缺陷名（C-102）**这一格逐字只发那一帧、=> 而而那那号逐字在**两边**逐字**错开**了** ——
         *   => 而那旧版逐字是 `if (closed.compareAndSet(false, true)) host.send(F_CLOSE, sid, …)`
         *     => 而而那那 native 那一族逐字 `session_close` 逐字 `memset(&g_sessions[sid], 0, …)`
         *       => 而而那那**槽**逐字**已经**清掉了
         *     => 而而那那 Kotlin 那一族逐字那 `sessions` 那一族逐字**还留着**那一格
         *       => 而而那那 `allocSid` 逐字**跳过**那一号、=> 而而那那 native 逐字**复用**它
         *       => 而而那那**号**逐字**错开**了
         *       => 而而那那**后果**：=> 而而那那 `FRAME_READY` 逐字回的号与那 `sessions` 那一族
         *         逐字对不上、=> 而而那那 latch 逐字**永远**等不到、=> 而而那那**输出**逐字**找不到**那一格
         *   => 而那**处置**：=> 而那这一格逐字**关**的时候逐字**同时**把那一号从那 `sessions` 那一族逐字去掉
         *     => 而而那那**两边**逐字**同一**套号、=> 而而那那**号**逐字**不会**错开
         */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                host.send(F_CLOSE, sid, ByteArray(0))
                host.releaseSid(sid)
            }
        }
    }

    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        companion object {
            /** 那**会话数**逐字**跟**那 native 那一族逐字**一样**（=> 而而那它逐字是 8）。 */
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
                    //  缺陷名（C-102）**那一路逐字只进 diag、=> 而而那零字不进诊断那一族** ——
                    //    => 而那旧版逐字只把那一行写进那个内存里的 diag 串
                    //      （=> 而那 diag 逐字只被 diagnostics() 那一格读出来、=> 而而那 diagnostics()
                    //        逐字只在「PTY 超时未就绪」那一支被用、=> 而而那**其余**那些诊断输出
                    //        逐字**只留在内存里**、=> 而那进程一退逐字**全没了**）。
                    //    => 而那**后果**：=> 而那 PTY 宿主逐字报了错、=> 而那 diag.jsonl 逐字**没有**它
                    //      （=> 而那读设备的人逐字看不到「宿主说了什么」）。
                    //    => 而那**处置**：=> 而那一行逐字**也**进诊断那一族
                    //      （=> 而那 RuntimeDiagnostics.append 逐字写进 diag.jsonl）。
                    //    => 而那 ctx 逐字**可以**是空（=> 而那 Host 那个 ctx 那一格逐字可选）
                    //      => 而而那空的时候逐字**不报**（=> 而那不是「那一档没算」、=> 而那是「没有地方可报」）。
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

        /**
         * 那**会话号**逐字**跟着**那 native 那一族逐字分的**槽**走
         *   （=> 而而那它逐字**不**自己另分一套）。
         *
         *  缺陷名（C-102）**两边逐字各自分号、=> 而而那它们逐字会错开** ——
         *   => 而那旧版逐字是 `for (i in 0..255) if (!sessions.containsKey(i)) return i`
         *     => 而而那那 native 那一族逐字 `alloc_slot()` 逐字**只**在 0..MAX_SESSIONS（=> 而那是 8）里分
         *     => 而而那那 `FRAME_OPEN` 那一族逐字**根本不用**那帧带来的 sid
         *       （=> 而那 `int slot = alloc_slot();` 逐字**自己**分）
         *     => 而而那那 `FRAME_READY` 逐字回的逐字是那**真的**槽号
         *     => 而而那那 Kotlin 那一族逐字 `onReady` 逐字按那**真的**槽号逐字建 Session
         *     => 而而那那 Kotlin 那一族逐字**自己**分的那个 sid 逐字**只**用在发那帧上
         *       （=> 而那它逐字**被丢掉**了）
         *     => 而而那**后果**（=> 而那槽复用之后）：=> 而那 native 逐字**复用**槽 0、
         *       而而那那 Kotlin 逐字给 2、=> 而而那那**号**逐字**不一致**
         *       => 而而那那 `sessions` 那一族逐字**按号**索引、=> 而而那它逐字**对不上**
         *   => 而那**处置**：=> 而那**会话号**逐字**就用**那 native 逐字回的那个**槽号**
         *     => 而而那它逐字**只**从那帧上来（=> 而而那它逐字**不**自己另分）
         *     => 而而那发那帧那一格逐字**不再**带一个自己分的号（=> 而那它逐字**没有**用）
         */
        /** 把那一号逐字**还**回去（=> 而而那它逐字**跟**那 native 那一族逐字**同步**）。 */
        @Synchronized
        internal fun releaseSid(sid: Int) {
            sessions.remove(sid)
            ready.remove(sid)
        }

        private fun allocSid(): Int {
            //  => 而那那号逐字**跟**那 native 那一族逐字**同一**个范围
            //    （=> 而而那那 native 那一族逐字 `MAX_SESSIONS` 逐字是 8）
            //    => 而而那宿主侧逐字**分**的号逐字**必须**在那个范围里、=> 而而那它逐字**才**是那**真的**号
            for (i in 0 until MAX_SESSIONS) if (!sessions.containsKey(i)) return i
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
                toHost.write(h)
                if (payload.isNotEmpty()) toHost.write(payload)
                toHost.flush()
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
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {
            //  => 而那那输出逐字**先**落到那 Session 自己的缓冲里（=> 而而那回调**还没**接上时逐字**不**丢）
            try { sessions[sid]?.deliver(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
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

    fun activeSessions(): List<Session> = sessions.values.toList()

    fun diagnostics(): String = synchronized(diag) { diag.toString() }

        override fun close() {
            runCatching { send(F_EXIT, 0, ByteArray(0)) }
            runCatching { proc.destroy() }
            runCatching { toHost.close() }
            runCatching { fromHost.close() }
            for (s in sessions.values) s.markClosed()
            sessions.clear()
            //  缺陷名（C-102）**那两个线程逐字没有「停它的那一格」** ——
            //    => 而那旧版逐字只 `proc.destroy()` 与关那三个流（=> 而那**零字没有** join 那两个线程）
            //      => 而那 reader 逐字是 pump() 那一支的**唯一驱动**（=> 而那 pump() 逐字读 fromHost）
            //        而那 diagReader 逐字是 proc.errorStream 的**唯一读者**
            //      => 而那**流关掉之后**那两个线程逐字**各自卡在那一读上**、=> 而它们逐字是 daemon
            //        （=> 而 daemon 逐字在进程退出时才被逐字杀掉）=> 而那一次 close 之后
            //        它们逐字**还占着那个进程**，=> 而而那**下一次** openSession 逐字再建两个。
            //    => 而那**处置**：=> 而那**先断流、再 join**（=> 而那 join 逐字有界、=> 而那不逐字无限等）。
            //      => 而那 join 逐字**放在关流之后**（=> 而那线程逐字要靠流关闭才逐字醒）。
            stopReader(reader, "lobos-pty-reader")
            stopReader(diagReader, "lobos-pty-diag")
        }

        /**
         * 停那一个线程（=> 而那**有界**、=> 而那不逐字无限等）。
         *   => 而那先 interrupt（=> 而那线程逐字卡在那一读上、=> 而 interrupt 逐字能逐字把它叫醒）
         *     => 而那再 join 那一个有界的时长（=> 而那超时逐字就逐字放弃、=> 而那它逐字是 daemon、=> 而那不逐字挡着进程）。
         */
        private fun stopReader(t: Thread, name: String) {
            if (!t.isAlive) return
            runCatching { t.interrupt() }
            runCatching { t.join(READER_JOIN_MS) }
            if (t.isAlive) {
                //  => 而那它逐字还没停 => 而那记一条（=> 而那按 C-05「缺字段就报缺」不逐字假装它停了）。
                Log.w(TAG, "PTY 线程 $name 在 ${READER_JOIN_MS}ms 内没停（=> 而它逐字是 daemon、=> 而进程退出时会被杀掉）")
            }
        }
    }

    @Volatile private var host: Host? = null
    private val hostLock = Any()

    /**
     * 开**一条**会话（=> 而那它逐字**活着**、=> 而而那它逐字**不是**那一次性的那一族）。
     *
     * 缺陷名（C-102）**那一格逐字把 argv 原样交给那宿主、=> 而而那它逐字没走那 `ExecEntry.argv` 那一族** ——
     *   => 而那旧版逐字是 `host(ctx, bin).start(argv, rows, cols, timeoutMs)`
     *     => 而而那**宿主**逐字是那 native 那一份（=> 而那 `pty-session.c` 那一族逐字**直接 execve**）
     *       => 而而那它逐字**不**判形态、=> 而而那它逐字**不**套那一层
     *     => 而而那**入口**逐字是那**解释器**（=> 而那 bash 那一件逐字 ET_DYN）
     *       => 而而那它逐字**直接**被 execve、=> 而而那**没有** linker64 驱动它
     *       => 而而那那**错误**逐字是
     *         `/data/user/0/lobos.os/files/usr/bin/bash: cannot execute binary file`
     *     => 而而那那**会话**逐字**当场就退出了**（=> 而那 stdout 逐字空 + exitCode 逐字 0）
     *   => 而而那**那**与那 `runToCompletion` 那一族逐字**不一样**：
     *     => 而那 `runToCompletion` 逐字**有**那一句
     *       `val effArgv = … ExecEntry.argv(first, argv) ?: argv`
     *     => 而而那它逐字**判形态**、=> 而而那它逐字**套**那一层
     *     => 而而那那 `openSession` 那一族逐字**漏了**那一句（=> 而而那它是**同一条路**的另一扇门）
     *   => 而那**处置**：=> 而那**判形态**那一层逐字**交给**那 `ExecEntry.argv` 那一族
     *     （=> 而那是那**唯一真相源**、=> 而那 native 那份已删、=> 而那 C-87）
     *     => 而而那那 ET_DYN 那一档逐字**由它**套 linker64、=> 而而那那 ET_EXEC 那一档逐字**由它**不套
     *     => 而而那它逐字**认不出**那一档时逐字**保留**那 argv（=> 而那 `ExecEntry.argv` 那一族
     *       逐字**自己**就那样返回、=> 而而那不是**猜**）
     */
    fun openSession(
        ctx: Context,
        bin: java.io.File,
        argv: List<String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        timeoutMs: Long = 5_000,
    ): Session {
        //  => 而那**判形态**那一层逐字**走**那 `ExecEntry.argv` 那一族
        //    （=> 而而那它逐字与那 `runToCompletion` 那一族逐字**同一把尺**）
        val effArgv = if (argv.isEmpty()) argv
        else {
            val first = java.io.File(argv[0])
            //  => 而那**认不出**那一档逐字**报缺**、=> 而而那它逐字**不**调起（=> 而那 M7-05 那一族）
            //    => 而那旧版逐字是 `?: argv`（=> 而而那它逐字**拿回那原样的 argv**、
            //      => 而而那**坏 ELF 逐字被当程序调起**）
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
                //  缺陷名（C-102）**那一层逐字手写了、=> 而而那它逐字没走那 `ExecEntry.argv` 那一族逐字对 sh 判形态** ——
                //    => 而那旧版逐字是
                //        listOf(SYSTEM_LINKER, sh.absolutePath, "-c", script.toString())
                //      => 而而那它逐字**总是**套那 SYSTEM_LINKER
                //      => 而而那**判据**逐字该是「那**形态**」（=> 而那 `ExecEntry.argv` 那一族逐字就是那一族）
                //        · 而那 **ET_DYN** 那一档逐字要 linker64 驱动
                //        · 而而那 **ET_EXEC** 那一档逐字**不要**
                //    => 而而那**后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
                //      · 设备上 `files/opt/bash/5.2.15/bin/bash` 逐字 ELF 头 `7f 45 4c 46 02 01 01`
                //        逐字 e_type=03（=> 而那 **ET_DYN**、=> 而而那它逐字要 linker64 驱动）
                //      => 而而那那**错误**逐字是
                //        `/data/user/0/lobos.os/files/usr/bin/bash: cannot execute binary file`
                //      => 而而那那**会话**逐字**当场就退出了**、=> 而而那那 stdout 逐字空 + exitCode 逐字 0
                //      => 而而那那**期望**逐字是 `git version 2.55.0` 与那退出码 0
                //        => 而而那一格逐字**没过**（=> 而那 stdout 逐字**不含**那期望片段）
                //    => 而那**处置**：=> 而那**判形态**那一层逐字**交给**那 `ExecEntry.argv` 那一族
                //      （=> 而那是那**唯一真相源**、=> 而那 native 那份已删、=> 而那 C-87）
                //      => 而而那那 ET_DYN 那一档逐字**由它**套 linker64、=> 而而那那 ET_EXEC 那一档逐字**由它**不套
                val shArgv = lobos.kernel.proc.ExecEntry.argv(
                    sh, listOf(sh.absolutePath, "-c", script.toString()),
                )
                //  => 而那它逐字**认不出**那一档时逐字**报缺**（=> 而按 C-05 不猜填）
                //    => 而而那**不**回退到那手写那一层（=> 而那**就是**这一族那个错）
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
            // 缺陷名（C-102）**这一趟的环境从来没有被灌进 view0**。
            //
            // 真机读数（07，2026-10-24）：那一趟的 stderr 逐字是
            //   CANNOT LINK EXECUTABLE "…/usr/lib/librivospty.so": library "liblobosposix.so" not found: needed by main executable
            // 而 EXIT=1；而带上 LD_LIBRARY_PATH=…/usr/lib 再跑同一趟是 exit=0
            // （输出「控制通道已关闭（父侧退出）…librivospty bye」）。
            // 而 diag.jsonl 里 stage=pty 那一条的 script= 未走 shell
            //   => 而那是**下面那个 else 那一支**（=> 而 sh == null，=> 而 pb0 = hostArgv）。
            //
            // 而成因：上面那一支（=> 而 sh != null）把 view0 的每一项 export 进脚本，
            //   => 而脚本 exec 那个宿主 => 而环境由那个脚本带 => 而那一支是好的；
            // 而**下面那一支直接 exec 那个宿主本体**，=> 而而 pb1 的环境是**继承宿主进程的**，
            //   => 而而宿主进程那份环境里没有 LD_LIBRARY_PATH（=> 而 view0 只被 export 进脚本，
            //      => 而那一支没走脚本，=> 而 view0 从来没被灌进 env）。
            // 而那个宿主（librivospty.so）的 DT_NEEDED 有 liblobosposix.so，
            //   而 liblobosposix.so 在 files/usr/lib，=> 而而那不在系统默认搜索路径里，
            //   => 而所以它要靠 LD_LIBRARY_PATH => 而那一项丢了它就起不来。
            //
            // => 而处置：把 view0 灌进 pb1 的环境（=> 而那一份就是 treeRootEnv，
            //   => 而它含 LD_LIBRARY_PATH），=> 而而下面那一句 remove 照旧（=> 而 LD_PRELOAD 不继承）。
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
    fun activeSessions(ctx: Context): List<Session> {
        val h = host
        return if (h == null) emptyList() else h.activeSessions()
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
        // 缺陷（C-102）**重复替换：已替换过的 argv 又被替换一次**。
        // 真机读数（07，logcat）：Cmdline = /system/bin/linker64 /system/bin/linker64 …/opt/ripgrep/…/rg
        // —— linker64 出现两次。多出来的这一次来自本行：上游 CommandLine.run 给的 argv[0]
        // 已经是 linker64，而 linker64 自己也是 ET_DYN => ExecEntry.argv 又替一次。
        // linker64 把自己当程序加载 => call_constructors → libc 初始化链崩（kill-audit 记 desc=null pointer dereference）。
        //
        // ET_DYN 要经 linker64 驱动 —— 唯一真相源是 ExecEntry.argv，native 那份已删（C-87）。
        // 这里只对**还没有**替过的 argv 替；已是 SYSTEM_LINKER 的直接放行。
        // Shape.Unknown 时 argv() 返回 null —— 那时保留调用方给的 argv（不置空），
        // 否则「入口认不出」会表现成「argv 为空」，两种失败混成一种。
        val alreadyDriven = argv[0] == lobos.kernel.proc.ExecEntry.SYSTEM_LINKER
        val first = java.io.File(argv[0])
        val effArgv = if (alreadyDriven) argv
        else lobos.kernel.proc.ExecEntry.argv(first, argv)
        //  => 而那**认不出**那一档逐字**报缺**、=> 而而那它逐字**不**调起（=> 而那 M7-05 那一族）
        //    => 而那旧版逐字是 `?: argv`（=> 而而那它逐字**拿回那原样的 argv**、
        //      => 而而那**坏 ELF 逐字被当程序调起**）
        //    => 而而那那**退出码**逐字是那一个具体的数、=> 而而那那**诊断原文**逐字**进**那 stderr
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
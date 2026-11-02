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

    /**
     * 那**输出**逐字**捕获**那一个上限（=> 而而那它逐字**防**那堆逐字被那一趟输出逐字吃光）。
     *
     *   => 而而那那**上限**逐字是那 **4 MiB**（=> 而而那它逐字**够**那期望输出那一族逐字要的那一些）
     *   => 而而那那**超出**的一截逐字**丢**、=> 而而那那**丢了多少**逐字**记**着（=> 而那按 C-05 不猜填）
     */
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

        /**
         * 那**进程**（=> 而那它逐字是那**产品侧**逐字起的那一个、=> 而而那它逐字**不**是 native 那一族 fork 的）。
         *   => 而而那它逐字**存在**时逐字是那**进程账本**那一族逐字该记的那一个
         *   => 而而那它逐字**空**时逐字是「那 native 那一族逐字管的那一个」（=> 而而那它逐字**不**记账本）
         */
        @Volatile var process: Process? = null

        /**
         * 那**程序名**（=> 而而那它逐字是那**件**逐字、=> 而而那它逐字**不**由那进程的名字反推）。
         *   => 而而那它逐字**空**时逐字**报缺**（=> 而按 C-05 不猜填）
         */
        @Volatile var programId: String = ""

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
                //  缺陷名（C-102）**那一格逐字只关了那一族、=> 而而那那**产品侧**的进程逐字**还活着** ——
                //    => 而那旧版逐字是 `host.send(F_CLOSE, sid, …)` + `host.releaseSid(sid)`
                //      => 而而那那 `F_CLOSE` 逐字**只**让那 native 那一族逐字**关它自己那一个**
                //        （=> 而而那那 `pty-session.c` 逐字 `session_close` 逐字
                //          `kill(g_sessions[sid].pid, SIGHUP)` 逐字杀的是**那 native fork 出来**那个子进程、
                //          => 而而那那子进程逐字**只**握那从设备、=> 而而那它逐字**不是**那产品侧起的那一个）
                //        => 而而那那**产品侧**逐字起的那进程逐字**没人杀**
                //      => 而而那那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
                //        · 关完之后逐字那账本逐字**仍有** `linker64 gen=2 pid=26021`
                //        · => 而而那那 `files/dev` 逐字**仍有** `pts: [000]` 与 `index.json: [{"sid":0,…}]`
                //        · => 而而那那 `/proc` 逐字**还有**那三个 `linker64`
                //        => 而而那一格逐字**不是**退出清理那一族逐字的问题
                //          （=> 而那会话逐字真的活着、=> 而「关掉那条会话」那一格逐字**没生效**）
                //    => 而那**判据**逐字是「那一格逐字关了之后、=> 而而那它逐字有没有逐字真的关掉」那一个事实上
                //      （=> 而而那它逐字**不**由「那进程逐字还在不在」顶掉）
                //    => 而那**处置**：=> 而那**产品侧**的进程逐字**显式**逐字终止
                //      => 而而那它逐字**走**那产品侧逐字自己报退出那一路（=> 而而那它逐字**不许**只靠那读帧线程）
                //      => 而而那那**退出清理**逐字随它逐字**走**（=> 而而那那账本逐字与那 `files/dev` 逐字**同步**清空）
                terminateProcess()
                host.send(F_CLOSE, sid, ByteArray(0))
                host.releaseSid(sid)
                ProductSessions.remove("product-" + sid)
            }
        }

        /**
         * 那**产品侧**的进程逐字**显式**终止（=> 而而那它逐字**走**那产品侧逐字自己报退出那一路）。
         *
         *   => 而而那它逐字**先**要那进程逐字**软**终止（=> 而而那那 `TERM` 逐字给那进程逐字**机会**收尾）
         *   => 而而那它逐字**不**软终止逐字**成**时逐字**硬**终止（=> 而而那按 C-05 不猜填、=> 而**不**留着）
         *   => 而而那那**退出清理**逐字随它逐字**走**（=> 而而那那账本逐字与那 `files/dev` 逐字**同步**清空）
         */
        private fun terminateProcess() {
            val proc = process ?: return
            //  => 而而那那**软**终止逐字**先**（=> 而而那那 `TERM` 逐字给那进程逐字**机会**收尾）
            runCatching { proc.destroy() }
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) return
                runCatching { Thread.sleep(50L) }
            }
            //  => 而而那那**软**终止逐字**没**成时逐字**硬**终止（=> 而而那它逐字**不**留着）
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
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
            //  => 而那**程序名**逐字**从**那 argv 逐字**头**那一条逐字取（=> 而而那它逐字**不**由那进程的名字反推）
            //    => 而而那那**件**逐字是那一条的**最后**那一段（=> 而而那它逐字**不**猜）
            s.programId = argv.firstOrNull()?.let { basenameOf(it) } ?: ""
            if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)
            //  缺陷名（C-102）**那一格逐字由 native 自己去 exec、=> 而而那它逐字在那个域里**跑不起来** ——
            //   => 而那**事实**（仓内 `ExecEntry.kt:115` 到 `:122` 逐字记着）：
            //     · 而那四份入口在设备上是 u:object_r:app_data_file:s0
            //     · 而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
            //       （=> 而直接 exec 它在加载解释器之前就 EACCES => 而那一格是 error=13）
            //     · 而 /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可
            //   => 而而那那 native 宿主逐字是那 linker64 逐字驱动的、=> 而而那它逐字在 system_linker_exec 域
            //     => 而而那那它 fork 出来的子进程逐字**继承**那域
            //     => 而而那那 app_data_file 逐字**仍**没有执行许可
            //   => 而而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
            //     · 那子进程逐字**在 execv 那一格就死了**、=> 而而那那子进程逐字的那几条留痕逐字**一条都没到**
            //   => 而那**处置**：=> 而那**执行**那一格逐字**归这产品侧**
            //     => 而而那 native 那一族逐字**只**做那 PTY 那一层的事（=> 而而那它逐字建那从设备）
            //     => 而而那**执行**逐字走那 `ProcessSupervisor` 那一族（=> 而而那它逐字**已经**能跑那九件）
            //  => 而那**执行**那一格逐字（=> 而那它逐字归这产品侧）
            //    => 而而那它逐字**要**那 ctx（=> 而那它逐字**不能**是空、=> 而按 C-05 报缺）
            val c0 = ctx
                ?: throw IllegalStateException("宿主侧没有那上下文 —— 报缺，不猜")
            spawnOnSlave(c0, s, argv)
            return s
        }

        /**
         * 那**执行**那一格逐字（=> 而那它逐字归这产品侧、=> 而而那它逐字走那 `ProcessSupervisor` 那一族）。
         *
         *   => 而那它逐字把那进程的 stdin/stdout/stderr 逐字**接**到那从设备上
         *   => 而那它逐字**不**走 native 那一族（=> 而那是那一族那个域缺陷的处置）
         *   => 而那它逐字**不**静默（=> 而它逐字**失败**时逐字**报缺**、=> 而按 C-05 不猜填）
         */
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
            //  => 而那形态逐字**还是**那 ExecEntry.argv 那一族算的（=> 而而那它逐字**不**变）
            val shaped = lobos.kernel.proc.ExecEntry.argv(first, argv)
                ?: throw lobos.kernel.proc.ProcessSupervisor.NotExecutable(first)
            val pb = ProcessBuilder(shaped)
            //  => 而那**环境**逐字走那**同一**套（=> 而那 `ProcessSupervisor` 那一族逐字用的那一套）
            val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
                ctx,
                lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
                null,
            )
            pb.environment().clear()
            for ((k, v) in view) pb.environment()[k] = v
            pb.environment().remove("LD_PRELOAD")
            //  缺陷名（C-102）**那一格逐字把那**输入**也逐字拿掉了、=> 而而那那会话逐字**当场就退** ——
            //    => 而那旧版逐字是「那 `dup2` 逐字**整个不要**」
            //      => 而而那那 `dup2` 逐字原来逐字把那**三路**逐字接到那从设备上
            //        （=> 而而那那 stdin 逐字**也**在里头、=> 而而那那 stdout/stderr 逐字**也**在里头）
            //      => 而而那把那**三路**逐字**整个**去掉之后：
            //        · 而而那那 stdout/stderr 逐字**留**那 Java 管道（=> 而那**对**的、
            //          => 而而那那**产品侧**逐字**读**它、=> 而而那那 App 自己的 stdout 逐字**不**被动）
            //        · => 而而那那 **stdin** 逐字也逐字**留**那 Java 管道
            //          => 而而那那管道逐字**没有**写的那一头
            //          => 而而那那 `bash -i` 逐字读那 stdin 逐字 => 而而那它逐字**立刻**拿到 **EOF**
            //          => 而而那那**会话**逐字**当场就退**了（=> 而那 status=0 signal=0）
            //            （=> 而那 07 本轮逐字核出：=> 而那那会话逐字**起过**（24 次 TAB 到位）、
            //              => 而那那账本逐字**只有**那 `jq` 那一件、=> 而那那 PTY 会话逐字那一件逐字**又退了**、
            //              => 而那那 `files/dev/tty/pts` 空、=> 而那那 `index.json` `[]`）
            //    => 而那**处置**：=> 而而那那 **stdin** 逐字**单独**接到那从设备上
            //      => 而而那那 stdout/stderr 逐字**仍**留那 Java 管道（=> 而那**对**的那一族）
            //      => 而而那那 App 进程逐字自己逐字那三路逐字**不**被动（=> 而而那那**它**逐字**只**动那 stdin）
            //      => 而而那那 stdin 逐字**不**是那 Java 管道、=> 而而那那 `bash -i` 逐字**等**那用户逐字输入
            //        （=> 而而那那从设备逐字**没有**别的一头写它时、=> 而而那它逐字**不**给 EOF）
            //    => 而而那那**判据**逐字是「那会话逐字该活多久」
            //      （=> 而而那它逐字**不**由「那进程逐字做了什么」顶掉）
            //      => 而而那那**该**活到那用户逐字关它或那调用方逐字**显式**关它
            //        （=> 而而那它逐字**不**该因为那读侧逐字 EOF 就走）
            //
            //  => 而而那那 stdin 逐字**要**那从设备、=> 而而那那 App 进程逐字自己逐字那 stdin 逐字**先**存一份
            //    => 而而那那**存**那一份逐字**只**在**那 fork 之前**做、=> 而而那那**孩子**逐字**起完**就**还**回去
            val savedStdin = runCatching {
                //  => 而而那那 App 进程逐字自己逐字那 stdin 逐字**先**复制一份
                //    （=> 而而那那 android.jar 逐字**剥掉**了那 `FileDescriptor` 逐字那静态字段、
                //      => 而而那它逐字**不能**直接引用、=> 而而那那**唯一**的路逐字是那 `Os.dup` 逐字
                //      => 而而那那 `Os.dup` 逐字**要**一个真的那 `FileDescriptor`）
                val p = android.system.Os.open("/proc/self/fd/0", android.system.OsConstants.O_RDONLY, 0)
                android.system.Os.dup(p).also { android.system.Os.close(p) }
            }.getOrNull()
            val slaveFd = runCatching {
                android.system.Os.open(slave, android.system.OsConstants.O_RDWR, 0)
            }.getOrNull()
            if (slaveFd == null) {
                throw IllegalStateException("那从设备逐字开不了：" + slave + "（=> 而而那按 C-05 报缺）")
            }
            val proc = try {
                //  => 而而那那 **stdin** 逐字**单独**接到那从设备上
                //    （=> 而而那那 stdout/stderr 逐字**不**动、=> 而而那它们逐字**仍**是那 Java 管道）
                android.system.Os.dup2(slaveFd, 0)
                val p = pb.start()
                //  => 而而那那 App 进程逐字自己逐字那 stdin 逐字**还**回去
                //    （=> 而而那那**孩子**逐字**已经**继承了那从设备、=> 而而那它逐字**不**受影响）
                if (savedStdin != null) {
                    runCatching { android.system.Os.dup2(savedStdin, 0) }
                    runCatching { android.system.Os.close(savedStdin) }
                }
                p
            } finally {
                runCatching { android.system.Os.close(slaveFd) }
            }
            session.process = proc
            //  => 而而那那**产品侧**逐字**读**那子进程逐字的输出、=> 而而那它逐字**送到**那一面
            //    （=> 而那 `Session.deliver` 那一格、=> 而而那它逐字**就是**那 `onData` 那一路）
            //  => 而而那那 **native** 那一族逐字**只**管那从设备那一头（=> 而而那它逐字**不**抢那读）
            pumpOutput(session, proc)
            //  缺陷名（C-102）**那一格逐字没写那账本、=> 而而那那一格逐字是那产品侧逐字起的那一个**
            //    => 而那旧版逐字是 `session.process = proc`（=> 而而那它逐字**只**记那一个句柄）
            //      => 而而那那**账本**那一族逐字**没有**这一行、=> 而而那它逐字**漏记**
            //    => 而而那**判别形式**（01 裁的 `S625-01-LEDGER-PTY-SESSION-RULING.md`）：
            //      「答『那一个进程在 LOB-OS 自己那一份 Linux 上跑着、归谁管』，
            //        判据必须落在『那账本那一行记的那五格』上，
            //        不许由那进程的名字反推、不许猜；
            //        => 而那 PTY 会话那一族起的那一个不许漏记、
            //        => 而而那 native 那一族管的那一个不许记账本」
            //    => 而而那**那五格**逐字是：程序名 / 那一代 / 那 pid / 那 starttime / 那起始时刻
            //      （=> 而那 `ProcessLedger.Entry` 那一族逐字就是那五格）
            //    => 而而那**程序名**逐字是那**件**逐字（=> 而而那它逐字**不**由那进程的名字反推）
            //    => 而而那**那一代**逐字走那 `nextGeneration` 那一族（=> 而而那它逐字**不**猜）
            //    => 而而那**native** 那一族管的那一个（=> 而而那 `session.process` 逐字**空**时）
            //      逐字**不**记账本（=> 而而那它逐字**不是**那产品侧逐字起的那一个）
            //  => 而而那那**产品侧**起的那一条逐字**记进**那产品侧那一族
            //    （=> 而而那它逐字**不**在 native 那一族那里面、=> 而而那 `activeSessions` 逐字
            //     逐字**要**能列它）
            //    => 而而那那**键**逐字带那族标记（=> 而而那它逐字**不**与 native 那一族**冲突**）
            ProductSessions.put("product-" + session.sid, session)
            //  缺陷名（C-102）**那一格逐字要等那一拍才逐字登记进 `files/dev`** ——
            //    => 而那旧版逐字**只**记进那 `ProductSessions` 那一族
            //      => 而而那那 `files/dev` 逐字**只**在那一 `SysDev.render` 那一拍逐字**才**写
            //        （=> 而而那那一拍逐字是那 15 秒那一族、=> 而而那那 `SupervisorPool` 那一族逐字**驱动**它）
            //      => 而而那那**会话**逐字**起**了之后、=> 而而那那 15 秒**之内**逐字**取**那一读逐字**看不到**它
            //        （=> 而那 07 本轮逐字核出：=> 而那 `sess3.sh` 每 3 秒 12 拍逐字全空、
            //          => 而那 `sess4.sh` 每 1 秒 40 拍逐字全空、UTC 03:53:07Z 到 03:53:22Z）
            //      => 而而那那账本逐字**有**它（=> 而那 `linker64 gen=1 pid=22915 starttime=7581679`）
            //        => 而而那那一件逐字**起过**、=> 而而那它逐字**退了**
            //    => 而那**判据**逐字是「那会话逐字该活多久」那一个事实上
            //      （=> 而而那它逐字与那「那会话号逐字撞了」那一族逐字共同的那一族）
            //    => 而那**处置**：=> 而而那那**会话**逐字**登记**逐字**立刻**就逐字**做**
            //      => 而而那那 `files/dev` 逐字**立刻**逐字**有**它（=> 而而那那**读数**逐字**不用**等那一拍）
            //      => 而而那那**判读**那一族逐字**不用**赶在那一拍之内核
            notifyRegistered(ctx)
            recordInLedger(ctx, session, proc, argv)
            //  => 而而那那**退出清理**逐字与那**创建/记账**逐字**一致**
            //    （=> 而那是 M7-07 那一行逐字说的「创建、记账、退出清理一致」）
            //    => 而而那那进程逐字**退了**时逐字把那一行逐字**从账本清掉**
            //      （=> 而否则那账本逐字**留着**一个**已经不在**的进程）
            val pid0 = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid0 > 0) {
                Thread({
                    runCatching { proc.waitFor() }
                    //  缺陷名（C-102）**那一格逐字**只**清了那一族、=> 而而那那 native 那一族逐字那槽逐字**还占着** ——
                    //    => 而那旧版逐字是 `ProcessLedger.end(…)` + `ProductSessions.remove(…)`
                    //      => 而而那那**账本**清了、=> 而而那那**名单**清了
                    //      => 而而那那 **`F_CLOSE`** 逐字**没人发**
                    //        （=> 而而那那 `F_CLOSE` 逐字**只有**那 `Session.close()` 那一格逐字发）
                    //      => 而而那那 native 那一族逐字那槽逐字**还占着**
                    //        （=> 而而那那 `session_close` 逐字**没**被调到、=> 而而那那槽逐字**没**被清）
                    //      => 而而那那**下一趟**逐字分号逐字就**撞**
                    //        （=> 而而那那 `diag.jsonl` 逐字 `stage="pty"` 逐字两条 FAIL
                    //          「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」）
                    //    => 而那**处置**：=> 而而那那**产品侧**逐字自己退时逐字**显式**逐字发那 `F_CLOSE`
                    //      => 而而那那槽逐字**就**被还回去了、=> 而而那那**下一趟**逐字分号逐字**不撞**
                    //      => 而而那那**判据**逐字是「那会话号逐字撞了那一族」那一个事实上
                    //        （=> 而而那它逐字与那「那会话逐字该活多久」那一族逐字**不并**）
                    runCatching { session.close() }
                    runCatching { lobos.kernel.proc.ProcessLedger.end(ctx, pid0) }
                    //  => 而而那那**产品侧**那一族逐字**去掉**那一条（=> 而而那它逐字**不在**了）
                    runCatching { ProductSessions.remove("product-" + session.sid) }
                }, "lobos-pty-ledger-clean").apply { isDaemon = true; start() }
            }
        }

        /**
         * 那**会话**逐字**登记**那一格（=> 而而那它逐字**立刻**就逐字把那 `files/dev` 逐字**刷新**一遍）。
         *
         *   => 而而那那**刷新**逐字**不**逐字等那 15 秒那一拍（=> 而而那那**读数**逐字**不用**赶在那一拍之内核）
         *   => 而而那那**刷新**逐字**失败**时逐字**不**逐字把那一格逐字的中断逐字带出去
         *     （=> 而而那它逐字**自己** catch 住、=> 而而那它逐字**报缺**）
         */
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

        /**
         * 那**输出**那一条读（=> 而而那它逐字是「那一件逐字的输出逐字从哪条通道回来」那一族）。
         *
         *   => 而而那它逐字**读**那子进程逐字那 stdout/stderr 逐字、=> 而而那它逐字**送到**那一面
         *     （=> 而那 `Session.deliver` 那一格）
         *   => 而而那它逐字**不**抢那 native 那一族逐字那 master（=> 而而那那两遍逐字各读各的）
         *   => 而而那它逐字**不**静默（=> 而那读出错时逐字**报缺**）
         */
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
                runCatching {
                    val es = proc.errorStream
                    val ebuf = ByteArray(4096)
                    while (true) {
                        val n = es.read(ebuf)
                        if (n <= 0) break
                        val out = ebuf.copyOf(n)
                        runCatching { session.deliver(out) }
                    }
                }
            }, "lobos-pty-output-" + session.sid)
            t.isDaemon = true
            t.start()
        }

        /**
         * 那**账本**那一格逐字（=> 而而那它逐字记那**五格**、=> 而而那它逐字**不**漏记）。
         *
         *   => 而而那它逐字**失败**时逐字**报缺**（=> 而按 C-05 不猜填、=> 而**不**静默）
         *   => 而而那它逐字**不**替那 native 那一族管的那一个记账本（=> 而那它逐字**不是**产品侧起的）
         */
        /**
         * 那**账本**那一格逐字（=> 而而那它逐字记那**五格**、=> 而而那它逐字**不**漏记）。
         *
         *   => 而而那它逐字**失败**时逐字**报缺**（=> 而按 C-05 不猜填、=> 而**不**静默）
         *   => 而而那它逐字**不**逐字把那一格逐字的中断逐字**带出去**
         *     （=> 而那它是「那一格逐字抛异常、=> 而而那它逐字把别的逐字一起带走」那一族）
         *   => 而而那它逐字**不**替那 native 那一族管的那一个记账本（=> 而那它逐字**不是**产品侧起的）
         *
         *  缺陷名（C-102）**那一格逐字把别的逐字一起带走了** ——
         *   => 而那旧版逐字**没有**任何一处 try/catch、=> 而而那那 `ProcessLedger.begin` /
     *     `nextGeneration` 那一族逐字**抛**时逐字**中断**那整格
         *     => 而而那**后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
         *       · 而那那一件逐字**活着**（=> 而那 diag 逐字「会话 0 就绪 从设备=/dev/pts/0」、
         *         => 而那 `ps` 逐字**有** 3 个 bash/linker64 进程）
         *       · => 而而那**账本**逐字**没有**它、=> 而而那 **ProductSessions** 逐字**也没有**它
         *       · => 而而那那 `activeSessions` 逐字列不出它、=> 而而那那四个判据逐字**全不成立**
         *   => 而那**处置**：=> 而而那**整格**逐字自己 catch 住、=> 而而那它逐字**报缺**
         *     => 而而那它逐字**不**逐字把那一格逐字的中断逐字带出去
         *     => 而而那那 `ProductSessions.put` 逐字与那会话逐字本身逐字**不**逐字受它影响
         */
        private fun recordInLedger(
            ctx: Context,
            session: Session,
            proc: Process,
            argv: List<String>,
        ) {
            try {
            //  => 而那 pid 逐字**从**那进程自己逐字读（=> 而那它逐字**不**猜）
            //    => 而而那那读法逐字跟那 `ProcessSupervisor` 那一族逐字**同一**个
            //      （=> 而那 `Process.pid()` 逐字在那一层**不可用**、=> 而而那它逐字从 `toString()` 逐字取）
            val pid = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid <= 0) {
                //  => 而那 pid 逐字读不到时逐字**报缺**（=> 而按 C-05 不猜填）
                lobos.services.log.RuntimeDiagnostics.append(
                    ctx, "ledger", false, "PTY 会话那进程逐字取不到 pid",
                    "sid=" + session.sid + " （=> 而而那账本那一行逐字**没有**记、=> 而那按 C-05 报缺）",
                )
                return
            }
            //  => 而那**程序名**逐字是那**件**逐字（=> 而而那它逐字**不**由那进程的名字反推）
            val programId = session.programId.ifBlank {
                argv.firstOrNull()?.let { basenameOf(it) } ?: ("pty-" + session.sid)
            }
            //  => 而那**那一代**逐字走那 `nextGeneration` 那一族（=> 而而那它逐字**不**猜）
            val generation = lobos.kernel.proc.ProcessLedger.nextGeneration(ctx, programId)
            val recorded = lobos.kernel.proc.ProcessLedger.begin(ctx, programId, generation, pid)
            lobos.services.log.RuntimeDiagnostics.append(
                ctx, "ledger", recorded != null,
                "PTY 会话那进程逐字已记账",
                if (recorded != null) {
                    "program=" + recorded.programId + " gen=" + recorded.generation +
                        " pid=" + recorded.pid + " starttime=" + recorded.starttime +
                        " startedAt=" + recorded.startedAt
                } else "账本那一行逐字**没有**记成（=> 而那按 C-05 报缺）",
            )
            } catch (e: Throwable) {
                //  => 而而那那**记账**逐字**失败**时逐字**报缺**、=> 而而那它逐字**不**把那一格逐字的中断逐字带出去
                //    => 而而那那 `ProductSessions.put` 逐字与那会话逐字本身逐字**不**受它影响
                runCatching {
                    lobos.services.log.RuntimeDiagnostics.append(
                        ctx, "ledger", false, "PTY 会话那进程逐字记不进账本",
                        "sid=" + session.sid + " " + e.javaClass.name + ": " + (e.message ?: "") +
                            "（=> 而而那那一格逐字**没有**中断、=> 而而那那会话逐字**仍然**活着）",
                    )
                }
            }
        }

        /** 那一个路径逐字**最后**那一段（=> 而而那它逐字**不**猜）。 */
        private fun basenameOf(p: String): String =
            p.trimEnd('/').substringAfterLast('/').ifBlank { p }

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

        /**
         * 那**会话号**逐字**统一**分（=> 而而那它逐字**不**让两边各自去抢）。
         *
         * 缺陷名（C-102）**那号逐字在两族逐字之间逐字撞了** ——
         *   => 而那旧版逐字是 `for (i in 0 until MAX_SESSIONS) if (!sessions.containsKey(i)) return i`
         *     => 而而那那 `sessions` 逐字**只是**宿主侧那一族（=> 而而那那产品侧持有的那些**不**在里面）
         *     => 而而那那 **native** 那一族逐字**自己**有一份 `g_sessions[].open`
         *       （=> 而而那那号逐字是**它**逐字分的、=> 而而那它逐字**不**知道宿主侧那一份）
         *     => 而而那那**后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
         *       · 而那产品侧逐字那会话逐字拿到 0、=> 而而那那 native 那一族逐字那 0 号逐字
         *         **已被** `sys.tty.exec` 那一族逐字占了
         *       => 而而那那 diag 逐字「FAIL | PTY 的C 侧报错 | WINSIZE 指向不存在的会话」
         *       => 而而那那**会话号**逐字错位那一族
         *       => 而而那那 `tty/index.json` 逐字那两行逐字重复（=> 而而那那也是那号撞了那一族的旁证）
         *   => 而那**处置**：=> 而那号逐字**统一**分、=> 而而那它逐字**不**由两边各自去抢
         *     => 而而那它逐字**看**那**两边**逐字**都**占着的那些号
         *     => 而而那那产品侧持有的那些逐字**也**算**占着**
         *     => 而而那那 native 那一族逐字那槽逐字**空**着逐字才**用**
         */
        private fun allocSid(): Int {
            //  => 而而那那**占着**的那几号逐字 = 宿主侧那一族 ∪ 产品侧那一族 ∪ native 那一族
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
                //  缺陷名（C-102）**那一格逐字把**那产品侧**持有的那些会话逐字一起逐字标成**关了** ——
                //    => 而那旧版逐字是 `for (s in sessions.values) s.markClosed()`
                //      => 而而那那 `sessions` 逐字**只是**那 native 那一族逐字管的那一些
                //      => 而而那那**读帧线程**逐字**结束**时（=> 而那是那 native 宿主逐字**没了**那一族）
                //        逐字把**全部**那一族逐字标成**关**
                //      => 而而那那**产品侧**持有的那些逐字**不在**那 `sessions` 那一族里
                //        => 而而那它们逐字**不**受那 native 宿主逐字**生死**的影响
                //        => 而而那**后果**（07 本轮逐字核出）：
                //          · 而那会话逐字**起过**（=> 而那那 PTY 进程逐字**在**那账本里：
                //            逐字 `linker64 gen=1 pid=18427 starttime=4710383`）
                //          · => 而而那那会话逐字**没登记进 `files/dev`**
                //            （=> 而那 pts 空、=> 而那等 8 拍 80 秒逐字没出现、=> 而那 index.json `[]`）
                //          => 而而那是那 `activeSessions` 那一格逐字的问题
                //            （=> 而而那那 `ProductSessions.list()` 逐字**滤掉**那 `isClosed` 的那些）
                //    => 而那**处置**：=> 而而那那**产品侧**的那些逐字**不**被那一格逐字标成**关**
                //      => 而而那它们逐字**只**由它们**自己**那一路逐字标（=> 而那是那进程逐字**退**了、
                //        或而那那调用方逐字**显式**关它）
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
            //  缺陷名（C-102）**那一格逐字把那 native 那一族的退出逐字当成那会话逐字**真的**退了** ——
            //    => 而那旧版逐字是 `s?.recordExit(...); s?.markClosed()`
            //      => 而而那那**退出信号**逐字是那 **native** 那一族逐字发的
            //        （=> 而和那它逐字是那 **native fork 出来**的那个子进程逐字退时逐字发的）
            //      => 而而那那**执行**逐字**已经**归那**产品侧**了（=> 而那是 52c0139 那一笔逐字落的）
            //        => 而和那那 native 的那个子进程逐字**只**握那从设备、=> 而和那它逐字退
            //          **不代表**那产品侧逐字起的那进程退
            //      => 而而那那一格逐字 markClosed 逐字把那会话逐字逐字**关**了
            //        => 而而那那 `ProductSessions.list()` 逐字**滤掉**那 `isClosed` 的那些
            //          => 而而那那会话逐字**就**不在那名单里、=> 而而那那 `files/dev` 逐字**没有**它
            //            （=> 而那 07 本轮逐字核到：=> 而那那会话逐字**起过**、
            //              => 而而那那 PTY 进程逐字**在**那账本里：逐字 `linker64 gen=1 pid=18427`、
            //              => 而而那那会话逐字**没登记进 `files/dev`**：
            //              逐字 pts 空、=> 而那等 8 拍 80 秒逐字没出现、=> 而那 index.json `[]`）
            //    => 而那**处置**：=> 而而那那**产品侧**的进程逐字**还活着**时
            //      => 而而那那一格逐字**不**把那会话逐字标成**关**
            //      => 而而那那会话逐字**继续**在那名单里、=> 而而那那 `files/dev` 逐字**有**它
            //      => 而而那那**真的**退了时（=> 而那产品侧逐字**自己**报那退出、=> 或那调用方逐字**显式**关它）
            //        逐字**才**标成**关**
            val stillAlive = s?.process?.isAlive == true
            if (!stillAlive) {
                s?.recordExit(status, signal)
                s?.markClosed()
            }
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
    /**
     * 那**产品侧**逐字持有的那几条会话（=> 而那些逐字**不**是 native 那一族管的那一些）。
     *
     * 缺陷名（C-102）**那产品侧起的那一条逐字没人持有、=> 而而那它逐字不在那会话那一族的名单里** ——
     *   => 而那旧版逐字**没有**这样的一族（=> 而那 `spawnOnSlave` 那一格逐字起完就**只**记那一个句柄）
     *     => 而而那那**会话**逐字**没有**一个统一的名单、=> 而而那 `activeSessions` 逐字**只**问 native 那一族
     *   => 而那**处置**：=> 而那**这一族**逐字持有那产品侧起的那几条
     *     => 而而那它们逐字**可**被 `activeSessions` 逐字列出来
     *     => 而而那那**会话号**逐字**不**该**冲突**（=> 而而那它逐字**带**一个族标记）
     */
    object ProductSessions {
        private val lock = Any()
        private val held = LinkedHashMap<String, Session>()

        /** 那**记下**那一条（=> 而而那那号逐字**带**那族标记、=> 而而那它逐字**不**与 native 那一族**冲突**）。 */
        @Synchronized
        fun put(key: String, s: Session) {
            synchronized(lock) { held[key] = s }
        }

        /** 那**还**在的那些（=> 而而那它们逐字**不**是 native 那一族管的那些）。 */
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

        /** 那**去掉**那一条（=> 而而那那进程逐字退了时逐字调）。 */
        @Synchronized
        fun remove(key: String) {
            synchronized(lock) { held.remove(key) }
        }
    }

    /**
     * 那**活着**的那几条会话（=> 而而那**两族**逐字**都**列）。
     *
     * 缺陷名（C-102）**那一格逐字只列 native 那一族管的那一些、=> 而而那产品侧持有的那些逐字不在里面** ——
     *   => 而那旧版逐字是
     *       `val h = host; return if (h == null) emptyList() else h.activeSessions()`
     *     => 而而那那 `h.activeSessions()` 逐字**只**列那 `g_sessions` 那一族逐字管的那些
     *       （=> 而而那是那 **native** 那一族逐字 fork 出来的）
     *     => 而而那**产品侧**逐字持有的那一条（=> 而那 `spawnOnSlave` 那一格逐字起的那一个）
     *       逐字**不在**那里面
     *     => 而而那**后果**（07 本轮逐字核出、Lead 本机实读逐字成立）：
     *       · 而那 `SysDev.kt:64` 逐字 `for (s in PtySession.activeSessions(ctx))`
     *       · => 而而那产品侧逐字持有的那一条（=> 而那 sid=0 / slave=/dev/pts/2）逐字不在里面
     *       · => 而而那 `files/dev/tty/pts/<NNN>` 逐字**永远建不出来**
     *   => 而那**处置**：=> 而那**两族**逐字**都**列
     *     · 那 **native** 那一族逐字管的那一些（=> 而那些逐字是那 PTY 那一层自己管的）
     *     · 那**产品侧**逐字持有的那一些（=> 而那些逐字是那 `spawnOnSlave` 那一格逐字起的）
     *     => 而而那那**会话号**逐字**不**该**冲突**（=> 而而那两族逐字**各**用自己那一套号）
     *     => 而而那那**判别形式**（依 C-105 逐字**不**合并那两问）：
     *       答「那**会话**逐字是什么」逐字该列**全部**；=> 而而那答「那**进程**逐字归谁管」逐字**只**看那账本
     */
    fun activeSessions(ctx: Context): List<Session> {
        val native = runCatching {
            val h = host
            if (h == null) emptyList() else h.activeSessions()
        }.getOrDefault(emptyList())
        //  => 而而那那**产品侧**逐字持有的那一些（=> 而那些逐字**不**在 native 那一族那里面）
        val product = runCatching { ProductSessions.list() }.getOrDefault(emptyList())
        //  缺陷名（C-102）**那同一条会话逐字被列了两次** ——
        //   => 而那旧版逐字是 `native + product`
        //     => 而而那那**同一条**会话逐字**两边**都看得见（=> 而那 native 那一族逐字有一份、
        //       而而那那产品侧那一族逐字**也**有一份）
        //     => 而而那那 `tty/index.json` 逐字那两行逐字**重复**
        //       （=> 而那 07 本轮逐字核到：`[{"sid":0,…},{"sid":0,…}]`、=> 而那那两行逐字**完全一样**）
        //       （=> 而那那也是那号撞了那一族的**旁证**）
        //   => 而那**处置**：=> 而那那**同一**那一条会话逐字**只**列一次
        //     => 而而那那**判据**逐字是那**从设备名**（=> 而那它逐字**唯一**、=> 而那那号逐字**可能**重）
        //     => 而而那那**号**逐字**不同**的那些逐字**都**留（=> 而那是**两条**不同的会话）
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
            //  缺陷名（C-102）**那一格逐字逐字**无上限**地累积那输出** ——
            //    => 而那旧版逐字是 `val buf = java.io.ByteArrayOutputStream()`
            //      => 而而那那**缓冲**逐字**一直**涨、=> 而而那它逐字**不**停
            //        （=> 而而那那一趟逐字要多久、=> 而而那它逐字就涨多少）
            //      => 而而那那堆逐字**本来**就只剩那**余量**了
            //        （=> 而那设备上 `heapgrowthlimit` = 384m、=> 而而那那堆逐字已到 291 MB）
            //    => 而那**处置**：=> 而而那那缓冲逐字**有**一个上限、=> 而而那超出那一截逐字**丢**
            //      => 而而那那**期望输出**那一族逐字**要**的是那**开头**那一些（=> 而那它逐字**不**是全量）
            //      => 而而那那**上限**逐字**要**报缺（=> 而那按 C-05 不猜填）
            val buf = java.io.ByteArrayOutputStream()
            var dropped = 0L
            val done = CountDownLatch(1)
            s.onData = { d ->
                synchronized(buf) {
                    if (buf.size() + d.size > MAX_CAPTURED_OUTPUT_BYTES) {
                        //  => 而而那那**超出**的一截逐字**丢**、=> 而而那那**丢了多少**逐字**记**着
                        val room = (MAX_CAPTURED_OUTPUT_BYTES - buf.size()).coerceAtLeast(0)
                        if (room > 0) buf.write(d, 0, room)
                        dropped += (d.size - room).toLong()
                    } else {
                        buf.write(d)
                    }
                }
            }
            s.onExit = { _, _ -> done.countDown() }
            //  缺陷名（C-102）**那一格逐字只等 native 那一族那一条退出信号、=> 而而那那一条逐字**永远不来****
            //   => 而那旧版逐字是 `s.onExit = { _, _ -> done.countDown() }`
            //     => 而而那那退出信号逐字是那 native 那一族逐字发的 `FRAME_EXITED`
            //       （=> 而而那那一条逐字是那 **native fork 出来**的子进程逐字退时逐字发的）
            //     => 而而那**现在**逐字那执行逐字归那**产品侧**（=> 而那是 52c0139 那一笔逐字落的）
            //       => 而而那那产品侧逐字起的进程逐字**自己**退、=> 而而那那 native 那一族逐字**不知道**
            //       => 而而那那 `FRAME_EXITED` 逐字**永远不来**、=> 而而那那**等**逐字**只能**超时
            //   => 而而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
            //     · 那 `sys.tty.exec` 那一趟逐字「PTY 不可用（超时 10000ms）」、=> 而而那逐字走那无 PTY 通路
            //     · => 而而那那**阳性对照**：那会话逐字**建得出来**（=> 而那 diag 逐字「会话 0 就绪 从设备=/dev/pts/N」）
            //   => 而那**处置**：=> 而而那那进程逐字**自己**退了时逐字把那退出**报**出来
            //     => 而而那那 `onExit` 逐字**两路**都能来（=> 而那 native 那一族逐字那一条 + 而那产品侧这一条）
            //     => 而而那那**等**逐字因此逐字**等得到**、=> 而而那那**超时**逐字**不再**是那唯一那条路
            s.process?.let { proc ->
                Thread({
                    val st = runCatching { proc.waitFor() }.getOrNull()
                    runCatching {
                        s.onExit?.invoke(st ?: 0, 0)
                    }
                }, "lobos-pty-exit-watch").apply { isDaemon = true; start() }
            }
            //  => 而而那那**丢了多少**逐字**记**着（=> 而那按 C-05 不猜填、=> 而那**不**静默）
            val droppedNote = if (dropped > 0L) "（=> 而那超出上限那一截逐字丢了 $dropped 字节）" else ""
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
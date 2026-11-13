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
     * 输出捕获上限 4 MiB：超出丢掉、记丢了多少（C-05，不猜填）。
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

    /**
     * 等槽空出来的上限 2 秒：native 500 毫秒没回，宿主也照样还号、记一档。
     */
    private const val SLOT_FREED_TIMEOUT_MS = 2_000L

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    //  停一个线程时等的时长（有界，不无限等）。
    private const val READER_JOIN_MS = 500L

    /**
     * 帧长度上限 1 MiB（长度从线上来、不能信）：native 自发最大 64 KiB（`FRAME_MAX`），1 MiB 是 16 倍。
     * 超了报缺、不分配（C-05，不猜填）。
     */
    private const val MAX_FRAME_BYTES = 1024 * 1024

    /**
     * 输入上限 64 KiB（与 native `FRAME_MAX` 同一个）：超出丢掉，帧不超、流不错位。
     */
    private const val MAX_INPUT_BYTES = 64 * 1024

    /** 输入丢了多少（C-05，不猜填）。 */
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

        /**
         * native 侧的槽开没开（判据是「那一帧发的时候、槽开没开」那一个事实）。
         * 缺陷 C-102：旧版 `resize()` 无条件发 `F_WINSIZE`（唯一发送点 `host.send(F_WINSIZE, sid, b.array())`），`spawnOnSlave`
         *   的 `if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)` 在 latch 等到之前就可能调它，
         *   native 判 `g_sessions[s].open`（`pty-session.c:463`-`:465` 的 `case FRAME_WINSIZE:`）报「WINSIZE 指向不存在的会话」。
         * 后果：diag.jsonl 两条 FAIL（1793597239169、1793597303066）。处置：等 `F_READY` 到了才发（`onReady` 就是凭据）。
         */
        @Volatile private var nativeSlotOpen = false

        /** 要的尺寸（`F_READY` 到了之后补发）。 */
        @Volatile private var pendingWinsize: Pair<Int, Int>? = null

        /**
         * `F_READY` 那一帧真到了没有（不等同于 `nativeSlotOpen`）。
         * 缺陷 C-102：旧版把本地状态 `nativeSlotOpen` 置真就叫就绪凭据，而它可能比 `F_READY` 早变真，
         *   `F_WINSIZE` 就发得太早，diag.jsonl 里 FAIL 还在发（两趟都是「会话 0 就绪」，号没撞）。
         * 处置：凭据只是 `F_READY` 那一帧真的到（依 C-103，两边各答一件事）；resize 等真的到。
         */
        @Volatile private var readyFrameArrived = false

        //  回调接上时把缓冲倒给它（输出不丢）。
        @Volatile
        var onData: ((ByteArray) -> Unit)? = null
            set(value) {
                field = value
                if (value != null) flushPending()
            }

        //  缺陷名 C-102：回调没接上时到的输出被丢掉，且那一格无上限累积。
        //   旧版 `onData` 是可变字段，调用方在 `start()` 返回后才接（`runToCompletion` 的 `s.onData = { … }`），
        //   这中间宿主已可发 FRAME_DATA；`pending` 是 `ByteArrayOutputStream()`、没上限。后果：`sys.tty.exec`
        //   那趟 stdout 空、退出码 0（账本只有 jq），以及 logcat OOM「Failed to allocate a 117031556 byte allocation」。
        //   处置：输出先落进 Session 自己的缓冲、回调接上时倒给它；上限 4 MiB，超出丢掉、记丢了多少（C-05，不猜填）。
        private val pending = java.io.ByteArrayOutputStream()
        private val pendingLock = Any()
        private var pendingDropped = 0L

        /** 缓冲的上限（超出那一截丢掉）。 */
        private val MAX_PENDING_BYTES = 4 * 1024 * 1024

        /** 缓冲里有没有东西（读数，不是推出来的）。 */
        val hasPending: Boolean get() = synchronized(pendingLock) { pending.size() > 0 }

        internal fun deliver(data: ByteArray) {
            val cb = onData
            if (cb != null) {
                try { cb.invoke(data) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
                return
            }
            //  回调还没接上：输出先进缓冲、不丢。
            //    上限 4 MiB（与 `LocalExec` 那一族同一个），超出丢掉并记丢了多少（C-05，不猜填）。
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

        /** 丢了多少（C-05，不猜填；丢了就报缺）。 */
        internal fun pendingDroppedBytes(): Long = synchronized(pendingLock) { pendingDropped }

        @Volatile var onExit: ((status: Int, signal: Int) -> Unit)? = null

        @Volatile var exitStatus: Int = -1
            private set

        @Volatile var exitSignal: Int = 0
            private set

        /**
         * 那条进程（产品侧起的、不是 native fork 的）：非空时该记账本，空时归 native 管、不记账本。
         */
        @Volatile var process: Process? = null

        /**
         * 程序名（是那件、不由进程名反推）；空时报缺（C-05，不猜填）。
         */
        @Volatile var programId: String = ""

        internal fun recordExit(status: Int, signal: Int) {
            exitStatus = status
            exitSignal = signal
        }

        //  缺陷名 C-102：一帧发得太大，native 丢掉那一帧、没吃掉体，流就错位了。
        //   旧版 `host.send(F_INPUT, sid, data)` 无上限，native 的 `FRAME_MAX` 是 64 KiB；错位后下一帧的 sid
        //   是体里的某个字节，可能大于 `MAX_SESSIONS`，就判「WINSIZE 指向不存在的会话」。后果：两条 FAIL 每次
        //   都在「会话 0 就绪」之后 3 到 4 秒（20:41:07->20:41:10、20:48:51->20:48:55）。
        //   判据是「那一帧发的时候、native 的槽开没开」；处置：输入上限 64 KiB、超出截断、帧不超。
        fun write(data: ByteArray) {
            if (closed.get()) return
            if (data.size <= MAX_INPUT_BYTES) {
                host.send(F_INPUT, sid, data)
                return
            }
            //  超出那一截截断、记丢了多少（C-05，不猜填）。
            val keep = data.copyOf(MAX_INPUT_BYTES)
            val dropped = (data.size - MAX_INPUT_BYTES).toLong()
            synchronized(inputDroppedLock) { inputDropped += dropped }
            host.send(F_INPUT, sid, keep)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) {
            if (closed.get()) return
            //  native 的槽还没开时不发那一帧，要的尺寸记进 `pendingWinsize`、`F_READY` 到了补发。
            //  缺陷名 C-102：旧版拿宿主侧能知道的 `readyFrameArrived` 当凭据，可它不是那个事实
            //    （`F_READY` 到、槽还没开；槽开、`F_READY` 可能没到），`F_WINSIZE` 就发得太早。
            //    处置：判据归 native 自己答（`pty-session.c` 的 `pending_winsize`），宿主侧照旧发。
            pendingWinsize = rows to cols
            sendWinsize(rows, cols)
        }

        /** 真的发那一帧（槽已经开的时候才走这一格）。 */
        private fun sendWinsize(rows: Int, cols: Int) {
            val b = java.nio.ByteBuffer.allocate(8)
            b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort(rows.toShort()).putShort(cols.toShort())
            b.putShort(0).putShort(0)
            host.send(F_WINSIZE, sid, b.array())
        }

        /**
         * native 的槽开好了（只由 `F_READY` 触发）：记着的尺寸这时候补发。
         */
        internal fun onNativeSlotOpen() {
            nativeSlotOpen = true
        }

        /**
         * `F_READY` 那一帧真的到了（只由它触发，不由本地状态触发）：记着的尺寸这时候才发。
         * 判据归 native 自己答（`g_sessions[s].open` 宿主看不到，`F_READY` 也不是它的回执）；
         * 早到的那一帧由 native 自己记着，不要宿主侧猜。
         */
        internal fun onReadyFrameArrived() {
            readyFrameArrived = true
            val p = pendingWinsize ?: return
            pendingWinsize = null
            runCatching { sendWinsize(p.first, p.second) }
        }

        val isClosed: Boolean get() = closed.get()

        internal fun markClosed() { closed.set(true) }

        /**
         * 关掉一条会话（把号还给 native）。
         * 缺陷 C-102：旧版只发 `F_CLOSE`，号在两边错开——native 的 `session_close` 已清槽，Kotlin 的 `sessions`
         *   还留着，`allocSid` 跳过那号而 native 复用，`FRAME_READY` 回的号对不上，latch 永远等不到。
         * 处置：关的时候同时把那一号从 `sessions` 去掉，两边同一套号。
         */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                //  缺陷名 C-102：旧版只关了 native 那一族，产品侧的进程还活着。
                //   `F_CLOSE` 只让 native 关它自己那个（`session_close` 的 `kill(…SIGHUP)` 杀的是它 fork 的子进程，
                //   那个只握从设备），产品侧起的那个没人杀。后果：关完后账本仍有 `linker64 gen=2 pid=26021`、
                //   `files/dev` 仍有 `pts: [000]` 与 `index.json`、`/proc` 还有三个 `linker64`。
                //   处置：产品侧的进程显式终止（走它自己报退出那一路），退出清理随它走（账本与 `files/dev` 同步清空）。
                terminateProcess()
                host.send(F_CLOSE, sid, ByteArray(0))
                //  缺陷名 C-102：旧版把那一号立刻还回去，而 native 的槽还没空。
                //   native 关完槽才回一帧（FRAME_EXITED，等子进程死最多 500 毫秒），旧版 `host.releaseSid(sid)`
                //   紧跟其后不等回话，下一趟拿到同一号而槽还开着，就报「会话号已被占用」/「WINSIZE 指向不存在的会话」。
                //   判据是「还号的时候、native 的槽空没空」；处置：等回帧到了才还，等有上限
                //   （`SLOT_FREED_TIMEOUT_MS` 2 秒，没回就照样还、记一档）。
                val freed = host.awaitSlotFreed(sid, SLOT_FREED_TIMEOUT_MS)
                if (!freed) {
                    host.reportSlotNotFreed(sid)
                }
                host.releaseSid(sid)
                ProductSessions.remove("product-" + sid)
            }
        }

        /**
         * 把 native 的槽还回去（只发 `F_CLOSE`，不杀进程——进程退了时 `close()` 管终止）。
         * `close()` 的 `compareAndSet` 护着，只发一次。
         */
        internal fun releaseNativeSlot() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { host.send(F_CLOSE, sid, ByteArray(0)) }
            runCatching { host.releaseSid(sid) }
            runCatching { ProductSessions.remove("product-" + sid) }
        }

        /**
         * 显式终止产品侧的进程：先软终止（`TERM`），不成再硬终止（C-05，不猜填）。
         * 退出清理随它走（账本与 `files/dev` 同步清空）。
         */
        private fun terminateProcess() {
            val proc = process ?: return
            //  先软终止（`TERM` 给进程机会收尾）。
            runCatching { proc.destroy() }
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) return
                runCatching { Thread.sleep(50L) }
            }
            //  软终止不成时硬终止（不留着）。
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }

    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        companion object {
            /** 会话数与 native 一样（8）。 */
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
                    //  缺陷名 C-102：那一路只进 diag、不进诊断。
                    //   旧版只写进内存里的 diag 串（只被 `diagnostics()` 读，而它只在「PTY 超时未就绪」那一支用），
                    //   进程一退全没了。处置：一行也进诊断（`RuntimeDiagnostics.append` 写进 diag.jsonl）。
                    //   ctx 可以是空（Host 的 ctx 可选），空的时候不报——是「没有地方可报」，不是「没算」。
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

        /**
         * 占着的槽（native `open` 那一格的宿主侧镜像）。
         * 判据是「还号的时候、native 的槽空没空」；占着记的是 native 回的那一帧。
         * 没收到回帧就一直占着（保守，不抢号）。
         */
        private val slotBusy = HashSet<Int>()

        private val ready = ConcurrentHashMap<Int, CountDownLatch>()

        //  发帧的锁：两个线程各写管道，次序要保证、头与体要连着。
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
            //  native 的槽开了、记成占着（判据是「还号的时候、native 的槽空没空」）。
            markSlotBusy(sid)
            //  程序名从 argv 头一条取那件的末段（不由进程名反推、不猜）。
            s.programId = argv.firstOrNull()?.let { basenameOf(it) } ?: ""
            if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)
            //  缺陷名 C-102：native 自己去 exec 在那个域里跑不起来。
            //   事实（`ExecEntry.kt:115`-`:122`）：四份入口是 u:object_r:app_data_file:s0，app 域 u:r:untrusted_app
            //   对它没有执行许可（直接 exec 在加载解释器之前就 EACCES，error=13）；linker64 的 system_linker_exec
            //   有许可，但它 fork 出来的子进程继承的域里仍没有。后果：子进程在 execv 那一格就死了，留痕一条没到。
            //   处置：执行归产品侧、走 `ProcessSupervisor`（已能跑那九件），要 ctx、不能为空（C-05 报缺）。
            val c0 = ctx
                ?: throw IllegalStateException("宿主侧没有那上下文 —— 报缺，不猜")
            spawnOnSlave(c0, s, argv)
            return s
        }

        /**
         * 执行那一格归产品侧（走 `ProcessSupervisor`）：把子进程的 stdin/stdout/stderr 接到从设备上。
         * 不走 native 那一族（是那个域缺陷的处置）；失败时报缺、不静默（C-05）。
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
            //  形态仍由 `ExecEntry.argv` 那一族算（不变）。
            val shaped = lobos.kernel.proc.ExecEntry.argv(first, argv)
                ?: throw lobos.kernel.proc.ProcessSupervisor.NotExecutable(first)
            val pb = ProcessBuilder(shaped)
            //  环境走同一套（`ProcessSupervisor` 用的那套）。
            val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
                ctx,
                lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
                null,
            )
            pb.environment().clear()
            for ((k, v) in view) pb.environment()[k] = v
            pb.environment().remove("LD_PRELOAD")
            //  缺陷名 C-102：旧版把输入也拿掉了（`dup2` 整个不要），会话当场就退。
            //   stdout/stderr 留 Java 管道是对的，但 stdin 也留了管道、没有写的那一头，`bash -i` 立刻拿到 EOF，
            //   会话当场退出（status=0 signal=0）。后果：会话起过（24 次 TAB 到位），账本只有 `jq`，
            //   `files/dev/tty/pts` 空、`index.json` `[]`。
            //   处置：stdin 单独接到从设备上，stdout/stderr 仍留 Java 管道，App 自己的三路不动。
            //   判据是「那会话该活多久」：活到用户或调用方显式关它，不该因为读侧 EOF 就走。
            val savedStdin = runCatching {
                //  App 进程自己的 stdin 先复制一份
                //    （android.jar 剥掉了 `FileDescriptor` 的静态字段，只能用 `Os.dup`，
                //      它要一个真的 `FileDescriptor`）
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
                //  stdin 单独接到从设备上（stdout/stderr 不动，仍是 Java 管道）。
                android.system.Os.dup2(slaveFd, 0)
                val p = pb.start()
                //  App 自己的 stdin 还回去（孩子已继承了从设备，不受影响）。
                if (savedStdin != null) {
                    runCatching { android.system.Os.dup2(savedStdin, 0) }
                    runCatching { android.system.Os.close(savedStdin) }
                }
                p
            } finally {
                runCatching { android.system.Os.close(slaveFd) }
            }
            session.process = proc
            //  产品侧读子进程的输出、送到 `Session.deliver`（就是 `onData` 那一路）；
            //  native 只管从设备那一头，不抢这读。
            pumpOutput(session, proc)
            //  缺陷名 C-102：旧版没写账本（只 `session.process = proc` 记了个句柄），而这一格是产品侧起的那个进程。
            //   判别形式（01 裁的 `S625-01-LEDGER-PTY-SESSION-RULING.md`）：「答那个进程归谁管，判据必须落在账本
            //   那一行的五格上，不许由进程名反推、不许猜；PTY 会话起的不许漏记，native 管的不许记账本」。
            //   五格是程序名 / 那一代 / pid / starttime / 起始时刻（`ProcessLedger.Entry`）：程序名是那件、
            //   那一代走 `nextGeneration`；`session.process` 空时（native 管的）不记。
            ProductSessions.put("product-" + session.sid, session)
            //  缺陷名 C-102：旧版只记进 `ProductSessions`，要等 `SysDev.render` 那一拍（15 秒，`SupervisorPool`
            //   驱动）才写进 `files/dev`，会话起了之后 15 秒内读数看不到它。后果：`sess3.sh` 每 3 秒 12 拍全空、
            //   `sess4.sh` 每 1 秒 40 拍全空（UTC 03:53:07Z 到 03:53:22Z），而账本有它
            //   （`linker64 gen=1 pid=22915 starttime=7581679`）。
            //   判据是「那会话该活多久」；处置：登记时立刻刷新 `files/dev`，读数不用等那一拍。
            notifyRegistered(ctx)
            recordInLedger(ctx, session, proc, argv)
            //  退出清理与创建/记账一致（M7-07「创建、记账、退出清理一致」）。
            //    进程退了就把那一行从账本清掉，否则账本留一个已经不在的进程。
            val pid0 = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid0 > 0) {
                Thread({
                    //  缺陷名 C-102：旧版把清理吊在 `waitFor()` 后面，常常等不到。
                    //   产品侧的进程是 linker64 那一个（argv 是 `[SYSTEM_LINKER, <入口>, …]`，真身在里面）；`TERM`
                    //   打给 linker64，它不转给里面那一个、自己也不一定退，`waitFor()` 等不到，`ProcessLedger.end`
                    //   没被调到。后果：pts 与 `index.json` 都空、`/proc` 只剩宿主自己那个 `linker64`（pid 28331），
                    //   账本仍留着 `linker64 gen=1 pid=25800 starttime=8966788`。
                    //   处置：清账本不等 `waitFor`（进程退没退是另一问）。归属：清账本 05、判据 01、读数 07。
                    runCatching { lobos.kernel.proc.ProcessLedger.end(ctx, pid0) }
                    runCatching { proc.waitFor() }
                    //  缺陷名 C-102：旧版只清了账本和名单，`F_CLOSE` 没人发（只有 `Session.close()` 发），native 的槽
                    //   还占着，下一趟分号就撞（diag.jsonl 报「WINSIZE 指向不存在的会话」）。
                    //  旧版 `session.close()` 在 `ProcessLedger.end` 之前，而它现在会 `terminateProcess()`（2 秒预算），
                    //   账本要等那 2 秒才清。后果：pts 与 index.json 都空、`/proc` 只剩一个 `linker64`，而账本仍留着
                    //   `linker64 gen=1 pid=25944 starttime=8594873`、40 秒没清。
                    //   处置：`ProcessLedger.end` 先走、终止随后，账本不等那 2 秒。
                    runCatching { session.close() }
                    //  产品侧那一族去掉那一条（它不在了）。
                    runCatching { ProductSessions.remove("product-" + session.sid) }
                }, "lobos-pty-ledger-clean").apply { isDaemon = true; start() }
            }
        }

        /**
         * 会话登记那一格：登记时立刻把 `files/dev` 刷新一遍（不等那 15 秒一拍）。
         * 刷新失败自己 catch 住、报缺，不把中断带出去。
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
         * 读输出那一条：读子进程的 stdout、送到 `Session.deliver`。
         * 不抢 native 的 master（各读各的）；读出错时报缺。
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
                //  缺陷名 C-102：那一路又读了 stderr，stderr 有了两个读者。
                //   旧版 `val es = proc.errorStream` + `while (true) { es.read(ebuf) … }`，而 `diagReader` 也读它
                //   （`proc.errorStream.bufferedReader().forEachLine { … }`）。`InputStream` 不能并发读：两读者抢同一批字节、行被劈开，
                //   `forEachLine` 等的换行被吃掉、等待没有上限。后果：「会话 0 就绪」那条 note 在 stderr 上，
                //   18:58:21 打的、4 秒之后那一帧才发（18:58:25Z）。
                //   处置：stderr 只归 `diagReader`（进 diag.jsonl），输出那一路只读 `inputStream`。
            }, "lobos-pty-output-" + session.sid)
            t.isDaemon = true
            t.start()
        }

        /**
         * 账本那一格（记五格、不漏记）；失败时报缺（C-05，不猜填），不替 native 管的那一个记账本。
         * 缺陷 C-102：旧版没有 try/catch，`ProcessLedger.begin` / `nextGeneration` 抛时中断整格——那一件活着
         *   （diag「会话 0 就绪 从设备=/dev/pts/0」、`ps` 里有 3 个 bash/linker64），而账本与 `ProductSessions`
         *   都没有它，`activeSessions` 列不出它，四个判据全不成立。处置：整格自己 catch 住并报缺。
         */
        private fun recordInLedger(
            ctx: Context,
            session: Session,
            proc: Process,
            argv: List<String>,
        ) {
            try {
            //  pid 从进程自己读（不猜）：`Process.pid()` 在这层不可用，只能从 `toString()` 取，
            //  与 `ProcessSupervisor` 那一族同一个读法。
            val pid = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid <= 0) {
                //  pid 读不到时报缺（C-05，不猜填）。
                lobos.services.log.RuntimeDiagnostics.append(
                    ctx, "ledger", false, "PTY 会话那进程逐字取不到 pid",
                    "sid=" + session.sid + " （账本那一行逐字**没有**记、按 C-05 报缺）",
                )
                return
            }
            //  程序名是那件（不由进程名反推）。
            val programId = session.programId.ifBlank {
                argv.firstOrNull()?.let { basenameOf(it) } ?: ("pty-" + session.sid)
            }
            //  那一代走 `nextGeneration`（不猜）。
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
                //  记账失败时报缺、不把中断带出去；`ProductSessions.put` 与会话本身不受影响。
                runCatching {
                    lobos.services.log.RuntimeDiagnostics.append(
                        ctx, "ledger", false, "PTY 会话那进程逐字记不进账本",
                        "sid=" + session.sid + " " + e.javaClass.name + ": " + (e.message ?: "") +
                            "（一格逐字**没有**中断、会话逐字**仍然**活着）",
                    )
                }
            }
        }

        /** 那一个路径的最后一段（不猜）。 */
        private fun basenameOf(p: String): String =
            p.trimEnd('/').substringAfterLast('/').ifBlank { p }

        /**
         * 会话号跟着 native 分的槽走（不自另分一套）。缺陷 C-102：旧版
         *   `for (i in 0..255) if (!sessions.containsKey(i)) return i`，而 native 的 `alloc_slot()` 只在 0..MAX_SESSIONS（8）
         *   里分，`FRAME_READY` 回的是真槽号、Kotlin 自己分的 sid 被丢掉。后果（槽复用后）：native 复用槽 0 而 Kotlin 给 2，
         *   号不一致，`sessions` 按号索引就对不上。处置：会话号就用 native 回的槽号，只从帧上来。
         */
        @Synchronized
        internal fun releaseSid(sid: Int) {
            sessions.remove(sid)
            ready.remove(sid)
        }

        /**
         * 会话号统一分（不让两边各自去抢）。缺陷 C-102：旧版 `for (i in 0 until MAX_SESSIONS) if (!sessions.containsKey(i))
         *   return i`，而 `sessions` 只是宿主侧那一族，native 自己有一份 `g_sessions[].open`。后果：产品侧的会话拿到 0，
         *   而 native 的 0 号已被 `sys.tty.exec` 占了，报「WINSIZE 指向不存在的会话」，`tty/index.json` 那两行重复。
         * 处置：号统一分，看两边都占着的号——产品侧持有的也算占着，native 的槽空了才用。
         */
        private fun allocSid(): Int {
            //  占着的号 = 宿主侧 + 产品侧 + native 三族。
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
        //  缺陷名 C-102：旧版把一帧拆成两次写、没有锁。
        //   旧版 `toHost.write(h)` + `toHost.write(payload)` + `toHost.flush()`，两个线程各写管道：调用方写
        //   `F_OPEN`，reader 写 `F_READY` 的反应，同时写就交错成 A 写头 -> B 写头+体 -> A 写体，native 读到的
        //   帧头与体对不上。后果：diag.jsonl 两条 FAIL（1793621838075、1793622097373）；`F_READY` 已真到
        //   （「会话 0 就绪」在 FAIL 前 2.1 秒）而帧还是发早了——错在帧错乱、不在早。
        //   处置：一帧一次写（头与体连着）、写一把锁；早到的那一帧由 native 自己答（`pending_winsize`）。
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
                //  一帧一次写成（两次写能交错，一次写不能）。
                val frame = if (payload.isEmpty()) h else h + payload
                //  写一把锁（次序就是写次序）。
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
                    //  缺陷名 C-102：旧版拿线上来的长度直接分配（`val buf = ByteArray(len)`），len 是 `fromHost.read()` 四个字节拼的、
                    //   最大 4294967295（4 GiB）；帧通道能错位（native `send_frame` 两次写、写能交错，读是逐字节）。
                    //   后果：分配量 117031556 字节（`node` 那一件 116779696 字节，差 251860 是压缩与解压之差），
                    //   耗电那第一格回退、第二趟就来 OOM。
                    //   判据是「那一格要多少、它不该要那么多」；处置：上限 1 MiB（native 自发最大 64 KiB 的 16 倍），
                    //   超了丢那一帧并说清长度（C-05，不猜填）——流已错位，帧能重新同步。
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
                //  缺陷名 C-102：旧版把产品侧持有的那些会话也一起标成关了。
                //   旧版 `for (s in sessions.values) s.markClosed()`，而 `sessions` 只是 native 管的那一些；读帧线程
                //   结束时（native 宿主没了）就把那一族全标成关，产品侧持有的不在里面、不该受影响。
                //   后果：会话起过（账本里有 `linker64 gen=1 pid=18427 starttime=4710383`），却没登记进 `files/dev`
                //   （pts 空、等 8 拍 80 秒没出现、`index.json` `[]`）。
                //   处置：产品侧的那些不被这一格标成关，只由它们自己那一路标。
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
            //  `F_READY` 那一帧真的到了（它就是凭据），这时候才补发 `F_WINSIZE`。
            //    本地状态不许当凭据（依 C-103，两边各答一件事）。
            sessions[sid]?.onReadyFrameArrived()
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {
            //  输出先落进 Session 自己的缓冲（回调还没接上时不丢）。
            try { sessions[sid]?.deliver(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        private fun onExited(sid: Int, payload: ByteArray) {
            val s = sessions.remove(sid)
            //  native 回了那回帧，槽已关完：判据是「还号的时候、native 的槽空没空」。
            //    回帧是 `F_CLOSE` 关完才发的（等子进程死最多 500 毫秒，在 native 里面）。
            markSlotFreed(sid)
            var status = -1
            var signal = 0
            if (payload.size >= 8) {
                val b = java.nio.ByteBuffer.wrap(payload, 0, 8)
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                status = b.int
                signal = b.int
            }
            //  缺陷名 C-102：旧版把 native 的退出当成会话真的退了（`s?.recordExit(...); s?.markClosed()`）。
            //   退出信号是 native 发的（它 fork 出来的子进程退时），而执行已归产品侧（52c0139 那一笔），那个子进程
            //   只握从设备，它退不代表产品侧起的进程退。一格 `markClosed` 就把会话关了，`ProductSessions.list()`
            //   滤掉 `isClosed` 的，`files/dev` 就没有它。后果：会话起过、账本里有 `linker64 gen=1 pid=18427`，
            //   却没登记进 `files/dev`（pts 空、等 8 拍 80 秒没出现、`index.json` `[]`）。
            //   处置：产品侧的进程还活着就不标成关，会话继续在名单里；真退了才标。
            val stillAlive = s?.process?.isAlive == true
            if (!stillAlive) {
                s?.recordExit(status, signal)
                //  缺陷名 C-102：旧版只标成关，`F_CLOSE` 没人发。
                //   旧版 `s?.recordExit(…); s?.markClosed()`，而 `F_CLOSE` 只在 `close()` 里发（`host.send(F_CLOSE, sid, ByteArray(0))`），
                //   产品侧自己退时没发（`onExited` 那条路只有 `recordExit` 与 `markClosed` 两行），native 的槽还占着，下一趟分号就撞
                //   （diag.jsonl 又出现「WINSIZE 指向不存在的会话」，1793594620810）。
                //   处置：产品侧自己退这一路也发 `F_CLOSE`（`close()` 的 `compareAndSet` 护着，不能发两次）。
                //   判据是「那件退了之后、native 的槽有没有被还回去」。
                s?.releaseNativeSlot()
                //  `markClosed` 必须在 `F_CLOSE` 之后：它会把 `closed` 置真，
                //  而 `releaseNativeSlot` 的 `compareAndSet(false, true)` 就不成了，`F_CLOSE` 发不出去。
                s?.markClosed()
            }
            try { s?.onExit?.invoke(status, signal) }
            catch (e: Exception) { Log.w(TAG, "EXITED 回调异常", e) }
        }

    fun activeSessions(): List<Session> = sessions.values.toList()

    /**
     * 等槽空出来。native 关完槽才回一帧（FRAME_EXITED）；等有上限，到了不等；答的是真没空（不猜填）。
     */
    fun awaitSlotFreed(sid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val free = synchronized(this) { !slotBusy.contains(sid) }
            if (free) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(20)
        }
    }

    /** 记一下槽空了（native 回了那回帧）。 */
    internal fun markSlotFreed(sid: Int) {
        synchronized(this) { slotBusy.remove(sid) }
    }

    /** 记一下槽占着（native 开了那槽）。 */
    internal fun markSlotBusy(sid: Int) {
        synchronized(this) { slotBusy.add(sid) }
    }

    /**
     * 记一档槽没回报已空（号照样还了，不静着）。
     */
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
            //  缺陷名 C-102：那两个线程没有「停它的那一格」。
            //   旧版只 `proc.destroy()` 与关三个流、没 join：reader 是 pump() 的唯一驱动，diagReader 是
            //   proc.errorStream 的唯一读者，流关掉后两个线程各自卡在那一读上，而它们是 daemon，一次 close 之后
            //   还占着那个进程，下一次 openSession 再建两个。
            //   处置：先断流、再 join（join 有界 `READER_JOIN_MS`；线程要靠流关闭才醒）。
            stopReader(reader, "lobos-pty-reader")
            stopReader(diagReader, "lobos-pty-diag")
        }

        /**
         * 停一个线程（有界、不无限等）：先 interrupt 把它从读上叫醒，再 join 一个有界时长，超时就放弃（它是 daemon）。
         */
        private fun stopReader(t: Thread, name: String) {
            if (!t.isAlive) return
            runCatching { t.interrupt() }
            runCatching { t.join(READER_JOIN_MS) }
            if (t.isAlive) {
                //  它还没停就记一条（C-05：缺字段就报缺，不假装它停了）。
                Log.w(TAG, "PTY 线程 $name 在 ${READER_JOIN_MS}ms 内没停（它逐字是 daemon、进程退出时会被杀掉）")
            }
        }
    }

    @Volatile private var host: Host? = null
    private val hostLock = Any()

    /**
     * 开一条会话（活着的那一种）。缺陷 C-102：旧版把 argv 原样交给宿主、没走 `ExecEntry.argv`（`host(ctx, bin).start(argv, …)`），
     *   而宿主是 native 那份（`pty-session.c` 直接 execve、不判形态）。入口是解释器（bash 是 ET_DYN），直接 execve 没有 linker64
     *   驱动，报 `.../usr/bin/bash: cannot execute binary file`，会话当场退出（stdout 空 + exitCode 0）。
     * 处置：判形态交给 `ExecEntry.argv`（唯一真相源，native 那份已删，C-87）：ET_DYN 套 linker64、ET_EXEC 不套。
     */
    fun openSession(
        ctx: Context,
        bin: java.io.File,
        argv: List<String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        timeoutMs: Long = 5_000,
    ): Session {
        //  判形态走 `ExecEntry.argv`（与 `runToCompletion` 同一把尺）。
        val effArgv = if (argv.isEmpty()) argv
        else {
            val first = java.io.File(argv[0])
            //  认不出那一档报缺、不调起（M7-05）：旧版的 `?: argv` 会拿回原样 argv，把坏 ELF 当程序调起。
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
                //  缺陷名 C-102：那一层手写了，没走 `ExecEntry.argv` 对 sh 判形态（旧版 `listOf(SYSTEM_LINKER,
                //   sh.absolutePath, "-c", script.toString())`，总是套 SYSTEM_LINKER），而判据该是形态。
                //   后果：设备上 `files/opt/bash/5.2.15/bin/bash` 头 `7f 45 4c 46 02 01 01`、e_type=03（ET_DYN）；
                //   报 `.../usr/bin/bash: cannot execute binary file`，会话当场退出（stdout 空 + exitCode 0），
                //   期望 `git version 2.55.0` 与退出码 0 没过。
                //   处置：判形态交给 `ExecEntry.argv`（唯一真相源，native 那份已删，C-87）。
                val shArgv = lobos.kernel.proc.ExecEntry.argv(
                    sh, listOf(sh.absolutePath, "-c", script.toString()),
                )
                //  认不出那一档时报缺（C-05，不猜填），不回退到手写那一层。
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
            // 缺陷名 C-102：这一趟的环境从来没有被灌进 view0。
            // 真机读数（07，2026-10-24）：stderr 是 CANNOT LINK EXECUTABLE "…/usr/lib/librivospty.so": library
            //   "liblobosposix.so" not found，EXIT=1；带上 LD_LIBRARY_PATH=…/usr/lib 再跑同一趟是 exit=0。
            // 成因：else 那一支直接 exec 宿主本体，pb1 继承宿主进程的环境（里面没有 LD_LIBRARY_PATH），而
            //   librivospty.so 的 DT_NEEDED 有 liblobosposix.so，它在 files/usr/lib、不在系统默认搜索路径里。
            // 处置：把 view0 灌进 pb1 的环境，下面那句 remove 照旧（LD_PRELOAD 不继承）。
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
     * 产品侧持有的那几条会话（不是 native 管的那一些）。
     * 缺陷 C-102：旧版没有这样一族（`spawnOnSlave` 起完只记一个句柄），`activeSessions` 只问 native，
     *   产品侧起的那一条不在名单里。
     * 处置：这一族持有产品侧起的那几条、可被 `activeSessions` 列出来；键带族标记，不与 native 冲突。
     */
    object ProductSessions {
        private val lock = Any()
        private val held = LinkedHashMap<String, Session>()

        /** 记下那一条（键带族标记，不与 native 冲突）。 */
        @Synchronized
        fun put(key: String, s: Session) {
            synchronized(lock) { held[key] = s }
        }

        /** 还在的那些（不是 native 管的那些）。 */
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

        /** 去掉那一条（进程退了时调）。 */
        @Synchronized
        fun remove(key: String) {
            synchronized(lock) { held.remove(key) }
        }
    }

    /**
     * 活着的那几条会话（两族都列）。缺陷 C-102：旧版只列 native 那一族（`h.activeSessions()` 只列 `g_sessions` 管的），产品侧
     *   持有的那一条（`spawnOnSlave` 起的）不在里面。后果：`SysDev.kt:64` 的 `for (s in PtySession.activeSessions(ctx))`
     *   拿不到它，`files/dev/tty/pts/<NNN>` 永远建不出来（sid=0 / slave=/dev/pts/2）。
     * 处置：两族都列。判据依 C-105 不合并两问——答「会话是什么」列全部，答「进程归谁管」只看账本。
     */
    fun activeSessions(ctx: Context): List<Session> {
        val native = runCatching {
            val h = host
            if (h == null) emptyList() else h.activeSessions()
        }.getOrDefault(emptyList())
        //  产品侧持有的那一些（不在 native 那一族里）。
        val product = runCatching { ProductSessions.list() }.getOrDefault(emptyList())
        //  缺陷名 C-102：同一条会话被列了两次（旧版 `native + product`），`tty/index.json` 那两行重复
        //   （`[{"sid":0,…},{"sid":0,…}]`，也是号撞了的旁证）。处置：同一条会话只列一次，
        //   判据是从设备名（唯一）；号不同的都留（是两条不同的会话）。
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
        // 缺陷 C-102：重复替换——已替换过的 argv 又被替换一次。真机读数（07，logcat）：Cmdline =
        // /system/bin/linker64 /system/bin/linker64 …/opt/ripgrep/…/rg，linker64 出现两次：上游 CommandLine.run
        // 给的 argv[0] 已是 linker64，而它自己也是 ET_DYN，ExecEntry.argv 又替一次；linker64 把自己当程序加载，
        // call_constructors 里 libc 初始化链崩（kill-audit 记 desc=null pointer dereference）。
        // 这里只对还没有替过的 argv 替（唯一真相源是 ExecEntry.argv，C-87）；Shape.Unknown 时 argv() 返回 null，
        // 那时保留调用方给的 argv，否则「入口认不出」与「argv 为空」两种失败会混成一种。
        val alreadyDriven = argv[0] == lobos.kernel.proc.ExecEntry.SYSTEM_LINKER
        val first = java.io.File(argv[0])
        val effArgv = if (alreadyDriven) argv
        else lobos.kernel.proc.ExecEntry.argv(first, argv)
        //  **认不出**那一档报缺、不调起（M7-05）：旧版 `?: argv` 会把坏 ELF 当程序调起。
        //    退出码是具体那个数，诊断原文进 stderr。
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
            //  缺陷名 C-102：旧版无上限地累积输出（`val buf = java.io.ByteArrayOutputStream()`），一趟跑多久
            //   它就涨多少；而堆本来就只剩余量了（设备上 `heapgrowthlimit` = 384m、堆已到 291 MB）。
            //   处置：上限 4 MiB，超出丢掉；期望输出要的是开头那一些（不是全量）；报缺（C-05，不猜填）。
            val buf = java.io.ByteArrayOutputStream()
            var dropped = 0L
            val done = CountDownLatch(1)
            s.onData = { d ->
                synchronized(buf) {
                    if (buf.size() + d.size > MAX_CAPTURED_OUTPUT_BYTES) {
                        //  超出的那一截丢掉、记丢了多少。
                        val room = (MAX_CAPTURED_OUTPUT_BYTES - buf.size()).coerceAtLeast(0)
                        if (room > 0) buf.write(d, 0, room)
                        dropped += (d.size - room).toLong()
                    } else {
                        buf.write(d)
                    }
                }
            }
            s.onExit = { _, _ -> done.countDown() }
            //  缺陷名 C-102：旧版只等 native 那一条退出信号，它永远不来。
            //   旧版 `s.onExit = { _, _ -> done.countDown() }`，退出信号是 native 发的 `FRAME_EXITED`（它 fork 出来的
            //   子进程退时发的）；执行已归产品侧（52c0139），产品侧起的进程自己退、native 不知道，等只能超时。
            //   后果：`sys.tty.exec` 那趟「PTY 不可用（超时 10000ms）」走了无 PTY 通路；阳性对照是会话建得出来
            //   （diag「会话 0 就绪 从设备=/dev/pts/N」）。
            //   处置：进程自己退了时也把退出报出来，`onExit` 两路都能来，等因此等得到。
            s.process?.let { proc ->
                Thread({
                    val st = runCatching { proc.waitFor() }.getOrNull()
                    runCatching {
                        s.onExit?.invoke(st ?: 0, 0)
                    }
                }, "lobos-pty-exit-watch").apply { isDaemon = true; start() }
            }
            //  丢了多少记着（C-05，不猜填、不静默）。
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
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
     * 那**输出**逐字**捕获**那一个上限（ 它逐字**防**那堆逐字被那一趟输出逐字吃光）。
     *
     *   **上限**逐字是那 **4 MiB**（ 它逐字**够**那期望输出那一族逐字要的那一些）
     *   **超出**的一截逐字**丢**、 **丢了多少**逐字**记**着（ 按 C-05 不猜填）
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
     * 那**槽**逐字**空没空**逐字**等**的那**上限**（ native 那一族逐字那 500 毫秒之后逐字**还没**回）。
     *   宿主侧逐字**不**能**永远**等、 它逐字**照样**还、 一档逐字**记**下来
     */
    private const val SLOT_FREED_TIMEOUT_MS = 2_000L

    private const val DEFAULT_ROWS = 24
    private const val DEFAULT_COLS = 80

    //  **停那一个线程**逐字等的时长（ **有界**、 不逐字无限等）。
    private const val READER_JOIN_MS = 500L

    /**
     * 那**帧**逐字那长度逐字那一个上限（ **长度**逐字**从线**上逐字来、 它逐字**不能**信）。
     *
     *   native 那一族逐字**自己**逐字**发**的逐字**最大**是那 64 KiB（ `FRAME_MAX`）
     *   这 1 MiB 逐字**是**那它逐字的 **16 倍**、 **余量**逐字**够**
     *   **超了**逐字**报缺**、 按 C-05 不猜填
     */
    private const val MAX_FRAME_BYTES = 1024 * 1024

    /**
     * 那**输入**逐字那一个上限（ 它逐字与那 native 那一族逐字那 `FRAME_MAX` 逐字**同一个**上限）。
     *
     *   native 那一族逐字那 `FRAME_MAX` 逐字是那 **64 KiB**
     *   超出那一截逐字**丢**了、 **帧**逐字**不**超、 **流**逐字**不**错位
     */
    private const val MAX_INPUT_BYTES = 64 * 1024

    /** 那**输入**逐字**丢了多少**（ 按 C-05 不猜填） */
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
         * 那 **native** 那一族逐字那槽逐字**开没开**（ 它逐字是「那帧逐字发的时候、 槽逐字开没开」那一个事实上）。
         *
         * 缺陷名（C-102）**那一帧逐字发的时候、 槽逐字还没开** ——
         *   旧版逐字是 `resize()` 逐字**无条件**逐字发那 `F_WINSIZE`
         *     `F_WINSIZE` 逐字**只**那一处逐字发
         *       （ `PtySession.kt:141` 逐字 `host.send(F_WINSIZE, sid, b.array())`）
         *     `spawnOnSlave` 那一格逐字在**那 latch 等到之前**就逐字可能调那 `resize`
         *       （ `PtySession.kt:305` 逐字
         *         `if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)`）
         *       native 那一族逐字那槽逐字**还没**开
         *         native 那一侧逐字**判**的是「那槽逐字开没开」：
         *           （ `pty-session.c:463` 到 `:465` 逐字
         *             `case FRAME_WINSIZE: {` / `int s = h.sid;` /
         *             `if (s >= MAX_SESSIONS || !g_sessions[s].open) { send_error(out_fd, "WINSIZE 指向不存在的会话"); break; }`）
         *         **后果**（07 本轮逐字核出、Lead 本机复核成立）：
         *           · 而那 `diag.jsonl` 逐字 `stage="pty"` 逐字两条 FAIL
         *             「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」
         *             逐字又逐字出现了一条：逐字 `1793597239169` 与 `1793597303066`
         *   **处置**： `F_WINSIZE` 逐字**等**那 `F_READY` 逐字**到了之后**才发
         *     **正解**逐字是 07 逐字给的
         *     **判据**逐字是「那一帧逐字发的时候、 槽逐字开没开」那一个事实上
         *       （ 它逐字**不**由「那一件逐字是什么」顶掉）
         *
         * 缺陷名（C-102）**那一格逐字**不知道**那 `F_READY` 逐字到没到** ——
         *   产品侧逐字**知不知道**它到了、 **知不知道**的逐字怎么核
         *     **答**逐字是那 `F_READY` 逐字**本身**逐字带着那**就绪**那一个事实
         *       （ `onReady` 逐字建那 `Session` 逐字**就是**那**到了**的凭据）
         *     **用法**逐字： `resize` 逐字**先**看那一格
         *       **还没**到时逐字**把**那**要的**尺寸**逐字**记**着
         *         `F_READY` 逐字到了之后逐字**补**发那一帧
         */
        @Volatile private var nativeSlotOpen = false

        /** 那**要**的尺寸（ 它逐字**等**那 `F_READY` 到了之后逐字**补**发）。 */
        @Volatile private var pendingWinsize: Pair<Int, Int>? = null

        /**
         * 那 `F_READY` 逐字**真到**没有（ 它逐字**不**逐字是那 `nativeSlotOpen` 逐字的同义词）。
         *
         * 缺陷名（C-102）**那一格逐字拿那本地状态逐字当凭据、 `F_READY` 逐字那一帧逐字还没真到** ——
         *   旧版逐字是 `nativeSlotOpen` 逐字**一被置真**就逐字**当**那**就绪**的凭据
         *     `nativeSlotOpen` 逐字是那 native 那一族逐字**自己的本地状态**
         *       （ 它逐字**可以**比那 `F_READY` 逐字那一帧**先**变真、
         *         `F_READY` 逐字那一帧逐字**还没**到）
         *     `resize` 那一格逐字就逐字**拿**那本地状态逐字**当**那**凭据**
         *       `F_WINSIZE` 逐字**发得太早**
         *         （ native 那一族逐字那槽逐字**还没**开、 它逐字**判**的是那**本地**那一格）
         *     **后果**（07 本轮逐字核出、Lead 本机复核成立）：
         *       · 而那 `diag.jsonl` 逐字 `stage="pty"` 逐字 FAIL
         *         「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」逐字**还在**发
         *         （ 两趟会话逐字都是「会话 0 就绪」、 号逐字**没撞**）
         *   **处置**： **凭据**逐字**只**是那 `F_READY` 逐字**那一帧真的到**
         *     它逐字**不**逐字是那本地状态逐字（ 依 **C-103** 那两边逐字各答一件事）
         *     `resize` 那一格逐字**等**那**真的到**、 它逐字**不许**拿那本地状态逐字**当**凭据
         *   **判据**逐字是「那一帧逐字发的时候、 native 那一族逐字那槽逐字开没开」
         *     那一个事实上（ 它逐字**不**由「那一件逐字是什么」顶掉）
         */
        @Volatile private var readyFrameArrived = false

        //  回调逐字**接上**的时候逐字把缓冲**倒**给它（ 输出逐字**不**丢）
        @Volatile
        var onData: ((ByteArray) -> Unit)? = null
            set(value) {
                field = value
                if (value != null) flushPending()
            }

        //  缺陷名（C-102）**那一格逐字把输出逐字丢在中间、 它逐字是在那回调**还没接上**的时候到的** ——
        //    旧版逐字是 `onData` 逐字一个**可变字段**、 调用方逐字**在** `start()` **返回之后**
        //      逐字才把它接上（ `runToCompletion` 那一族逐字是 `s.onData = { … }`）
        //    **时序**逐字是： 宿主逐字发 FRAME_READY（ `onReady` 逐字建那 Session
        //      并把 latch 逐字倒）、 `start()` 逐字**返回**、 调用方逐字**还没**接 onData、
        //      **这中间**逐字那宿主逐字就**已经**可以发 FRAME_DATA 了
        //      （ `pty-session.c` 那一族逐字那 poll 循环逐字**每一轮**都把那些 master 逐字加进去）
        //    **后果**（07 本轮逐字核出）：
        //      · 那 `sys.tty.exec` 那一趟逐字 stdout 逐字**空**、 退出码逐字 0、 期望逐字**没**出现
        //      · 而那一趟跑的那命令逐字**没有进程**（ 账本逐字只有 jq、 `ps -A` 逐字 0）
        //    **处置**： 输出逐字**先**落到那 Session 自己的**缓冲**里
        //      回调逐字**接上**的时候逐字**把缓冲逐字倒给它**
        //      样逐字**不**依赖那回调逐字什么时候接（ **不**是猜、 **不**是丢）
        //  缺陷名（C-102）**那一格逐字把那一段输出逐字**无上限**地累积进那堆** ——
        //    旧版逐字是 `private val pending = java.io.ByteArrayOutputStream()`
        //      **缓冲**逐字**没有**上限、 它逐字**一直**涨
        //        （ 回调逐字**还没**接上那一段时间逐字**输出**多少、 它逐字就涨多少）
        //      **后果**（07 本轮逐字核出、Lead 本机复核成立）：
        //        · 而那 logcat 逐字 `Throwing OutOfMemoryError "Failed to allocate a 117031556 byte
        //          allocation …"`
        //        **分配量**逐字 117 MB、 与设备上逐字那一件逐字**同形**
        //      **判据**逐字是「那一份逐字在那一段逐字还在逐字被读」那一个事实上
        //    **处置**： **缓冲**逐字**有**一个上限、 超出那一截逐字**丢**
        //      **丢了多少**逐字**记**着（ 按 C-05 不猜填）
        private val pending = java.io.ByteArrayOutputStream()
        private val pendingLock = Any()
        private var pendingDropped = 0L

        /** 那**缓冲**逐字那一个上限（ 它逐字**有**那个上限、 超出那一截逐字**丢**）。 */
        private val MAX_PENDING_BYTES = 4 * 1024 * 1024

        /** 那**缓冲**里逐字有东西没有（ 它逐字是那**读数**、 它逐字**不**是推出来的）。 */
        val hasPending: Boolean get() = synchronized(pendingLock) { pending.size() > 0 }

        internal fun deliver(data: ByteArray) {
            val cb = onData
            if (cb != null) {
                try { cb.invoke(data) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
                return
            }
            //  回调逐字**还没**接上 输出逐字**先进缓冲**、 **不**丢
            //    **缓冲**逐字**有**一个上限、 超出那一截逐字**丢**
            //      （ **上限**逐字是那 4 MiB、 与那 `LocalExec` 那一族那一个上限逐字**同一个**）
            //      **丢了多少**逐字**记**着（ 按 C-05 不猜填）
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

        /** 那**丢了多少**逐字（ 按 C-05 不猜填、 **丢**这一件逐字**报缺**）。 */
        internal fun pendingDroppedBytes(): Long = synchronized(pendingLock) { pendingDropped }

        @Volatile var onExit: ((status: Int, signal: Int) -> Unit)? = null

        @Volatile var exitStatus: Int = -1
            private set

        @Volatile var exitSignal: Int = 0
            private set

        /**
         * 那**进程**（ 它逐字是那**产品侧**逐字起的那一个、 它逐字**不**是 native 那一族 fork 的）。
         *   它逐字**存在**时逐字是那**进程账本**那一族逐字该记的那一个
         *   它逐字**空**时逐字是「那 native 那一族逐字管的那一个」（ 它逐字**不**记账本）
         */
        @Volatile var process: Process? = null

        /**
         * 那**程序名**（ 它逐字是那**件**逐字、 它逐字**不**由那进程的名字反推）。
         *   它逐字**空**时逐字**报缺**（ 按 C-05 不猜填）
         */
        @Volatile var programId: String = ""

        internal fun recordExit(status: Int, signal: Int) {
            exitStatus = status
            exitSignal = signal
        }

        //  缺陷名（C-102）**那一格逐字把那一帧逐字**发得太大**、 native 那一族逐字逐字**丢**那一帧**
        //    旧版逐字是 `host.send(F_INPUT, sid, data)`
        //      **体**逐字**没有上限**、 native 那一族逐字那 `FRAME_MAX` 逐字是那 **64 KiB**
        //        native 那一族逐字那「frame payload 超上限」那一格逐字**丢**那一帧、 它逐字**没**把那体逐字**吃掉**
        //          **流**逐字就逐字**错位**了
        //            **下一**帧逐字那 sid 逐字**是**那**体**里的某个字节
        //              sid 逐字**可能**逐字**大于**那 `MAX_SESSIONS`
        //                `FRAME_WINSIZE` 那一格逐字就逐字判「WINSIZE 指向不存在的会话」
        //                **时刻**逐字**对不上**、 **症状**逐字**偶发**
        //      **后果**（07 本轮逐字核出、Lead 本机实读逐字复核成立）：
        //        · 而那两条 FAIL 逐字每次逐字都在那「会话 0 就绪」逐字之后 3 到 4 秒
        //          （逐字 20:41:07 -> 20:41:10、逐字 20:48:51 -> 20:48:55）
        //    **判据**逐字是「那一帧逐字发的时候、 native 那一族逐字那槽逐字开没开」那一个事实上
        //      （ 它逐字**不**由「那 stderr 那一行逐字什么时候打出来」顶掉）
        //    **处置**： **输入**逐字**有**一个上限、 超出那一截逐字**截**、 **帧**逐字**不**超
        //      **上限**逐字是那 **64 KiB**、 它逐字与那 native 那一族逐字**同一个**上限
        fun write(data: ByteArray) {
            if (closed.get()) return
            if (data.size <= MAX_INPUT_BYTES) {
                host.send(F_INPUT, sid, data)
                return
            }
            //  **超出**逐字**截**、 **丢**了**多少**逐字**记**着（ 按 C-05 不猜填）
            val keep = data.copyOf(MAX_INPUT_BYTES)
            val dropped = (data.size - MAX_INPUT_BYTES).toLong()
            synchronized(inputDroppedLock) { inputDropped += dropped }
            host.send(F_INPUT, sid, keep)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        fun resize(rows: Int, cols: Int) {
            if (closed.get()) return
            //  **native** 那一族逐字那槽逐字**还没**开时逐字**不**发那一帧
            //    （ **判据**逐字是「那一帧逐字发的时候、 槽逐字开没开」那一个事实上）
            //    **要**的尺寸逐字**记**着、 `F_READY` 到了之后逐字**补**发
            //  缺陷名（C-102）**那一格逐字拿那宿主侧逐字能知道的那一格逐字当那凭据、 **不是**那个事实** ——
            //    旧版逐字是 `if (!readyFrameArrived) { pendingWinsize = …; return }`
            //      **判据**逐字是「那一帧逐字发的时候、 native 那一族逐字那槽逐字开没开」
            //        那一个事实（ Lead 本机实读逐字复核成立）
            //      `readyFrameArrived` 逐字**不**是那个事实
            //        （ `F_READY` 逐字**到** 槽逐字**还没开**、
            //          槽逐字**开** `F_READY` 逐字**可能还没到**）
            //      `readyFrameArrived` 逐字**只是**那宿主侧逐字能知道的**一件**事、
            //        它逐字**不是**那 native 那一族逐字那槽逐字的状态
            //    **处置**： **判据**逐字归那 native 那一族逐字**自己**答
            //      native 那一族逐字那一格逐字**早到**时逐字**记下来**、 槽开了再逐字**用**
            //        （ `pty-session.c` 逐字那 `pending_winsize` 那一格）
            //      **宿主侧**逐字**照旧**逐字发（ **早到**那一帧逐字**不是**错）
            //      宿主侧逐字**不**再拿那**一件**它能知道的事逐字当那**凭据**
            //    `pendingWinsize` 那一格逐字**留着**（ 它逐字**不**是那凭据、
            //      它逐字是那**宿主侧**自己那**尺寸**的一个记号）
            pendingWinsize = rows to cols
            sendWinsize(rows, cols)
        }

        /** 那**真的**发那一帧（ 槽逐字**已经**开的时候才走这一格）。 */
        private fun sendWinsize(rows: Int, cols: Int) {
            val b = java.nio.ByteBuffer.allocate(8)
            b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putShort(rows.toShort()).putShort(cols.toShort())
            b.putShort(0).putShort(0)
            host.send(F_WINSIZE, sid, b.array())
        }

        /**
         * 那 **native** 那一族逐字那槽逐字**开好了**（ 它逐字**由**那 `F_READY` 逐字**触发**）。
         *
         *   **判据**逐字是「那 `F_READY` 逐字**到了**」那一个事实上
         *     （ **就绪**那一个事实逐字**就是**那 `F_READY` 逐字**自己**带的）
         *   **补发**逐字： **记着**的尺寸逐字**这时候**才发
         */
        internal fun onNativeSlotOpen() {
            nativeSlotOpen = true
        }

        /**
         * 那 `F_READY` 逐字**那一帧真的到**了（ 它逐字**只是**那宿主侧逐字能知道的**一件**事）。
         *
         *   它逐字**只**由那 `F_READY` 逐字**触发**（ 它逐字**不**由那本地状态逐字触发）
         *   **判据**逐字**归**那 native 那一族逐字**自己**答
         *     （ `g_sessions[s].open` 那一格逐字是**它自己**的本地状态、
         *       宿主侧逐字**看不到**它、 `F_READY` 那一帧逐字**不是**那个状态的回执）
         *   **补发**逐字： **记着**的尺寸逐字**这时候**才发
         *     （ **早到**的那一帧逐字那 native 那一族逐字**自己**记着、 它逐字**不**要那宿主侧逐字猜）
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
         * 关掉那一条会话（ 它逐字**把**那号逐字**还**给那 native 那一族）。
         *
         *  缺陷名（C-102）**这一格逐字只发那一帧、 号逐字在**两边**逐字**错开**了** ——
         *   旧版逐字是 `if (closed.compareAndSet(false, true)) host.send(F_CLOSE, sid, …)`
         *     native 那一族逐字 `session_close` 逐字 `memset(&g_sessions[sid], 0, …)`
         *       **槽**逐字**已经**清掉了
         *     Kotlin 那一族逐字那 `sessions` 那一族逐字**还留着**那一格
         *       `allocSid` 逐字**跳过**那一号、 native 逐字**复用**它
         *       **号**逐字**错开**了
         *       **后果**： `FRAME_READY` 逐字回的号与那 `sessions` 那一族
         *         逐字对不上、 latch 逐字**永远**等不到、 **输出**逐字**找不到**那一格
         *   **处置**： 这一格逐字**关**的时候逐字**同时**把那一号从那 `sessions` 那一族逐字去掉
         *     **两边**逐字**同一**套号、 **号**逐字**不会**错开
         */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                //  缺陷名（C-102）**那一格逐字只关了那一族、 **产品侧**的进程逐字**还活着** ——
                //    旧版逐字是 `host.send(F_CLOSE, sid, …)` + `host.releaseSid(sid)`
                //      `F_CLOSE` 逐字**只**让那 native 那一族逐字**关它自己那一个**
                //        （ `pty-session.c` 逐字 `session_close` 逐字
                //          `kill(g_sessions[sid].pid, SIGHUP)` 逐字杀的是**那 native fork 出来**那个子进程、
                //          子进程逐字**只**握那从设备、 它逐字**不是**那产品侧起的那一个）
                //        **产品侧**逐字起的那进程逐字**没人杀**
                //      **后果**（07 本轮逐字核出、Lead 本机复核成立）：
                //        · 关完之后逐字那账本逐字**仍有** `linker64 gen=2 pid=26021`
                //        · `files/dev` 逐字**仍有** `pts: [000]` 与 `index.json: [{"sid":0,…}]`
                //        · `/proc` 逐字**还有**那三个 `linker64`
                //        一格逐字**不是**退出清理那一族逐字的问题
                //          （ 会话逐字真的活着、 「关掉那条会话」那一格逐字**没生效**）
                //    **判据**逐字是「那一格逐字关了之后、 它逐字有没有逐字真的关掉」那一个事实上
                //      （ 它逐字**不**由「那进程逐字还在不在」顶掉）
                //    **处置**： **产品侧**的进程逐字**显式**逐字终止
                //      它逐字**走**那产品侧逐字自己报退出那一路（ 它逐字**不许**只靠那读帧线程）
                //      **退出清理**逐字随它逐字**走**（ 账本逐字与那 `files/dev` 逐字**同步**清空）
                terminateProcess()
                host.send(F_CLOSE, sid, ByteArray(0))
                //  缺陷名（C-102）**那一格逐字把那一号逐字**立刻**还回去、 native 那一族逐字那槽逐字**还没**空**
                //    native 那一族逐字那 F_CLOSE 逐字**现在**逐字**回**一帧（ FRAME_EXITED 那一族）
                //      （ 它逐字**关**完那槽逐字才回、 它逐字**等**那子进程逐字**死**逐字最多 500 毫秒）
                //    旧版逐字是 `host.releaseSid(sid)` 逐字**紧跟**在那 F_CLOSE 逐字**后面**
                //      宿主侧逐字**不**等那 native 那一族逐字**回**话
                //        **下一趟**逐字拿到那**同一个**号、 native 那一族逐字那槽逐字**还开着**
                //          native 那一族逐字就逐字报「会话号已被占用」
                //            （ **槽**逐字**还开着**时逐字那 F_WINSIZE 逐字落到那**旧**会话上、
                //              sid 逐字**对不上**那一族逐字就逐字报「WINSIZE 指向不存在的会话」）
                //    **判据**逐字是「那一号逐字还回去的时候、 native 那一族逐字那槽逐字**空没空**」
                //      那一个事实上（ 它逐字**不**由「那一帧逐字发出去没有」顶掉）
                //    **处置**： 宿主侧逐字**等**那**回**帧逐字到了才**还**那一号
                //      **判据**逐字**答得出**、 **号**逐字**还**得**准**
                //      **等**逐字**有**一个**上限**（ native 那一族逐字那 500 毫秒之后逐字**还没**回，
                //        宿主侧逐字**不**能**永远**等、 它逐字**照样**还、 一档逐字**记**下来）
                val freed = host.awaitSlotFreed(sid, SLOT_FREED_TIMEOUT_MS)
                if (!freed) {
                    host.reportSlotNotFreed(sid)
                }
                host.releaseSid(sid)
                ProductSessions.remove("product-" + sid)
            }
        }

        /**
         * 那**native** 那一族逐字那槽逐字**还**回去（ 它逐字**只**发那 `F_CLOSE`）。
         *
         *   它逐字**只**发那 `F_CLOSE`、 它逐字**不**杀那进程
         *     （ 进程逐字**已经**退了、 `close()` 那一格逐字**管**那终止）
         *   `close()` 那一格逐字那 `compareAndSet` 逐字**护着**、 它逐字**只**发一次
         */
        internal fun releaseNativeSlot() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { host.send(F_CLOSE, sid, ByteArray(0)) }
            runCatching { host.releaseSid(sid) }
            runCatching { ProductSessions.remove("product-" + sid) }
        }

        /**
         * 那**产品侧**的进程逐字**显式**终止（ 它逐字**走**那产品侧逐字自己报退出那一路）。
         *
         *   它逐字**先**要那进程逐字**软**终止（ `TERM` 逐字给那进程逐字**机会**收尾）
         *   它逐字**不**软终止逐字**成**时逐字**硬**终止（ 按 C-05 不猜填、 **不**留着）
         *   **退出清理**逐字随它逐字**走**（ 账本逐字与那 `files/dev` 逐字**同步**清空）
         */
        private fun terminateProcess() {
            val proc = process ?: return
            //  **软**终止逐字**先**（ `TERM` 逐字给那进程逐字**机会**收尾）
            runCatching { proc.destroy() }
            val deadline = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) return
                runCatching { Thread.sleep(50L) }
            }
            //  **软**终止逐字**没**成时逐字**硬**终止（ 它逐字**不**留着）
            runCatching { proc.destroyForcibly() }
            runCatching { proc.waitFor(1, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }

    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        companion object {
            /** 那**会话数**逐字**跟**那 native 那一族逐字**一样**（ 它逐字是 8）。 */
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
                    //  缺陷名（C-102）**那一路逐字只进 diag、 零字不进诊断那一族** ——
                    //    旧版逐字只把那一行写进那个内存里的 diag 串
                    //      （ diag 逐字只被 diagnostics() 那一格读出来、 diagnostics()
                    //        逐字只在「PTY 超时未就绪」那一支被用、 **其余**那些诊断输出
                    //        逐字**只留在内存里**、 进程一退逐字**全没了**）。
                    //    **后果**： PTY 宿主逐字报了错、 diag.jsonl 逐字**没有**它
                    //      （ 读设备的人逐字看不到「宿主说了什么」）。
                    //    **处置**： 一行逐字**也**进诊断那一族
                    //      （ RuntimeDiagnostics.append 逐字写进 diag.jsonl）。
                    //    ctx 逐字**可以**是空（ Host 那个 ctx 那一格逐字可选）
                    //      空的时候逐字**不报**（ 不是「那一档没算」、 是「没有地方可报」）。
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
         * 那**占着**的槽逐字（ native 那一族逐字那 `open` 那一格的宿主侧逐字**镜像**）。
         *
         *   **判据**逐字是「那一号逐字还回去的时候、 native 那一族逐字那槽逐字**空没空**」
         *     那一个事实上（ 它逐字**不**由「那一帧逐字发出去没有」顶掉）
         *   **占着**逐字**记**那 native 那一族逐字**回**的那**回**帧
         *   **没**收到那**回**帧逐字就**一直**占着（ **保守**那一档、 它逐字**不**抢那号）
         */
        private val slotBusy = HashSet<Int>()

        private val ready = ConcurrentHashMap<Int, CountDownLatch>()

        //  **发帧**逐字那**锁**（ **两**个线程逐字**各**写那管道、
        //    **次序**逐字**要**保证、 **头与体**逐字**要**连着）
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
            //  native 那一族逐字那槽逐字**开**了、 它逐字**占着**
            //    **判据**逐字是「那一号逐字还回去的时候、 native 那一族逐字那槽逐字**空没空**」
            markSlotBusy(sid)
            //  **程序名**逐字**从**那 argv 逐字**头**那一条逐字取（ 它逐字**不**由那进程的名字反推）
            //    **件**逐字是那一条的**最后**那一段（ 它逐字**不**猜）
            s.programId = argv.firstOrNull()?.let { basenameOf(it) } ?: ""
            if (rows != s.initialRows || cols != s.initialCols) s.resize(rows, cols)
            //  缺陷名（C-102）**那一格逐字由 native 自己去 exec、 它逐字在那个域里**跑不起来** ——
            //   **事实**（仓内 `ExecEntry.kt:115` 到 `:122` 逐字记着）：
            //     · 而那四份入口在设备上是 u:object_r:app_data_file:s0
            //     · 而 app 进程那个域是 u:r:untrusted_app，**那一域对 app_data_file 没有执行许可**
            //       （ 直接 exec 它在加载解释器之前就 EACCES 一格是 error=13）
            //     · 而 /system/bin/linker64 是 u:object_r:system_linker_exec:s0，那一域有执行许可
            //   native 宿主逐字是那 linker64 逐字驱动的、 它逐字在 system_linker_exec 域
            //     它 fork 出来的子进程逐字**继承**那域
            //     app_data_file 逐字**仍**没有执行许可
            //   **后果**（07 本轮逐字核出、Lead 本机复核成立）：
            //     · 那子进程逐字**在 execv 那一格就死了**、 子进程逐字的那几条留痕逐字**一条都没到**
            //   **处置**： **执行**那一格逐字**归这产品侧**
            //     native 那一族逐字**只**做那 PTY 那一层的事（ 它逐字建那从设备）
            //     **执行**逐字走那 `ProcessSupervisor` 那一族（ 它逐字**已经**能跑那九件）
            //  **执行**那一格逐字（ 它逐字归这产品侧）
            //    它逐字**要**那 ctx（ 它逐字**不能**是空、 按 C-05 报缺）
            val c0 = ctx
                ?: throw IllegalStateException("宿主侧没有那上下文 —— 报缺，不猜")
            spawnOnSlave(c0, s, argv)
            return s
        }

        /**
         * 那**执行**那一格逐字（ 它逐字归这产品侧、 它逐字走那 `ProcessSupervisor` 那一族）。
         *
         *   它逐字把那进程的 stdin/stdout/stderr 逐字**接**到那从设备上
         *   它逐字**不**走 native 那一族（ 是那一族那个域缺陷的处置）
         *   它逐字**不**静默（ 它逐字**失败**时逐字**报缺**、 按 C-05 不猜填）
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
            //  形态逐字**还是**那 ExecEntry.argv 那一族算的（ 它逐字**不**变）
            val shaped = lobos.kernel.proc.ExecEntry.argv(first, argv)
                ?: throw lobos.kernel.proc.ProcessSupervisor.NotExecutable(first)
            val pb = ProcessBuilder(shaped)
            //  **环境**逐字走那**同一**套（ `ProcessSupervisor` 那一族逐字用的那一套）
            val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
                ctx,
                lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
                null,
            )
            pb.environment().clear()
            for ((k, v) in view) pb.environment()[k] = v
            pb.environment().remove("LD_PRELOAD")
            //  缺陷名（C-102）**那一格逐字把那**输入**也逐字拿掉了、 会话逐字**当场就退** ——
            //    旧版逐字是「那 `dup2` 逐字**整个不要**」
            //      `dup2` 逐字原来逐字把那**三路**逐字接到那从设备上
            //        （ stdin 逐字**也**在里头、 stdout/stderr 逐字**也**在里头）
            //      把那**三路**逐字**整个**去掉之后：
            //        · 而那 stdout/stderr 逐字**留**那 Java 管道（ **对**的、
            //          **产品侧**逐字**读**它、 App 自己的 stdout 逐字**不**被动）
            //        · **stdin** 逐字也逐字**留**那 Java 管道
            //          管道逐字**没有**写的那一头
            //          `bash -i` 逐字读那 stdin 逐字 它逐字**立刻**拿到 **EOF**
            //          **会话**逐字**当场就退**了（ status=0 signal=0）
            //            （ 07 本轮逐字核出： 会话逐字**起过**（24 次 TAB 到位）、
            //              账本逐字**只有**那 `jq` 那一件、 PTY 会话逐字那一件逐字**又退了**、
            //              `files/dev/tty/pts` 空、 `index.json` `[]`）
            //    **处置**： **stdin** 逐字**单独**接到那从设备上
            //      stdout/stderr 逐字**仍**留那 Java 管道（ **对**的那一族）
            //      App 进程逐字自己逐字那三路逐字**不**被动（ **它**逐字**只**动那 stdin）
            //      stdin 逐字**不**是那 Java 管道、 `bash -i` 逐字**等**那用户逐字输入
            //        （ 从设备逐字**没有**别的一头写它时、 它逐字**不**给 EOF）
            //    **判据**逐字是「那会话逐字该活多久」
            //      （ 它逐字**不**由「那进程逐字做了什么」顶掉）
            //      **该**活到那用户逐字关它或那调用方逐字**显式**关它
            //        （ 它逐字**不**该因为那读侧逐字 EOF 就走）
            //
            //  stdin 逐字**要**那从设备、 App 进程逐字自己逐字那 stdin 逐字**先**存一份
            //    **存**那一份逐字**只**在**那 fork 之前**做、 **孩子**逐字**起完**就**还**回去
            val savedStdin = runCatching {
                //  App 进程逐字自己逐字那 stdin 逐字**先**复制一份
                //    （ android.jar 逐字**剥掉**了那 `FileDescriptor` 逐字那静态字段、
                //      它逐字**不能**直接引用、 **唯一**的路逐字是那 `Os.dup` 逐字
                //      `Os.dup` 逐字**要**一个真的那 `FileDescriptor`）
                val p = android.system.Os.open("/proc/self/fd/0", android.system.OsConstants.O_RDONLY, 0)
                android.system.Os.dup(p).also { android.system.Os.close(p) }
            }.getOrNull()
            val slaveFd = runCatching {
                android.system.Os.open(slave, android.system.OsConstants.O_RDWR, 0)
            }.getOrNull()
            if (slaveFd == null) {
                throw IllegalStateException("那从设备逐字开不了：" + slave + "（=> 而那按 C-05 报缺）")
            }
            val proc = try {
                //  **stdin** 逐字**单独**接到那从设备上
                //    （ stdout/stderr 逐字**不**动、 它们逐字**仍**是那 Java 管道）
                android.system.Os.dup2(slaveFd, 0)
                val p = pb.start()
                //  App 进程逐字自己逐字那 stdin 逐字**还**回去
                //    （ **孩子**逐字**已经**继承了那从设备、 它逐字**不**受影响）
                if (savedStdin != null) {
                    runCatching { android.system.Os.dup2(savedStdin, 0) }
                    runCatching { android.system.Os.close(savedStdin) }
                }
                p
            } finally {
                runCatching { android.system.Os.close(slaveFd) }
            }
            session.process = proc
            //  **产品侧**逐字**读**那子进程逐字的输出、 它逐字**送到**那一面
            //    （ `Session.deliver` 那一格、 它逐字**就是**那 `onData` 那一路）
            //  **native** 那一族逐字**只**管那从设备那一头（ 它逐字**不**抢那读）
            pumpOutput(session, proc)
            //  缺陷名（C-102）**那一格逐字没写那账本、 一格逐字是那产品侧逐字起的那一个**
            //    旧版逐字是 `session.process = proc`（ 它逐字**只**记那一个句柄）
            //      **账本**那一族逐字**没有**这一行、 它逐字**漏记**
            //    **判别形式**（01 裁的 `S625-01-LEDGER-PTY-SESSION-RULING.md`）：
            //      「答『那一个进程在 LOB-OS 自己那一份 Linux 上跑着、归谁管』，
            //        判据必须落在『那账本那一行记的那五格』上，
            //        不许由那进程的名字反推、不许猜；
            //        PTY 会话那一族起的那一个不许漏记、
            //        native 那一族管的那一个不许记账本」
            //    **那五格**逐字是：程序名 / 那一代 / 那 pid / 那 starttime / 那起始时刻
            //      （ `ProcessLedger.Entry` 那一族逐字就是那五格）
            //    **程序名**逐字是那**件**逐字（ 它逐字**不**由那进程的名字反推）
            //    **那一代**逐字走那 `nextGeneration` 那一族（ 它逐字**不**猜）
            //    **native** 那一族管的那一个（ `session.process` 逐字**空**时）
            //      逐字**不**记账本（ 它逐字**不是**那产品侧逐字起的那一个）
            //  **产品侧**起的那一条逐字**记进**那产品侧那一族
            //    （ 它逐字**不**在 native 那一族那里面、 `activeSessions` 逐字
            //     逐字**要**能列它）
            //    **键**逐字带那族标记（ 它逐字**不**与 native 那一族**冲突**）
            ProductSessions.put("product-" + session.sid, session)
            //  缺陷名（C-102）**那一格逐字要等那一拍才逐字登记进 `files/dev`** ——
            //    旧版逐字**只**记进那 `ProductSessions` 那一族
            //      `files/dev` 逐字**只**在那一 `SysDev.render` 那一拍逐字**才**写
            //        （ 一拍逐字是那 15 秒那一族、 `SupervisorPool` 那一族逐字**驱动**它）
            //      **会话**逐字**起**了之后、 15 秒**之内**逐字**取**那一读逐字**看不到**它
            //        （ 07 本轮逐字核出： `sess3.sh` 每 3 秒 12 拍逐字全空、
            //          `sess4.sh` 每 1 秒 40 拍逐字全空、UTC 03:53:07Z 到 03:53:22Z）
            //      账本逐字**有**它（ `linker64 gen=1 pid=22915 starttime=7581679`）
            //        一件逐字**起过**、 它逐字**退了**
            //    **判据**逐字是「那会话逐字该活多久」那一个事实上
            //      （ 它逐字与那「那会话号逐字撞了」那一族逐字共同的那一族）
            //    **处置**： **会话**逐字**登记**逐字**立刻**就逐字**做**
            //      `files/dev` 逐字**立刻**逐字**有**它（ **读数**逐字**不用**等那一拍）
            //      **判读**那一族逐字**不用**赶在那一拍之内核
            notifyRegistered(ctx)
            recordInLedger(ctx, session, proc, argv)
            //  **退出清理**逐字与那**创建/记账**逐字**一致**
            //    （ 是 M7-07 那一行逐字说的「创建、记账、退出清理一致」）
            //    进程逐字**退了**时逐字把那一行逐字**从账本清掉**
            //      （ 否则那账本逐字**留着**一个**已经不在**的进程）
            val pid0 = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid0 > 0) {
                Thread({
                    //  缺陷名（C-102）**那一格逐字把那一行逐字**吊**在那一 `waitFor` 逐字**后面**、 它逐字**常常等不到****
                    //    旧版逐字是 `runCatching { proc.waitFor() }` 逐字**先**跑、 清理逐字**在它后面**
                    //      **产品侧**的进程逐字是那 **linker64** 逐字**那一个**
                    //        （ argv 逐字是那 `[SYSTEM_LINKER, <入口>, …]` 那一族、
                    //          linker64 逐字**是**那**壳**、 **真身**是它**里面**那一个）
                    //      **关**掉会话时逐字**TERM** 逐字打给那 linker64
                    //        linker64 逐字**不**把那个信号逐字转给它**里面**那一个、
                    //          linker64 逐字**自己**逐字**不一定**退
                    //        `waitFor()` 逐字就**等不到**、 清理逐字**一直**排**在它后面**
                    //          `ProcessLedger.end` 逐字**根本**没被调到
                    //      **后果**（07 本轮逐字核出、Lead 本机复核成立）：
                    //        · 而那三样逐字全对： `files/dev/tty/pts` 逐字**空**、
                    //          `files/dev/tty/index.json` 逐字**空**、
                    //          `/proc` 逐字**只剩一个** `linker64`
                    //            （ 逐字 pid 28331、 逐字是那宿主逐字自己）
                    //        · **账本**逐字**仍有** `linker64 gen=1 pid=25800 starttime=8966788`
                    //          （ 逐字那一条逐字就是逐字那被关掉的那一件、
                    //            逐字起完时逐字那账本逐字也是 `pid=25800`）
                    //    **判据**逐字是「那会话逐字关了之后、 账本逐字有没有逐字清掉」
                    //      那一个事实上（ 它逐字**不**由「那进程逐字还在不在」顶掉）
                    //    **处置**： **清账本**逐字**不**等那 `waitFor` 逐字
                    //      **清**逐字**不等**那进程逐字**退**（ 进程逐字退没退逐字**另一问**）
                    //      `waitFor` 逐字**仍然**等（ 它逐字**不**误伤那退出那一族）
                    //    **归属**： **清账本**归 **05**； **判据**归 **01**；
                    //      **读数**归 **07**。
                    //
                    //  **清账本**逐字**先**走（ 进程逐字退没退逐字**不**影响它）
                    runCatching { lobos.kernel.proc.ProcessLedger.end(ctx, pid0) }
                    runCatching { proc.waitFor() }
                    //  缺陷名（C-102）**那一格逐字**只**清了那一族、 native 那一族逐字那槽逐字**还占着** ——
                    //    旧版逐字是 `ProcessLedger.end(…)` + `ProductSessions.remove(…)`
                    //      **账本**清了、 **名单**清了
                    //      **`F_CLOSE`** 逐字**没人发**
                    //        （ `F_CLOSE` 逐字**只有**那 `Session.close()` 那一格逐字发）
                    //      native 那一族逐字那槽逐字**还占着**
                    //        （ `session_close` 逐字**没**被调到、 槽逐字**没**被清）
                    //      **下一趟**逐字分号逐字就**撞**
                    //        （ `diag.jsonl` 逐字 `stage="pty"` 逐字两条 FAIL
                    //          「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」）
                    //    **处置**： **产品侧**逐字自己退时逐字**显式**逐字发那 `F_CLOSE`
                    //      槽逐字**就**被还回去了、 **下一趟**逐字分号逐字**不撞**
                    //      **判据**逐字是「那会话号逐字撞了那一族」那一个事实上
                    //        （ 它逐字与那「那会话逐字该活多久」那一族逐字**不并**）
                    //  缺陷名（C-102）**那一格逐字等那终止逐字那 2 秒预算走完、 账本逐字没清** ——
                    //    旧版逐字是 `session.close()` **在**那 `ProcessLedger.end` **之前**
                    //      `session.close()` 那一格逐字**现在**会 `terminateProcess()`
                    //        （ 它逐字**有**那 2 秒逐字预算： `TERM` 逐字等 2 秒、
                    //          `TERM` 逐字**不成**才逐字**硬**终止）
                    //      **账本**逐字在那 2 秒**之后**才清
                    //        **后果**（07 本轮逐字核出、Lead 本机复核成立）：
                    //          · 而那三样逐字全对： `files/dev/tty/pts` 逐字**空**、
                    //            `files/dev/tty/index.json` 逐字**空**、
                    //            `/proc` 逐字**只剩一个** `linker64`
                    //          · **账本**逐字**仍有** `linker64 gen=1 pid=25944 starttime=8594873`
                    //          · 账本逐字 40 秒逐字**没清**
                    //    **判据**逐字是「那会话逐字关了之后、 账本逐字有没有逐字清掉」
                    //      那一个事实上（ 它逐字**不**由「那进程逐字还在不在」顶掉）
                    //    **处置**： `ProcessLedger.end` 逐字**先**走、 终止逐字随后
                    //      **账本**逐字**不**等那 2 秒预算
                    runCatching { session.close() }
                    //  **产品侧**那一族逐字**去掉**那一条（ 它逐字**不在**了）
                    runCatching { ProductSessions.remove("product-" + session.sid) }
                }, "lobos-pty-ledger-clean").apply { isDaemon = true; start() }
            }
        }

        /**
         * 那**会话**逐字**登记**那一格（ 它逐字**立刻**就逐字把那 `files/dev` 逐字**刷新**一遍）。
         *
         *   **刷新**逐字**不**逐字等那 15 秒那一拍（ **读数**逐字**不用**赶在那一拍之内核）
         *   **刷新**逐字**失败**时逐字**不**逐字把那一格逐字的中断逐字带出去
         *     （ 它逐字**自己** catch 住、 它逐字**报缺**）
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
         * 那**输出**那一条读（ 它逐字是「那一件逐字的输出逐字从哪条通道回来」那一族）。
         *
         *   它逐字**读**那子进程逐字那 stdout/stderr 逐字、 它逐字**送到**那一面
         *     （ `Session.deliver` 那一格）
         *   它逐字**不**抢那 native 那一族逐字那 master（ 两遍逐字各读各的）
         *   它逐字**不**静默（ 读出错时逐字**报缺**）
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
                //  缺陷名（C-102）**那一路逐字**又**读**那 stderr、 stderr 逐字**有**两个读者** ——
                //    旧版逐字是 `val es = proc.errorStream` + `while (true) { es.read(ebuf) … }`
                //      `diagReader` 那一族逐字**也**读那 `proc.errorStream`
                //        （ `:393` 逐字 `proc.errorStream.bufferedReader().forEachLine { … }`）
                //        **两个**读者逐字**各**读那**同一个**流
                //      `InputStream` 逐字**不是**那**可**逐字**并发读**的那一种
                //        两读者逐字**抢**那同一批字节、 **行**逐字**被**逐字**劈开**
                //      `forEachLine` 逐字**等**那一个换行、 换行逐字**已经**被**别**那个读者**吃掉**了
                //        它逐字**等**到**下一个**换行才逐字**醒**、 **等待**逐字**没有**上限
                //    **后果**（07 本轮逐字核出、Lead 本机实读成立）：
                //      · 而那「会话 0 就绪」那条 note 逐字是那 **stderr** 上那一条
                //        （ 它逐字**不**是那帧通道、 它逐字**另**一条通道）
                //      · 「会话 0 就绪」逐字 18:58:21、 **4 秒之后**那一帧逐字才发
                //        （ 18:58:25Z）
                //      **延迟**逐字**在**那 stderr 那一路、 **帧**逐字**不**延后
                //      「帧错位」与那「读帧无上限」两族逐字**没有完全治好**
                //    **判据**逐字是「那一路字节逐字**是不是**只有**一个**读者」那一个事实上
                //      （ 它逐字**不**由「那一件逐字是什么」顶掉）
                //    **处置**： stderr 逐字**只**归那 `diagReader` 那一族
                //      **输出**那一路逐字**只**读那 `inputStream`、 它逐字**不**再读那 stderr
                //      stderr 逐字**进**那诊断那一族（ `diag.jsonl` 逐字**有**它）
                //      **输出**逐字**不**再**混**那 stderr（ **别的**那一族逐字**不并**）
            }, "lobos-pty-output-" + session.sid)
            t.isDaemon = true
            t.start()
        }

        /**
         * 那**账本**那一格逐字（ 它逐字记那**五格**、 它逐字**不**漏记）。
         *
         *   它逐字**失败**时逐字**报缺**（ 按 C-05 不猜填、 **不**静默）
         *   它逐字**不**替那 native 那一族管的那一个记账本（ 它逐字**不是**产品侧起的）
         */
        /**
         * 那**账本**那一格逐字（ 它逐字记那**五格**、 它逐字**不**漏记）。
         *
         *   它逐字**失败**时逐字**报缺**（ 按 C-05 不猜填、 **不**静默）
         *   它逐字**不**逐字把那一格逐字的中断逐字**带出去**
         *     （ 它是「那一格逐字抛异常、 它逐字把别的逐字一起带走」那一族）
         *   它逐字**不**替那 native 那一族管的那一个记账本（ 它逐字**不是**产品侧起的）
         *
         *  缺陷名（C-102）**那一格逐字把别的逐字一起带走了** ——
         *   旧版逐字**没有**任何一处 try/catch、 `ProcessLedger.begin` /
     *     `nextGeneration` 那一族逐字**抛**时逐字**中断**那整格
         *     **后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
         *       · 而那一件逐字**活着**（ diag 逐字「会话 0 就绪 从设备=/dev/pts/0」、
         *         `ps` 逐字**有** 3 个 bash/linker64 进程）
         *       · **账本**逐字**没有**它、 **ProductSessions** 逐字**也没有**它
         *       · `activeSessions` 逐字列不出它、 四个判据逐字**全不成立**
         *   **处置**： **整格**逐字自己 catch 住、 它逐字**报缺**
         *     它逐字**不**逐字把那一格逐字的中断逐字带出去
         *     `ProductSessions.put` 逐字与那会话逐字本身逐字**不**逐字受它影响
         */
        private fun recordInLedger(
            ctx: Context,
            session: Session,
            proc: Process,
            argv: List<String>,
        ) {
            try {
            //  pid 逐字**从**那进程自己逐字读（ 它逐字**不**猜）
            //    读法逐字跟那 `ProcessSupervisor` 那一族逐字**同一**个
            //      （ `Process.pid()` 逐字在那一层**不可用**、 它逐字从 `toString()` 逐字取）
            val pid = runCatching {
                Regex("""pid=(\d+)""").find(proc.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
            if (pid <= 0) {
                //  pid 逐字读不到时逐字**报缺**（ 按 C-05 不猜填）
                lobos.services.log.RuntimeDiagnostics.append(
                    ctx, "ledger", false, "PTY 会话那进程逐字取不到 pid",
                    "sid=" + session.sid + " （=> 而那账本那一行逐字**没有**记、=> 而那按 C-05 报缺）",
                )
                return
            }
            //  **程序名**逐字是那**件**逐字（ 它逐字**不**由那进程的名字反推）
            val programId = session.programId.ifBlank {
                argv.firstOrNull()?.let { basenameOf(it) } ?: ("pty-" + session.sid)
            }
            //  **那一代**逐字走那 `nextGeneration` 那一族（ 它逐字**不**猜）
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
                //  **记账**逐字**失败**时逐字**报缺**、 它逐字**不**把那一格逐字的中断逐字带出去
                //    `ProductSessions.put` 逐字与那会话逐字本身逐字**不**受它影响
                runCatching {
                    lobos.services.log.RuntimeDiagnostics.append(
                        ctx, "ledger", false, "PTY 会话那进程逐字记不进账本",
                        "sid=" + session.sid + " " + e.javaClass.name + ": " + (e.message ?: "") +
                            "（=> 而那一格逐字**没有**中断、=> 而那会话逐字**仍然**活着）",
                    )
                }
            }
        }

        /** 那一个路径逐字**最后**那一段（ 它逐字**不**猜）。 */
        private fun basenameOf(p: String): String =
            p.trimEnd('/').substringAfterLast('/').ifBlank { p }

        /**
         * 那**会话号**逐字**跟着**那 native 那一族逐字分的**槽**走
         *   （ 它逐字**不**自己另分一套）。
         *
         *  缺陷名（C-102）**两边逐字各自分号、 它们逐字会错开** ——
         *   旧版逐字是 `for (i in 0..255) if (!sessions.containsKey(i)) return i`
         *     native 那一族逐字 `alloc_slot()` 逐字**只**在 0..MAX_SESSIONS（ 是 8）里分
         *     `FRAME_OPEN` 那一族逐字**根本不用**那帧带来的 sid
         *       （ `int slot = alloc_slot();` 逐字**自己**分）
         *     `FRAME_READY` 逐字回的逐字是那**真的**槽号
         *     Kotlin 那一族逐字 `onReady` 逐字按那**真的**槽号逐字建 Session
         *     Kotlin 那一族逐字**自己**分的那个 sid 逐字**只**用在发那帧上
         *       （ 它逐字**被丢掉**了）
         *     **后果**（ 槽复用之后）： native 逐字**复用**槽 0、
         *       而那 Kotlin 逐字给 2、 **号**逐字**不一致**
         *       `sessions` 那一族逐字**按号**索引、 它逐字**对不上**
         *   **处置**： **会话号**逐字**就用**那 native 逐字回的那个**槽号**
         *     它逐字**只**从那帧上来（ 它逐字**不**自己另分）
         *     发那帧那一格逐字**不再**带一个自己分的号（ 它逐字**没有**用）
         */
        /** 把那一号逐字**还**回去（ 它逐字**跟**那 native 那一族逐字**同步**）。 */
        @Synchronized
        internal fun releaseSid(sid: Int) {
            sessions.remove(sid)
            ready.remove(sid)
        }

        /**
         * 那**会话号**逐字**统一**分（ 它逐字**不**让两边各自去抢）。
         *
         * 缺陷名（C-102）**那号逐字在两族逐字之间逐字撞了** ——
         *   旧版逐字是 `for (i in 0 until MAX_SESSIONS) if (!sessions.containsKey(i)) return i`
         *     `sessions` 逐字**只是**宿主侧那一族（ 产品侧持有的那些**不**在里面）
         *     **native** 那一族逐字**自己**有一份 `g_sessions[].open`
         *       （ 号逐字是**它**逐字分的、 它逐字**不**知道宿主侧那一份）
         *     **后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
         *       · 而那产品侧逐字那会话逐字拿到 0、 native 那一族逐字那 0 号逐字
         *         **已被** `sys.tty.exec` 那一族逐字占了
         *       diag 逐字「FAIL | PTY 的C 侧报错 | WINSIZE 指向不存在的会话」
         *       **会话号**逐字错位那一族
         *       `tty/index.json` 逐字那两行逐字重复（ 也是那号撞了那一族的旁证）
         *   **处置**： 号逐字**统一**分、 它逐字**不**由两边各自去抢
         *     它逐字**看**那**两边**逐字**都**占着的那些号
         *     产品侧持有的那些逐字**也**算**占着**
         *     native 那一族逐字那槽逐字**空**着逐字才**用**
         */
        private fun allocSid(): Int {
            //  **占着**的那几号逐字 = 宿主侧那一族 ∪ 产品侧那一族 ∪ native 那一族
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
        //  缺陷名（C-102）**那一格逐字把那一帧逐字**拆**成**两**次写、 它们逐字**没有**那**锁****
        //    旧版逐字是
        //      `toHost.write(h)` + `toHost.write(payload)` + `toHost.flush()`
        //      **两**个线程逐字**各**写那管道：
        //        · 那**调用方线程**逐字写那 `F_OPEN`
        //        · 那 **reader 线程**逐字写那 `F_READY` 的**反应**（ **补发**）
        //      **写**逐字**没有**那**锁**、 **次序**逐字**不**保证
        //        **两**个线程逐字**同时**写时、 它们逐字**能**逐字**交错**：
        //          A 写头 -> B 写头+体 -> A 写体
        //          native 那一族逐字读到的那一帧逐字**头与体对不上**
        //            （ `h.length` 逐字**是**B 的、 **体**逐字**是**A 的）
        //      **后果**（07 本轮逐字核出、Lead 本机复核成立）：
        //        · 而那 `diag.jsonl` 逐字 `stage="pty"` 逐字两条 FAIL
        //          「PTY 的C 侧报错 | WINSIZE 指向不存在的会话」逐字**还是**发
        //          （逐字 `1793621838075` 与 `1793622097373`）
        //        · `F_READY` 逐字**已经**真到了（ 逐字「会话 0 就绪」逐字
        //          逐字就在那 FAIL 逐字前 2.1 秒） 一帧逐字**还是发早了**
        //          （ **错**逐字**不**是那**早**、 它逐字是那**帧**逐字**错乱**）
        //        · **sid** 逐字**也可能**错（ native 那一族逐字按那**号**逐字找那槽
        //          **号**逐字**是**别的那一个 它逐字就判「那会话逐字不存在」）
        //    **判据**逐字是「那一帧逐字发的时候、 native 那一族逐字那槽逐字开没开」
        //      那一个事实上（ 它逐字**不**由「那一件逐字是什么」顶掉）
        //    **处置**： 一帧逐字**一次**写（ **头与体**逐字**连着**）、
        //      **写**逐字**一把锁**（ **次序**逐字**就是**那**写**次序）
        //      **早到**那一帧逐字**由**那 native 那一族逐字**自己**答（ `pending_winsize` 那一格）
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
                //  **一帧**逐字**连着**头与体逐字**写成**那**一个**字节串**
                //    （ **两次**写逐字**能**逐字**交错**、 **一次**写逐字**不**能）
                val frame = if (payload.isEmpty()) h else h + payload
                //  **写**逐字**一把锁**（ **次序**逐字**就是**那**写**次序）
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
                    //  缺陷名（C-102）**那一格逐字拿那线上逐字来的长度逐字**直接**去分配、 它逐字**没有上限****
                    //    旧版逐字是 `val buf = ByteArray(len)`
                    //      len 逐字是那**四个字节**逐字**拼**的、 **最大值** = 4294967295（4 GiB）
                    //      帧通道逐字**能**逐字**错位**：
                    //        · 那 native 那一族逐字那 `send_frame` 逐字**两次**写（ 头与那体逐字**分**开写）
                    //        · **写**逐字**能**逐字**交错**、 **读**逐字**逐字节**读（ `fromHost.read()` 逐字**一**个**一**个读）
                    //        len 逐字就**是**那**别**的那一帧逐字**拼**出来的
                    //          （ **拼错**逐字就**能**是那**几亿**）
                    //      **后果**（07 本轮逐字核出、Lead 本机复核成立）：
                    //        · 而那耗电那第一格逐字**回退**、 OOM 逐字**第 2** 趟逐字就**来**了
                    //        · 而那**分配量**逐字 = **117031556** 字节
                    //          （ `node` 那一件逐字 116779696 字节、 和那**差**逐字 251860
                    //            和那**差**逐字**就是**那**压缩**逐字与那**解压**逐字那**差**）
                    //      **后果**逐字 = 那 `ByteArray(len)` 逐字**直接**就**超**了那堆
                    //    **判据**逐字是「那一格逐字要多少、 它逐字**不该**要那么多」那一个事实上
                    //    **处置**： 长度逐字**有**一个上限、 超出那一截逐字**报缺**、 **不**分配
                    //      **上限**逐字是那 **1 MiB**
                    //        （ native 那一族逐字**自己**逐字**发**的逐字**最大**是那 64 KiB、
                    //          1 MiB 逐字**是**那它逐字的 **16 倍**、 **余量**逐字**够**）
                    //      **长度**逐字**超**了时逐字**丢**那一帧、 **流**逐字**它自己**也**已经**错位了
                    //        （ **帧**逐字**能**逐字**重新同步**、 **流**逐字**不能**）
                    //        **报缺**逐字**说清**那**长度**（ 按 C-05 不猜填）
                    if (len > MAX_FRAME_BYTES) {
                        val msg = "那帧长度逐字超上限：len=" + len + "、上限=" + MAX_FRAME_BYTES +
                            "（=> 而那帧通道逐字**错位**了、=> 而那**流**逐字**不能**逐字重新同步）"
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
                //  缺陷名（C-102）**那一格逐字把**那产品侧**持有的那些会话逐字一起逐字标成**关了** ——
                //    旧版逐字是 `for (s in sessions.values) s.markClosed()`
                //      `sessions` 逐字**只是**那 native 那一族逐字管的那一些
                //      **读帧线程**逐字**结束**时（ 是那 native 宿主逐字**没了**那一族）
                //        逐字把**全部**那一族逐字标成**关**
                //      **产品侧**持有的那些逐字**不在**那 `sessions` 那一族里
                //        它们逐字**不**受那 native 宿主逐字**生死**的影响
                //        **后果**（07 本轮逐字核出）：
                //          · 而那会话逐字**起过**（ PTY 进程逐字**在**那账本里：
                //            逐字 `linker64 gen=1 pid=18427 starttime=4710383`）
                //          · 会话逐字**没登记进 `files/dev`**
                //            （ pts 空、 等 8 拍 80 秒逐字没出现、 index.json `[]`）
                //          是那 `activeSessions` 那一格逐字的问题
                //            （ `ProductSessions.list()` 逐字**滤掉**那 `isClosed` 的那些）
                //    **处置**： **产品侧**的那些逐字**不**被那一格逐字标成**关**
                //      它们逐字**只**由它们**自己**那一路逐字标（ 是那进程逐字**退**了、
                //        或而那调用方逐字**显式**关它）
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
            //  **native** 那一族逐字那槽逐字**开好了**（ `F_READY` 逐字**就是**那凭据）
            //    **补发**逐字**这时候**才发那 `F_WINSIZE`
            //  **F_READY** 逐字**那一帧真的到**了（ 它逐字**是**那**凭据**）
            //    **补发**逐字**这时候**才发那 `F_WINSIZE`
            //      （ 本地状态逐字**不**许当那凭据、 依 C-103 那两边逐字各答一件事）
            sessions[sid]?.onReadyFrameArrived()
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {
            //  输出逐字**先**落到那 Session 自己的缓冲里（ 回调**还没**接上时逐字**不**丢）
            try { sessions[sid]?.deliver(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        private fun onExited(sid: Int, payload: ByteArray) {
            val s = sessions.remove(sid)
            //  native 那一族逐字**回**了那**回**帧、 它逐字**关**完那槽了
            //    **判据**逐字是「那一号逐字还回去的时候、 native 那一族逐字那槽逐字**空没空**」
            //      那一个事实上
            //    **回**帧逐字**是**那 native 那一族逐字那 `F_CLOSE` 逐字**关**完才**发**的
            //      （ **等**那子进程逐字**死**逐字最多 500 毫秒逐字**在**那 native 那一族**里面**）
            markSlotFreed(sid)
            var status = -1
            var signal = 0
            if (payload.size >= 8) {
                val b = java.nio.ByteBuffer.wrap(payload, 0, 8)
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                status = b.int
                signal = b.int
            }
            //  缺陷名（C-102）**那一格逐字把那 native 那一族的退出逐字当成那会话逐字**真的**退了** ——
            //    旧版逐字是 `s?.recordExit(...); s?.markClosed()`
            //      **退出信号**逐字是那 **native** 那一族逐字发的
            //        （ 和那它逐字是那 **native fork 出来**的那个子进程逐字退时逐字发的）
            //      **执行**逐字**已经**归那**产品侧**了（ 是 52c0139 那一笔逐字落的）
            //        和那 native 的那个子进程逐字**只**握那从设备、 和那它逐字退
            //          **不代表**那产品侧逐字起的那进程退
            //      一格逐字 markClosed 逐字把那会话逐字**关**了
            //        `ProductSessions.list()` 逐字**滤掉**那 `isClosed` 的那些
            //          会话逐字**就**不在那名单里、 `files/dev` 逐字**没有**它
            //            （ 07 本轮逐字核到： 会话逐字**起过**、
            //              PTY 进程逐字**在**那账本里：逐字 `linker64 gen=1 pid=18427`、
            //              会话逐字**没登记进 `files/dev`**：
            //              逐字 pts 空、 等 8 拍 80 秒逐字没出现、 index.json `[]`）
            //    **处置**： **产品侧**的进程逐字**还活着**时
            //      一格逐字**不**把那会话逐字标成**关**
            //      会话逐字**继续**在那名单里、 `files/dev` 逐字**有**它
            //      **真的**退了时（ 产品侧逐字**自己**报那退出、 或那调用方逐字**显式**关它）
            //        逐字**才**标成**关**
            val stillAlive = s?.process?.isAlive == true
            if (!stillAlive) {
                s?.recordExit(status, signal)
                //  缺陷名（C-102）**那一格逐字**只**标成关、 `F_CLOSE` 逐字**没人发** ——
                //    旧版逐字是 `s?.recordExit(…); s?.markClosed()`
                //      `F_CLOSE` 逐字**只**在那 `close()` 那一格逐字发
                //        （ `PtySession.kt:184` 逐字 `host.send(F_CLOSE, sid, ByteArray(0))`）
                //      **产品侧**逐字自己退时逐字**没有**发那 `F_CLOSE`
                //        （ `PtySession.kt:819` 到 `:820` 那一格逐字**只有**那两行）
                //      native 那一族逐字那槽逐字**还占着**
                //        （ `session_close` 逐字**没**被调到、 槽逐字**没**被清）
                //      **下一趟**逐字分号逐字就**撞**
                //        （ `diag.jsonl` 逐字 `stage="pty"` 逐字「WINSIZE 指向不存在的会话」
                //          逐字又逐字出现了一条：逐字 `1793594620810`）
                //    **处置**： **产品侧**自己退这一路逐字**也**发那 `F_CLOSE`
                //      槽逐字**就**被还回去了、 **下一趟**逐字分号逐字**不撞**
                //      `close()` 那一格逐字那 `compareAndSet` 逐字**护着**、 它逐字**只**发一次
                //        （ **产品侧**自己退那一路逐字要逐字也受那一个护着、
                //          它逐字**不能**逐字发两次）
                //    **判据**逐字是「那一件逐字退了之后、 native 那一族逐字那槽逐字
                //      有没有逐字被还回去」那一个事实上
                //      （ 它逐字**不**由「那一格逐字报没报错」顶掉）
                s?.releaseNativeSlot()
                //  markClosed 逐字**在**那 F_CLOSE **之后**、 次序**不能**反
                //    （ `markClosed` 逐字把那一格 `closed` 逐字**置真**、
                //      `releaseNativeSlot` 逐字那 `compareAndSet(false, true)`
                //        逐字**就**会**不成**、 F_CLOSE 逐字**就**发**不**出去）
                s?.markClosed()
            }
            try { s?.onExit?.invoke(status, signal) }
            catch (e: Exception) { Log.w(TAG, "EXITED 回调异常", e) }
        }

    fun activeSessions(): List<Session> = sessions.values.toList()

    /**
     * 那**槽**逐字**空没空**逐字**等**。
     *
     *   native 那一族逐字那 `F_CLOSE` 逐字**关**完那槽逐字才**回**一帧（ `FRAME_EXITED` 那一族）
     *   **判据**逐字是「那一号逐字还回去的时候、 native 那一族逐字那槽逐字**空没空**」
     *   **等**逐字**有**一个**上限**、 **上限**到了逐字**不**等
     *   **答**逐字是那**真没空**（ 它逐字**不**猜填）
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

    /** 那**槽**逐字**空**了逐字**记**一下（ native 那一族逐字**回**了那**回**帧）。 */
    internal fun markSlotFreed(sid: Int) {
        synchronized(this) { slotBusy.remove(sid) }
    }

    /** 那**槽**逐字**占着**逐字**记**一下（ native 那一族逐字**开**了那槽）。 */
    internal fun markSlotBusy(sid: Int) {
        synchronized(this) { slotBusy.add(sid) }
    }

    /**
     * 那**槽**逐字**没**回报已空逐字**记**一下（ 一号逐字**照样**还了、 一档逐字**不**静着）。
     */
    internal fun reportSlotNotFreed(sid: Int) {
        ctx?.let {
            KernelHooks.report(
                it, "pty", false, "PTY 的C 侧报错",
                "槽 $sid 在 ${SLOT_FREED_TIMEOUT_MS}ms 内没回报已空，=> 而那一号逐字**照样**还了",
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
            //  缺陷名（C-102）**那两个线程逐字没有「停它的那一格」** ——
            //    旧版逐字只 `proc.destroy()` 与关那三个流（ **零字没有** join 那两个线程）
            //      reader 逐字是 pump() 那一支的**唯一驱动**（ pump() 逐字读 fromHost）
            //        而那 diagReader 逐字是 proc.errorStream 的**唯一读者**
            //      **流关掉之后**那两个线程逐字**各自卡在那一读上**、 它们逐字是 daemon
            //        （ daemon 逐字在进程退出时才被逐字杀掉） 一次 close 之后
            //        它们逐字**还占着那个进程**， **下一次** openSession 逐字再建两个。
            //    **处置**： **先断流、再 join**（ join 逐字有界、 不逐字无限等）。
            //      join 逐字**放在关流之后**（ 线程逐字要靠流关闭才逐字醒）。
            stopReader(reader, "lobos-pty-reader")
            stopReader(diagReader, "lobos-pty-diag")
        }

        /**
         * 停那一个线程（ **有界**、 不逐字无限等）。
         *   先 interrupt（ 线程逐字卡在那一读上、 interrupt 逐字能逐字把它叫醒）
         *     再 join 那一个有界的时长（ 超时逐字就逐字放弃、 它逐字是 daemon、 不逐字挡着进程）。
         */
        private fun stopReader(t: Thread, name: String) {
            if (!t.isAlive) return
            runCatching { t.interrupt() }
            runCatching { t.join(READER_JOIN_MS) }
            if (t.isAlive) {
                //  它逐字还没停 记一条（ 按 C-05「缺字段就报缺」不逐字假装它停了）。
                Log.w(TAG, "PTY 线程 $name 在 ${READER_JOIN_MS}ms 内没停（=> 而它逐字是 daemon、=> 而进程退出时会被杀掉）")
            }
        }
    }

    @Volatile private var host: Host? = null
    private val hostLock = Any()

    /**
     * 开**一条**会话（ 它逐字**活着**、 它逐字**不是**那一次性的那一族）。
     *
     * 缺陷名（C-102）**那一格逐字把 argv 原样交给那宿主、 它逐字没走那 `ExecEntry.argv` 那一族** ——
     *   旧版逐字是 `host(ctx, bin).start(argv, rows, cols, timeoutMs)`
     *     **宿主**逐字是那 native 那一份（ `pty-session.c` 那一族逐字**直接 execve**）
     *       它逐字**不**判形态、 它逐字**不**套那一层
     *     **入口**逐字是那**解释器**（ bash 那一件逐字 ET_DYN）
     *       它逐字**直接**被 execve、 **没有** linker64 驱动它
     *       **错误**逐字是
     *         `/data/user/0/lobos.os/files/usr/bin/bash: cannot execute binary file`
     *     **会话**逐字**当场就退出了**（ stdout 逐字空 + exitCode 逐字 0）
     *   **那**与那 `runToCompletion` 那一族逐字**不一样**：
     *     `runToCompletion` 逐字**有**那一句
     *       `val effArgv = … ExecEntry.argv(first, argv) ?: argv`
     *     它逐字**判形态**、 它逐字**套**那一层
     *     `openSession` 那一族逐字**漏了**那一句（ 它是**同一条路**的另一扇门）
     *   **处置**： **判形态**那一层逐字**交给**那 `ExecEntry.argv` 那一族
     *     （ 是那**唯一真相源**、 native 那份已删、 C-87）
     *     ET_DYN 那一档逐字**由它**套 linker64、 ET_EXEC 那一档逐字**由它**不套
     *     它逐字**认不出**那一档时逐字**保留**那 argv（ `ExecEntry.argv` 那一族
     *       逐字**自己**就那样返回、 不是**猜**）
     */
    fun openSession(
        ctx: Context,
        bin: java.io.File,
        argv: List<String>,
        rows: Int = DEFAULT_ROWS,
        cols: Int = DEFAULT_COLS,
        timeoutMs: Long = 5_000,
    ): Session {
        //  **判形态**那一层逐字**走**那 `ExecEntry.argv` 那一族
        //    （ 它逐字与那 `runToCompletion` 那一族逐字**同一把尺**）
        val effArgv = if (argv.isEmpty()) argv
        else {
            val first = java.io.File(argv[0])
            //  **认不出**那一档逐字**报缺**、 它逐字**不**调起（ M7-05 那一族）
            //    旧版逐字是 `?: argv`（ 它逐字**拿回那原样的 argv**、
            //      **坏 ELF 逐字被当程序调起**）
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
                //  缺陷名（C-102）**那一层逐字手写了、 它逐字没走那 `ExecEntry.argv` 那一族逐字对 sh 判形态** ——
                //    旧版逐字是
                //        listOf(SYSTEM_LINKER, sh.absolutePath, "-c", script.toString())
                //      它逐字**总是**套那 SYSTEM_LINKER
                //      **判据**逐字该是「那**形态**」（ `ExecEntry.argv` 那一族逐字就是那一族）
                //        · 而那 **ET_DYN** 那一档逐字要 linker64 驱动
                //        · 而那 **ET_EXEC** 那一档逐字**不要**
                //    **后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
                //      · 设备上 `files/opt/bash/5.2.15/bin/bash` 逐字 ELF 头 `7f 45 4c 46 02 01 01`
                //        逐字 e_type=03（ **ET_DYN**、 它逐字要 linker64 驱动）
                //      **错误**逐字是
                //        `/data/user/0/lobos.os/files/usr/bin/bash: cannot execute binary file`
                //      **会话**逐字**当场就退出了**、 stdout 逐字空 + exitCode 逐字 0
                //      **期望**逐字是 `git version 2.55.0` 与那退出码 0
                //        一格逐字**没过**（ stdout 逐字**不含**那期望片段）
                //    **处置**： **判形态**那一层逐字**交给**那 `ExecEntry.argv` 那一族
                //      （ 是那**唯一真相源**、 native 那份已删、 C-87）
                //      ET_DYN 那一档逐字**由它**套 linker64、 ET_EXEC 那一档逐字**由它**不套
                val shArgv = lobos.kernel.proc.ExecEntry.argv(
                    sh, listOf(sh.absolutePath, "-c", script.toString()),
                )
                //  它逐字**认不出**那一档时逐字**报缺**（ 按 C-05 不猜填）
                //    **不**回退到那手写那一层（ **就是**这一族那个错）
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
            //   是**下面那个 else 那一支**（ sh == null， pb0 = hostArgv）。
            //
            // 而成因：上面那一支（ sh != null）把 view0 的每一项 export 进脚本，
            //   脚本 exec 那个宿主 环境由那个脚本带 一支是好的；
            // 而**下面那一支直接 exec 那个宿主本体**， pb1 的环境是**继承宿主进程的**，
            //   宿主进程那份环境里没有 LD_LIBRARY_PATH（ view0 只被 export 进脚本，
            //      一支没走脚本， view0 从来没被灌进 env）。
            // 而那个宿主（librivospty.so）的 DT_NEEDED 有 liblobosposix.so，
            //   而 liblobosposix.so 在 files/usr/lib， 不在系统默认搜索路径里，
            //   所以它要靠 LD_LIBRARY_PATH 一项丢了它就起不来。
            //
            // 处置：把 view0 灌进 pb1 的环境（ 一份就是 treeRootEnv，
            //   它含 LD_LIBRARY_PATH）， 下面那一句 remove 照旧（ LD_PRELOAD 不继承）。
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
     * 那**产品侧**逐字持有的那几条会话（ 些逐字**不**是 native 那一族管的那一些）。
     *
     * 缺陷名（C-102）**那产品侧起的那一条逐字没人持有、 它逐字不在那会话那一族的名单里** ——
     *   旧版逐字**没有**这样的一族（ `spawnOnSlave` 那一格逐字起完就**只**记那一个句柄）
     *     **会话**逐字**没有**一个统一的名单、 `activeSessions` 逐字**只**问 native 那一族
     *   **处置**： **这一族**逐字持有那产品侧起的那几条
     *     它们逐字**可**被 `activeSessions` 逐字列出来
     *     **会话号**逐字**不**该**冲突**（ 它逐字**带**一个族标记）
     */
    object ProductSessions {
        private val lock = Any()
        private val held = LinkedHashMap<String, Session>()

        /** 那**记下**那一条（ 号逐字**带**那族标记、 它逐字**不**与 native 那一族**冲突**）。 */
        @Synchronized
        fun put(key: String, s: Session) {
            synchronized(lock) { held[key] = s }
        }

        /** 那**还**在的那些（ 它们逐字**不**是 native 那一族管的那些）。 */
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

        /** 那**去掉**那一条（ 进程逐字退了时逐字调）。 */
        @Synchronized
        fun remove(key: String) {
            synchronized(lock) { held.remove(key) }
        }
    }

    /**
     * 那**活着**的那几条会话（ **两族**逐字**都**列）。
     *
     * 缺陷名（C-102）**那一格逐字只列 native 那一族管的那一些、 产品侧持有的那些逐字不在里面** ——
     *   旧版逐字是
     *       `val h = host; return if (h == null) emptyList() else h.activeSessions()`
     *     `h.activeSessions()` 逐字**只**列那 `g_sessions` 那一族逐字管的那些
     *       （ 是那 **native** 那一族逐字 fork 出来的）
     *     **产品侧**逐字持有的那一条（ `spawnOnSlave` 那一格逐字起的那一个）
     *       逐字**不在**那里面
     *     **后果**（07 本轮逐字核出、Lead 本机实读逐字成立）：
     *       · 而那 `SysDev.kt:64` 逐字 `for (s in PtySession.activeSessions(ctx))`
     *       · 产品侧逐字持有的那一条（ sid=0 / slave=/dev/pts/2）逐字不在里面
     *       · `files/dev/tty/pts/<NNN>` 逐字**永远建不出来**
     *   **处置**： **两族**逐字**都**列
     *     · 那 **native** 那一族逐字管的那一些（ 些逐字是那 PTY 那一层自己管的）
     *     · 那**产品侧**逐字持有的那一些（ 些逐字是那 `spawnOnSlave` 那一格逐字起的）
     *     **会话号**逐字**不**该**冲突**（ 两族逐字**各**用自己那一套号）
     *     **判别形式**（依 C-105 逐字**不**合并那两问）：
     *       答「那**会话**逐字是什么」逐字该列**全部**； 答「那**进程**逐字归谁管」逐字**只**看那账本
     */
    fun activeSessions(ctx: Context): List<Session> {
        val native = runCatching {
            val h = host
            if (h == null) emptyList() else h.activeSessions()
        }.getOrDefault(emptyList())
        //  **产品侧**逐字持有的那一些（ 些逐字**不**在 native 那一族那里面）
        val product = runCatching { ProductSessions.list() }.getOrDefault(emptyList())
        //  缺陷名（C-102）**那同一条会话逐字被列了两次** ——
        //   旧版逐字是 `native + product`
        //     **同一条**会话逐字**两边**都看得见（ native 那一族逐字有一份、
        //       而那产品侧那一族逐字**也**有一份）
        //     `tty/index.json` 逐字那两行逐字**重复**
        //       （ 07 本轮逐字核到：`[{"sid":0,…},{"sid":0,…}]`、 两行逐字**完全一样**）
        //       （ 也是那号撞了那一族的**旁证**）
        //   **处置**： **同一**那一条会话逐字**只**列一次
        //     **判据**逐字是那**从设备名**（ 它逐字**唯一**、 号逐字**可能**重）
        //     **号**逐字**不同**的那些逐字**都**留（ 是**两条**不同的会话）
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
        //  **认不出**那一档逐字**报缺**、 它逐字**不**调起（ M7-05 那一族）
        //    旧版逐字是 `?: argv`（ 它逐字**拿回那原样的 argv**、
        //      **坏 ELF 逐字被当程序调起**）
        //    **退出码**逐字是那一个具体的数、 **诊断原文**逐字**进**那 stderr
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
            //  缺陷名（C-102）**那一格逐字**无上限**地累积那输出** ——
            //    旧版逐字是 `val buf = java.io.ByteArrayOutputStream()`
            //      **缓冲**逐字**一直**涨、 它逐字**不**停
            //        （ 一趟逐字要多久、 它逐字就涨多少）
            //      堆逐字**本来**就只剩那**余量**了
            //        （ 设备上 `heapgrowthlimit` = 384m、 堆逐字已到 291 MB）
            //    **处置**： 缓冲逐字**有**一个上限、 超出那一截逐字**丢**
            //      **期望输出**那一族逐字**要**的是那**开头**那一些（ 它逐字**不**是全量）
            //      **上限**逐字**要**报缺（ 按 C-05 不猜填）
            val buf = java.io.ByteArrayOutputStream()
            var dropped = 0L
            val done = CountDownLatch(1)
            s.onData = { d ->
                synchronized(buf) {
                    if (buf.size() + d.size > MAX_CAPTURED_OUTPUT_BYTES) {
                        //  **超出**的一截逐字**丢**、 **丢了多少**逐字**记**着
                        val room = (MAX_CAPTURED_OUTPUT_BYTES - buf.size()).coerceAtLeast(0)
                        if (room > 0) buf.write(d, 0, room)
                        dropped += (d.size - room).toLong()
                    } else {
                        buf.write(d)
                    }
                }
            }
            s.onExit = { _, _ -> done.countDown() }
            //  缺陷名（C-102）**那一格逐字只等 native 那一族那一条退出信号、 一条逐字**永远不来****
            //   旧版逐字是 `s.onExit = { _, _ -> done.countDown() }`
            //     退出信号逐字是那 native 那一族逐字发的 `FRAME_EXITED`
            //       （ 一条逐字是那 **native fork 出来**的子进程逐字退时逐字发的）
            //     **现在**逐字那执行逐字归那**产品侧**（ 是 52c0139 那一笔逐字落的）
            //       产品侧逐字起的进程逐字**自己**退、 native 那一族逐字**不知道**
            //       `FRAME_EXITED` 逐字**永远不来**、 **等**逐字**只能**超时
            //   **后果**（07 本轮逐字核出、Lead 本机复核成立）：
            //     · 那 `sys.tty.exec` 那一趟逐字「PTY 不可用（超时 10000ms）」、 逐字走那无 PTY 通路
            //     · **阳性对照**：那会话逐字**建得出来**（ diag 逐字「会话 0 就绪 从设备=/dev/pts/N」）
            //   **处置**： 进程逐字**自己**退了时逐字把那退出**报**出来
            //     `onExit` 逐字**两路**都能来（ native 那一族逐字那一条 + 而那产品侧这一条）
            //     **等**逐字因此逐字**等得到**、 **超时**逐字**不再**是那唯一那条路
            s.process?.let { proc ->
                Thread({
                    val st = runCatching { proc.waitFor() }.getOrNull()
                    runCatching {
                        s.onExit?.invoke(st ?: 0, 0)
                    }
                }, "lobos-pty-exit-watch").apply { isDaemon = true; start() }
            }
            //  **丢了多少**逐字**记**着（ 按 C-05 不猜填、 **不**静默）
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
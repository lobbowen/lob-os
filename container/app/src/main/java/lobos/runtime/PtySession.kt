package lobos.runtime

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import lobos.RuntimeDiagnostics
import lobos.os.RuntimeEnvironment
import lobos.os.SystemDirs

/**
 * PTY 会话 —— 底座由 ptysession 那件提供（librivospty.so，纯 C 静态可执行件）。
 *
 * 协议以 container/native/d3/pty-session.c 为准，不是我们自拟的：
 *   帧头 8 字节：kind(1) sid(1) flags(2) length(4)，长度是无符号大端
 *   帧类型 OPEN=1 READY=2 DATA=3 INPUT=4 WINSIZE=5 CLOSE=6 EXITED=7 EXIT=8 ERROR=9
 *   OPEN 的 payload 是 argv，NUL 分隔、以 NUL 结尾（parse_argv 靠数 \0 得 argc）
 *   READY 回 slave 路径（NUL 结尾）+ winsize；DATA 是从 master 读到的字节
 *   EXITED 回 status(4) + sig(4)
 *
 * 宿主走 stdin/stdout 两条管道（不是 UDS）—— 它的 main 就是 in_fd=0 out_fd=1。
 * 一个宿主进程管多个 sid（上限 8，超了宿主自己回 FRAME_ERROR）；
 * sid 由宿主分配，不是我们指定。
 */
object PtySession {

    private const val TAG = "PtySession"

    /** 宿主文件名 —— 带 .so 后缀但它是静态可执行件，不是共享库 */
    private const val HOST_NAME = "librivospty.so"

    private const val FRAME_OPEN = 1
    private const val FRAME_READY = 2
    private const val FRAME_DATA = 3
    private const val FRAME_INPUT = 4
    private const val FRAME_WINSIZE = 5
    private const val FRAME_CLOSE = 6
    private const val FRAME_EXITED = 7
    private const val FRAME_EXIT = 8
    private const val FRAME_ERROR = 9

    private const val FRAME_MAX = 64 * 1024
    /**
     * argv 的分隔符。源码里不能有裸 NUL 字节，用转义写死这一个。
     * 不是 const —— const 只接受基本类型字面量，\u0000 不在其列。
     */
    private val NUL = "\u0000"

    /** 宿主在位吗 —— 落在 $PREFIX/bin 下 */
    fun available(ctx: Context): Boolean = hostBin(ctx) != null

    private fun hostBin(ctx: Context): File? = runCatching {
        File(SystemDirs.bin(ctx), HOST_NAME).takeIf { it.isFile && it.canExecute() }
    }.getOrNull()

    /**
     * 一个 PTY 会话。
     *
     * onData / onExit 由 UI 侧挂：帧在读线程上收到就回调，调用方自己切主线程。
     */
    class Session internal constructor(
        val sid: Int,
        val slave: String,
        val rows: Int,
        val cols: Int,
        private val host: Host,
    ) : AutoCloseable {

        @Volatile var onData: ((ByteArray) -> Unit)? = null

        @Volatile var onExit: ((Int) -> Unit)? = null

        private val open = AtomicBoolean(true)

        val isOpen: Boolean get() = open.get()

        /** 往 PTY 写字节（等价于往终端敲键盘） */
        fun write(text: String) {
            if (open.get()) host.send(FRAME_INPUT, sid, text.toByteArray(Charsets.UTF_8))
        }

        /** 窗口大小变了 —— 跟内核 tty 一样，改了要立刻报给会话 */
        fun resize(newRows: Int, newCols: Int) {
            if (!open.get()) return
            val ws = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
            ws.putShort(newRows.toShort())
            ws.putShort(newCols.toShort())
            host.send(FRAME_WINSIZE, sid, ws.array())
        }

        override fun close() {
            if (open.compareAndSet(true, false)) {
                runCatching { host.send(FRAME_CLOSE, sid, ByteArray(0)) }
                host.retire(sid)
            }
        }
    }

    /** runToCompletion 的结果 —— completed=false 表示没跑完，调用方该改走别的通路 */
    data class RunResult(
        val completed: Boolean,
        val ok: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String?,
    )

    /** 一次 OPEN 的等待位 —— sid/slave 由宿主在 READY 帧里回填 */
    private class OpenWait {
        val done = CountDownLatch(1)

        @Volatile
        var sid: Int = -1

        @Volatile
        var slave: String = ""
    }

    /**
     * 宿主进程 —— 所有会话共用一个，读帧在一个后台线程上做。
     *
     * 写要串行：两线程同时写一帧字节会交错，所以 send 上加锁。
     */
    private class Host(private val proc: Process) {
        private val inStream = DataOutputStream(proc.getOutputStream())
        private val out = DataInputStream(proc.getInputStream())
        private val writeLock = Any()
        private val sessions = ConcurrentHashMap<Int, Session>()

        /** 正在等 READY 的那次 OPEN */
        val opening = AtomicReference<OpenWait?>(null)
        private val started = AtomicBoolean(true)

        @Volatile
        private var lastError: String? = null

        val error: String? get() = lastError

        fun send(kind: Int, sid: Int, payload: ByteArray) {
            if (!started.get()) return
            synchronized(writeLock) {
                runCatching {
                    inStream.writeByte(kind)
                    inStream.writeByte(sid)
                    inStream.writeShort(0)          // flags
                    inStream.writeInt(payload.size)
                    inStream.write(payload)
                    inStream.flush()
                }.onFailure {
                    started.set(false)
                    lastError = "写宿主失败：" + (it.message ?: it.javaClass.simpleName)
                }
            }
        }

        fun register(s: Session) {
            sessions[s.sid] = s
        }

        fun retire(sid: Int) {
            sessions.remove(sid)
        }

        /** 读循环 —— 读到宿主退出或 FRAME_EXIT 为止 */
        fun pump() {
            val hdr = ByteArray(8)
            try {
                while (started.get()) {
                    out.readFully(hdr)
                    val kind = hdr[0].toInt() and 0xff
                    val sid = hdr[1].toInt() and 0xff
                    val len = be32(hdr, 4)
                    if (len < 0 || len > FRAME_MAX) {
                        lastError = "帧长非法：$len"
                        break
                    }
                    val payload = ByteArray(len)
                    if (len > 0) out.readFully(payload)
                    handle(kind, sid, payload)
                    if (kind == FRAME_EXIT) break
                }
            } catch (_: EOFException) {
                // 宿主自己退了 —— 那是它的结束方式，不是错误
            } catch (e: Throwable) {
                lastError = "读宿主失败：" + (e.message ?: e.javaClass.simpleName)
                Log.w(TAG, "PTY 宿主读帧异常", e)
            } finally {
                started.set(false)
                for (s in sessions.values) s.open.set(false)
                sessions.clear()
            }
        }

        private fun be32(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xff) shl 24) or
                ((b[off + 1].toInt() and 0xff) shl 16) or
                ((b[off + 2].toInt() and 0xff) shl 8) or
                (b[off + 3].toInt() and 0xff)

        private fun handle(kind: Int, sid: Int, payload: ByteArray) {
            when (kind) {
                FRAME_READY -> {
                    // payload = slave 路径（以 NUL 结尾）+ winsize；
                    // 这个 sid 是宿主分配的槽位号 —— 这才是真 sid
                    val z = payload.indexOf(0)
                    val slave = if (z >= 0) String(payload, 0, z, Charsets.UTF_8) else ""
                    opening.getAndSet(null)?.let {
                        it.sid = sid
                        it.slave = slave
                        it.done.countDown()
                    }
                    Log.i(TAG, "PTY 就绪 sid=$sid slave=$slave")
                }

                FRAME_DATA -> sessions[sid]?.onData?.invoke(payload)

                FRAME_EXITED -> {
                    val status = if (payload.size >= 4) be32(payload, 0) else 0
                    val s = sessions.remove(sid)
                    s?.open?.set(false)
                    s?.onExit?.invoke(status)
                }

                FRAME_ERROR -> {
                    lastError = String(payload, Charsets.UTF_8)
                    // 会话没开成，但等的那个人得被放出来，不然他一直等到超时
                    opening.getAndSet(null)?.done?.countDown()
                }

                FRAME_EXIT -> Unit // 循环尾部统一收
            }
        }
    }

    /** 宿主进程按 filesDir 缓存 —— 上限 8 个会话，重开不如复用 */
    private val hosts = ConcurrentHashMap<String, Host>()

    private fun hostFor(ctx: Context): Host {
        val key = ctx.filesDir.absolutePath
        return hosts[key] ?: synchronized(this) {
            hosts[key] ?: startHost(ctx).also { hosts[key] = it }
        }
    }

    private fun startHost(ctx: Context): Host {
        val bin = hostBin(ctx)
            ?: throw IllegalStateException("PTY 会话宿主不在位（$HOST_NAME 未铺到 $PREFIX/bin）")
        val pb = ProcessBuilder(bin.absolutePath)
        // 宿主要能起 sh 和我们自己的件，给它树根环境
        pb.environment().putAll(
            RuntimeEnvironment.treeRootEnv(ctx, RuntimeEnvironment.treeRootFor(ctx), null)
        )
        val host = Host(pb.start())
        Thread({ host.pump() }, "pty-host-reader").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
        }.start()
        RuntimeDiagnostics.append(ctx, "pty", true, "PTY 会话宿主已起", bin.absolutePath)
        return host
    }

    /**
     * 开会话 —— argv[0] 是那个件里的可执行体，rows/cols 是窗口大小。
     *
     * sid **不是我们指定的**：alloc_slot() 只看 g_sessions[i].open 谁空着，
     * 槽位由宿主分配、再随 FRAME_READY 回给我们。所以 OPEN 的 sid 发 0，
     * 真 sid 从 READY 帧取 —— 自己先挑一个会有并发竞争，两个人同时开就撞。
     */
    fun openSession(ctx: Context, argv: List<String>, rows: Int, cols: Int): Session {
        if (argv.isEmpty()) throw IllegalArgumentException("argv 为空")
        if (!available(ctx)) throw IllegalStateException("PTY 会话宿主不在位")
        val host = hostFor(ctx)

        val waiter = OpenWait()
        host.opening.set(waiter)
        // argv 按 NUL 分隔，末尾也要一个 NUL ——
        // parse_argv 先数 \0 的个数得出 argc，再按 \0 切出每个参数，
        // 所以「n 个参数」要有 n 个 \0（最后一个正好是终止符）。
        val blob = argv.joinToString(NUL) + NUL
        host.send(FRAME_OPEN, 0, blob.toByteArray(Charsets.UTF_8))

        val got = waiter.done.await(5, TimeUnit.SECONDS)
        val err = host.error
        host.opening.compareAndSet(waiter, null)
        if (!got || err != null) {
            throw IllegalStateException("PTY 会话开不起来：" + (err ?: "宿主没应答"))
        }
        val s = Session(waiter.sid, waiter.slave, rows, cols, host)
        host.register(s)
        s.resize(rows, cols)   // 宿主 OPEN 时只报当前窗口大小，按我们给的补上
        return s
    }

    /**
     * 跑完就收 —— LocalExec 的首选通路。
     *
     * 超时不算 completed：调用方拿到 completed=false 就该改走无 PTY 通路，
     * 而不是把半截输出当结果。
     */
    fun runToCompletion(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String>,
        cwd: File?,
        timeoutMs: Long,
    ): RunResult {
        if (argv.isEmpty()) return RunResult(true, false, -1, "", "argv 为空")
        if (!available(ctx)) return RunResult(false, false, -1, "", "PTY 会话宿主不在位")
        return runCatching {
            val s = openSession(ctx, argv, 24, 80)
            val sb = StringBuilder()
            val done = CountDownLatch(1)
            var code = -1
            s.onData = { sb.append(String(it, Charsets.UTF_8)) }
            s.onExit = { code = it; done.countDown() }
            val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            s.close()
            if (!finished) {
                RunResult(false, false, -1, sb.toString(), "超时 ${timeoutMs}ms 未退出")
            } else {
                RunResult(true, code == 0, code, sb.toString(), null)
            }
        }.getOrElse {
            RunResult(false, false, -1, "", it.message ?: it.javaClass.simpleName)
        }
    }
}

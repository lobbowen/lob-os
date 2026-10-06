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

/**
 * PTY 会话 —— 程序要终端时的执行路径。
 *
 * ── 为什么不用 ProcessBuilder ──
 * ProcessBuilder 不分配 PTY，于是 isatty() 为假、程序不进交互模式、读不到窗口大小。
 * Android 的 ProcessBuilder 不暴露 setsid/TIOCSCTTY，无 JNI 做不到。
 * 所以底座有个原生件 librivospty 做这件事（见 container/native/d3/pty-session.c），
 * 它走「常驻可执行件 + 帧协议」而不是 JNI。
 *
 * ── 三条流 ──
 *   本件 stdin  → 控制帧（OPEN / INPUT / WINSIZE / CLOSE / EXIT）
 *   本件 stdout → 数据帧（READY / DATA / EXITED / ERROR）
 *   本件 stderr → 诊断（人类可读，不参与协议）
 *
 * ── 复用点 ──
 * [forCommand] 供 shell.exec 用（建一个、执行、读完就关）；
 * 第 9 阶段的内置终端窗口长期持有一个实例 —— 同一个类，不需要重写。
 */
object PtySession {

    private const val TAG = "PtySession"

    // 帧类型 —— 必须与 pty-session.c 的 enum 逐一对上
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

    /** 一条已建立的 PTY 会话。 */
    class Session internal constructor(
        private val host: Host,
        val sid: Int,
        val slavePath: String,
        val initialRows: Int,
        val initialCols: Int,
    ) {
        private val closed = AtomicBoolean(false)

        /**
         * 本会话的数据回调。
         *
         * 挂在 **sid** 上而不是 host 上 —— 挂 host 的话两个并发会话的输出会互相串
         * （A 命令的输出进 B 的缓冲区），那是最难查的一类 bug：单独测都对，
         * 并发就错。所以路由键是 sid，不是「当前谁在跑」。
         */
        @Volatile var onData: ((ByteArray) -> Unit)? = null

        /** 本会话的退出回调。 */
        @Volatile var onExit: (() -> Unit)? = null

        /** 写输入（用户敲的键、程序喂的数据）。 */
        fun write(data: ByteArray) {
            if (closed.get()) return
            host.send(F_INPUT, sid, data)
        }

        fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

        /** 改窗口大小 —— 改完 TIOCGWINSZ 才能读到新值，vi/less 会重排。 */
        fun resize(rows: Int, cols: Int) {
            if (closed.get()) return
            val b = java.nio.ByteBuffer.allocate(8)
            // 必须是 LITTLE_ENDIAN：原生侧是 memcpy(&ws, payload, sizeof(ws))，
            // 即主机序（aarch64 小端）。而 ByteBuffer 默认是**大端** ——
            // 用默认序写出去，24 会变成 0x1800（6144），窗口大小直接失效，
            // 而现象是「改了尺寸但程序不知道」，不报错。
            // 字段顺序 rows, cols, xpixel, ypixel 由 POSIX 的 struct winsize 定，
            // 两侧都按那个顺序，所以这里只需保证字节序一致。
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

    /** 常驻的 librivospty 进程 + 读帧线程。 */
    class Host internal constructor(pb: ProcessBuilder, private val ctx: Context? = null) : AutoCloseable {

        private val proc: Process = pb.start()
        private val toHost = DataOutputStream(proc.outputStream)
        private val fromHost = DataInputStream(proc.inputStream)
        private val diag = StringBuilder()

        /**
         * 原生侧的 stderr —— 它是排障时唯一的线索（起不来时 stdout/stderr 全空，
         * 只有这里写「librivospty ready」或具体 errno）。必须有人读，否则管道满了
         * 会把原生侧**卡死**在写 stderr 上，表现为「会话永远不 READY」。
         */
        private val diagReader = Thread({
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(diag) {
                        diag.append(line).append('\n')
                        if (diag.length > 8192) diag.setLength(0)   // 别让它涨成内存泄漏
                    }
                }
            } catch (_: Exception) {
                // 宿主退出时管道关闭，这里静默即可
            }
        }, "lobos-pty-diag").apply { isDaemon = true; start() }

        /** sid → 会话。读线程写入，读线程之外只读。 */
        private val sessions = ConcurrentHashMap<Int, Session>()

        /** 等待 OPEN 返回 READY：调用方阻塞在 start 这一刻。 */
        private val ready = ConcurrentHashMap<Int, CountDownLatch>()

        @Volatile private var lastError: String = ""

        /** 读帧线程：唯一的写 stdout 之外的消费者。 */
        private val reader = Thread({ pump() }, "lobos-pty-reader").apply {
            isDaemon = true
            start()
        }

        /** 起一个会话并等它就绪（带超时 —— 拿不到 PTY 就明确失败，不静默挂着）。 */
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
                // 把原生侧的诊断带上 —— 否则「没就绪」与「什么都没发生」长得一样，
                // 排障时无从下手（这正是「诊断不许指错方向」那条判据）。
                val d = diagnostics().trim()
                throw IllegalStateException(
                    "PTY 会话 ${sid} 在 ${timeoutMs}ms 内没就绪" +
                        (if (d.isNotEmpty()) "；librivospty 说：$d" else "（librivospty 无任何诊断输出）")
                )
            }
            // OPEN 时原生侧已按默认 24x80 建立；这里把调用方要的尺寸打过去
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

        /**
         * 读帧循环。
         * 一次只处理一帧，且不缓存 DATA 之外的语义 —— 输出顺序由 poll 保证。
         */
        private fun pump() {
            try {
                while (true) {
                    val kind = fromHost.read()            // 阻塞；-1 = 原生侧退出
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
                        fromHost.readFully(buf)   // 短读在管道上常见，必须补齐
                        buf
                    } else ByteArray(0)

                    when (kind) {
                        F_READY -> onReady(sid, payload)
                        F_DATA -> onData(sid, payload)
                        F_EXITED -> onExited(sid)
                        F_ERROR -> {
                            // 帧尾是 NUL 结尾的 C 字符串，但**不保证**恰好一个字节的 NUL
                            // —— 原生侧若被截断就会没有。trimEnd 掉 NUL 再解码，
                            // 否则 Kotlin 会抛 StringIndexOutOfBounds 把真正的错误盖住。
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
                // 原生侧正常退出
            } catch (e: Exception) {
                Log.w(TAG, "读帧线程异常", e)
            } finally {
                for (s in sessions.values) s.markClosed()
                sessions.clear()
                for (l in ready.values) l.countDown()
            }
        }

        private fun onReady(sid: Int, payload: ByteArray) {
            // payload = slave 路径（NUL 结尾）+ 8 字节 winsize
            val nul = payload.indexOf(0)
            if (nul < 0) { lastError = "READY 帧没有 slave 路径"; ready.remove(sid)?.countDown(); return }
            val slave = String(payload, 0, nul, Charsets.UTF_8)
            val rows: Int
            val cols: Int
            if (payload.size >= nul + 1 + 8) {
                val b = java.nio.ByteBuffer.wrap(payload, nul + 1, 8)
                // 小端 —— 原生侧 memcpy 的 struct winsize 是主机序（aarch64 小端）。
                // 不显式指定就是大端，rows=24 读成 6144。
                b.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                rows = b.short.toInt() and 0xFFFF
                cols = b.short.toInt() and 0xFFFF
            } else { rows = DEFAULT_ROWS; cols = DEFAULT_COLS }
            sessions[sid] = Session(this, sid, slave, rows, cols)
            ready.remove(sid)?.countDown()
        }

        private fun onData(sid: Int, payload: ByteArray) {
            // 按 sid 路由 —— 不按「当前谁在跑」。见 Session.onData 的说明。
            try { sessions[sid]?.onData?.invoke(payload) } catch (e: Exception) { Log.w(TAG, "DATA 回调异常", e) }
        }

        private fun onExited(sid: Int) {
            val s = sessions.remove(sid)
            s?.markClosed()
            try { s?.onExit?.invoke() } catch (e: Exception) { Log.w(TAG, "EXITED 回调异常", e) }
        }

        /** 原生侧的诊断输出（启动失败之类），排障时看它。 */
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

    // ── 宿主进程本身：全设备一个，起来就别拆（起一次要几十毫秒）──
    @Volatile private var host: Host? = null
    private val hostLock = Any()

    /**
     * 共享宿主上开一个会话 —— 内置终端窗口用它，**不另起一个宿主**。
     *
     * 为什么不直接把 `host(ctx)` 公开：暴露 Host 就等于让调用方能任意读写
     * 它的内部状态（帧协议、读线程、回调路由）。这里只给「起一个会话」这一件事。
     */
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

    /**
     * 找 librivospty。
     *
     * 两个位置都要认，因为它们的名字不同：
     *   · $PREFIX/bin/pty-session —— 落位后的系统名（PrefixProvisioner 建）
     *   · nativeLibraryDir/librivospty.so —— APK 里的打包名（未落位时的兜底）
     * 找不到就抛 —— 底座不完整时**不许静默退化**到「没有终端也能凑合」，
     * 那正是「程序要终端就报错」被反复踩的地方。
     */
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

    /** 可用性探针：起一个真会话、跑 `tty` 之外的真检查，然后关掉。 */
    fun probe(ctx: Context): Boolean = try {
        val h = host(ctx)
        val s = h.start(listOf("/system/bin/sh", "-c", "exit 0"), timeoutMs = 3_000)
        s.close()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "PTY 探针失败", e)
        false
    }

    /**
     * 一次性执行：建会话 → 跑完 → 收全部输出。
     *
     * 这是 CapabilityBroker 的 shell.exec 在 PTY 可用时走的路径。
     * 输出含 stderr —— PTY 天然合并两个流，这与真实终端一致，也省去分别接两条管道。
     *
     * env/cwd 的处理：原生侧 execve 时**继承宿主进程环境**，所以要带自定义环境
     * 或 cwd，就得另起一个宿主进程（不是复用共享那个 —— 那会把共享宿主的环境改脏）。
     */
    fun runToCompletion(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
    ): Result {
        if (argv.isEmpty()) return Result(false, "", "argv 为空")
        val h = try {
            if (env.isEmpty() && cwd == null) host(ctx) else dedicatedHost(ctx, env, cwd)
        } catch (e: Throwable) {
            return Result(false, "", e.message ?: e.javaClass.simpleName)
        }
        val ownHost = env.isNotEmpty() || cwd != null
        return try {
            val s = h.start(argv, DEFAULT_ROWS, DEFAULT_COLS, timeoutMs.coerceAtLeast(1_000))
            val buf = java.io.ByteArrayOutputStream()
            val done = CountDownLatch(1)
            s.onData = { d -> synchronized(buf) { buf.write(d) } }
            s.onExit = { done.countDown() }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                s.close()
                return Result(false, buf.toString(Charsets.UTF_8.name()), "超时 ${timeoutMs}ms")
            }
            Result(true, buf.toString(Charsets.UTF_8.name()), null)
        } catch (e: Throwable) {
            Result(false, "", e.message ?: e.javaClass.simpleName)
        } finally {
            if (ownHost) runCatching { h.close() }
        }
    }

    /** 另起一个宿主进程，带自定义环境与工作目录（用完即弃，不进共享池）。 */
    private fun dedicatedHost(ctx: Context, env: Map<String, String>, cwd: File?): Host {
        val bin = locateBin(ctx)
        val pb = ProcessBuilder(bin.absolutePath)
        if (cwd != null) pb.directory(cwd)
        if (env.isNotEmpty()) pb.environment().putAll(env)
        return Host(pb, ctx)
    }
}
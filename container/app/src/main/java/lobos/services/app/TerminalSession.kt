package lobos.services.app

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import lobos.kernel.ipc.PtySession

/**
 * 那**交互式终端**那一面 —— 一个逐字**持有** `Session` 的产品组件。
 *
 * 01 裁的《S617-01-PTY-SESSION-NODES-RULING.md》逐字要它：
 *   · 那它逐字属于「产品那一个承载」那一档（ 它逐字**不是**新形态）
 *   · 那一个消费者逐字是「一个逐字持有 `Session` 的交互式终端那一面」
 *   · 形状写死到那四条（ 四条逐字**正是** [PtySession.Session] 那四个方法）：
 *     能 write / 能读 onData / 能 resize / 在 onExit 收尾
 *
 * **缺陷名（C-102）那四个方法名逐字零产品调用者** ——
 *   旧版逐字**没有**任何一处逐字持有那一条 `Session`
 *     `Session.write` / `onData` / `onExit` / `resize` 逐字**零**产品调用者
 *       （ 01 本机实跑确认）
 *   **后果**（07 本轮逐字核出）： 设备上逐字**没有**一条会话
 *     `files/dev/tty/pts` 逐字空、 `files/dev/tty/index.json` 逐字 `[]`
 *     `PtySession.activeSessions(ctx)` 逐字**恒**是 `emptyList()`
 *       （ `openSession` 逐字零调用者、 `Host.start` 逐字只有
 *         `openSession` 与 `runToCompletion` 两个入口、 `runToCompletion`
 *         逐字同步等退出、 `ensureHost` 逐字只起宿主进程）
 *   **处置**： **这一格**逐字是那**持有者**
 *     它逐字**只**在**产品那一个承载**那一档（ 它逐字**不**是探针）
 *     它逐字**不是** `SysDev.render` 起会话（ **不采用**那一族）：
 *       渲染是投影、`wipe(r)` 每次重建、那 `sysdev` 那一族逐字每 15 秒重跑、
 *       且起会话失败逐字会让整个 `files/dev/` 渲染变成 null
 *
 * **而那它逐字怎么起一条会话**：
 *   它逐字**要**那底座那一个解释器（ `ShellEntry.require` 那一格）
 *     一个解释器逐字**要**那一件在 LOB-OS 自己那一份 Linux 上的**入口**
 *       （ `assetEntry` 那一格、 2838153 那一笔逐字修好了它）
 *   一条会话逐字跑那解释器、 它逐字**保持活着**
 *     （ 它逐字**不**是 `runToCompletion` 那一族、 一条逐字**等退出**）
 *
 * **而那它逐字在哪块屏幕上**： 一格逐字归 04 + 02 + 05（ 「要做什么」的裁权），
 *   本笔逐字落的是那**承载**那一侧（ 它逐字是那**持有者**、 它逐字**可**被
 *   任何那一面消费、 它逐字**不**自己摆 UI）。
 */
class TerminalSession private constructor(
    val session: PtySession.Session,
    /** 那一条会话逐字什么时候起的（ 它逐字是那**读数**）。 */
    val startedAtMs: Long,
) {
    /** 那一条会话逐字**已经**退了没有（ 它逐字是那**读数**）。 */
    @Volatile var exited: Boolean = false
        private set

    /** 那退出码（ 它逐字是那**读数**、 它逐字**不**是推出来的）。 */
    @Volatile var exitStatus: Int = -1
        private set

    /** 那信号（ 它逐字是那**读数**）。 */
    @Volatile var exitSignal: Int = 0
        private set

    /** 那**输出**逐字去哪儿（ 消费者逐字**自己**接）。 */
    @Volatile var onOutput: ((ByteArray) -> Unit)? = null

    /** 那**退出**逐字去哪儿（ 消费者逐字**自己**接）。 */
    @Volatile var onClosed: ((status: Int, signal: Int) -> Unit)? = null

    /** 写一条命令进去（ 它逐字是那 [PtySession.Session.write] 那一格）。 */
    fun write(text: String) = session.write(text)

    /** 改那终端的大小（ 它逐字是那 [PtySession.Session.resize] 那一格）。 */
    fun resize(rows: Int, cols: Int) = session.resize(rows, cols)

    /** 关掉那一条会话（ 它逐字是那 [PtySession.Session.close] 那一格）。 */
    fun close() = session.close()

    /** 那一条会话逐字**还活着**没有。 */
    val alive: Boolean get() = !exited && !session.isClosed

    companion object {
        /** 那**默认**那尺寸（ 它逐字是那 PTY 那一族的默认值）。 */
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        /**
         * 起**一条**会话（ 它逐字**持有**它、 它逐字**不**是那一次性的那一族）。
         *
         * @param ctx 那上下文
         * @param argv 那要跑的程序与它的参数（ 它逐字**默认**是那底座那一个解释器）
         * @return 那一条会话（ 它逐字**持有**它、 它逐字**不**是 null）
         * @throws IllegalStateException 那底座那一个解释器逐字**没有**（ 按 C-05 报缺、 不猜填）
         */
        @Throws(IllegalStateException::class)
        fun start(
            ctx: Context,
            argv: List<String> = emptyList(),
            rows: Int = DEFAULT_ROWS,
            cols: Int = DEFAULT_COLS,
            timeoutMs: Long = 5_000,
        ): TerminalSession {
            //  **底座那一个解释器**逐字**要**那登记那一个入口
            //     （ `ShellEntry.require` 那一格逐字**报缺**而不猜填）
            val sh = lobos.services.supply.ShellEntry.require(ctx)
            //  **宿主**逐字要在（ 它逐字**只**是那 APK 里那一份、 它逐字不是要另装的件）
            val ptyBin = lobos.kernel.compat.Compat.ptyHost(ctx)
                ?: throw IllegalStateException(
                    "PTY 宿主不在（librivospty 缺失）—— 报缺，不猜（它逐字是那 APK 里那一份）",
                )
            //  argv 逐字**默认**是那解释器（ 它逐字**没有**参数 它逐字**等**输入）
            //
            //  缺陷名（C-102）**那一档逐字只给那解释器、 它逐字没有那个 `-i`**
            //    旧版逐字是 `listOf(sh.absolutePath)`
            //      argv 逐字经那 `ExecEntry.argv` 那一族逐字变成
            //        `[SYSTEM_LINKER, <解释器>]`（ args.drop(1) 逐字是**空**）
            //      Android linker64 逐字把那 argv **原样**传给那程序
            //        （ 程序逐字的 argv[0] 逐字是那 linker 自己、 argv[1] 逐字
            //          **就是**那程序逐字的第一个参数）
            //        解释器逐字收到的 argv = `[<解释器>, <解释器>]`
            //      bash 那一族逐字**没有** `-c` 也没有 `-s` 时
            //        逐字把 argv[1] 逐字**当脚本名**逐字去 exec
            //        脚本名逐字就是那解释器自己
            //        **错误**逐字是
            //          `…/files/usr/bin/bash: …/files/usr/bin/bash: cannot execute binary file`
            //      **会话**逐字**当场就退出了**（ status=0 signal=0）
            //    **那**与那 `-c` 那一档逐字**不一样**：
            //      `LocalExec` 那一族逐字给的是 `[<解释器>, "-c", <命令>]`
            //        argv 逐字是 `[SYSTEM_LINKER, <解释器>, "-c", <命令>]`
            //        bash 逐字收到 argv = `[<解释器>, "-c", <命令>]`
            //        `-c` 逐字是那**命令**、 **脚本名**那一格逐字**不**是它
            //    **处置**： **交互式**那一档逐字给那解释器逐字**加**一个 `-i`
            //      （ 它逐字是「那解释器逐字是交互式」那一档的声明）
            //      加了它之后 bash 逐字**不**把 argv[1] 逐字当脚本名
            //      argv 逐字是 `[SYSTEM_LINKER, <解释器>, "-i"]`
            //      bash 逐字收到 argv = `[<解释器>, "-i"]`、 它逐字**读**那 stdin
            val effArgv = if (argv.isEmpty()) listOf(sh.absolutePath, "-i") else argv
            val s = lobos.kernel.ipc.PtySession.openSession(ctx, ptyBin, effArgv, rows, cols, timeoutMs)
            val t = TerminalSession(s, System.currentTimeMillis())
            //  **四条**逐字接上（ 它们逐字正是 01 裁的那四条）
            //     · 能读 onData 输出逐字去消费者
            s.onData = { d -> t.onOutput?.invoke(d) }
            //     · 在 onExit 收尾 退出码与那信号逐字**记**下来
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

/**
 * 那**终端会话池** —— 持有那几条活着的会话。
 *
 * **缺陷名（C-102）那会话逐字没人持有、 它逐字只活在那一次调用里** ——
 *   旧版逐字**没有**任何一处逐字持有那一条 `Session`
 *     `PtySession.openSession` 逐字零调用者
 *       （ 01 本机实跑确认、 07 本轮逐字核到设备上逐字没有会话）
 *   **处置**： **这一格**逐字持有那几条
 *     它们逐字**跨调用**活着（ 它们逐字**不是**那一次性的那一族）
 *     `PtySession.activeSessions(ctx)` 逐字**因此**非空
 *       （ `files/dev/tty/pts/<NNN>` 逐字**因此**存在、 **一格**逐字**解**了）
 */
object TerminalSessions {
    private val lock = Any()
    private val held = LinkedHashMap<String, TerminalSession>()

    /**
     * 起**一条**会话并**持有**它（ 它逐字**跨调用**活着）。
     *
     * @param id 那一条会话的**名字**（ 它逐字是那**消费者**给的那一个）
     * @return 那一条会话
     */
    fun open(ctx: Context, id: String, argv: List<String> = emptyList()): TerminalSession {
        val t = TerminalSession.start(ctx, argv)
        synchronized(lock) {
            held.put(id, t)?.let { old ->
                //  **同名**那一条逐字**先关掉**（ 它逐字**不**留着两条）
                runCatching { old.close() }
            }
            held[id] = t
        }
        return t
    }

    /** 那**持有**的那几条（ 它逐字是那**读数**、 它逐字**不**是推出来的）。 */
    fun list(): List<TerminalSession> = synchronized(lock) { held.values.toList() }

    /** 那**持有**的那一条（ 它逐字**读不到**逐字是 null）。 */
    fun get(id: String): TerminalSession? = synchronized(lock) { held[id] }

    /** 关掉**一条**（ 它逐字**从**那持有**那一族**里逐字去掉）。 */
    fun close(id: String): Boolean {
        val t = synchronized(lock) { held.remove(id) } ?: return false
        runCatching { t.close() }
        return true
    }

    /** 关掉**全部**（ 它逐字**从**那持有**那一族**里逐字清空）。 */
    fun closeAll() {
        val all = synchronized(lock) {
            val c = held.values.toList()
            held.clear()
            c
        }
        for (t in all) runCatching { t.close() }
    }
}

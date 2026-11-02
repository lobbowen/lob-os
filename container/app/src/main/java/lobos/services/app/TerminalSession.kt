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
 *   · 那它逐字属于「产品那一个承载」那一档（=> 而那它逐字**不是**新形态）
 *   · 那一个消费者逐字是「一个逐字持有 `Session` 的交互式终端那一面」
 *   · => 而那形状写死到那四条（=> 而那四条逐字**正是** [PtySession.Session] 那四个方法）：
 *     能 write / 能读 onData / 能 resize / 在 onExit 收尾
 *
 * **缺陷名（C-102）那四个方法名逐字零产品调用者** ——
 *   => 而那旧版逐字**没有**任何一处逐字持有那一条 `Session`
 *     => 而那 `Session.write` / `onData` / `onExit` / `resize` 逐字**零**产品调用者
 *       （=> 而那 01 本机实跑确认）
 *   => 而那**后果**（07 本轮逐字核出）：=> 而那设备上逐字**没有**一条会话
 *     => 而那 `files/dev/tty/pts` 逐字空、=> 而那 `files/dev/tty/index.json` 逐字 `[]`
 *     => 而那 `PtySession.activeSessions(ctx)` 逐字**恒**是 `emptyList()`
 *       （=> 而那 `openSession` 逐字零调用者、=> 而那 `Host.start` 逐字只有
 *         `openSession` 与 `runToCompletion` 两个入口、=> 而那 `runToCompletion`
 *         逐字同步等退出、=> 而那 `ensureHost` 逐字只起宿主进程）
 *   => 而那**处置**：=> 而那**这一格**逐字是那**持有者**
 *     => 而那它逐字**只**在**产品那一个承载**那一档（=> 而那它逐字**不**是探针）
 *     => 而那它逐字**不是** `SysDev.render` 起会话（=> 而那**不采用**那一族）：
 *       渲染是投影、`wipe(r)` 每次重建、那 `sysdev` 那一族逐字每 15 秒重跑、
 *       => 而且起会话失败逐字会让整个 `files/dev/` 渲染变成 null
 *
 * **而那它逐字怎么起一条会话**：
 *   => 而那它逐字**要**那底座那一个解释器（=> 而那 `ShellEntry.require` 那一格）
 *     => 而那一个解释器逐字**要**那一件在 LOB-OS 自己那一份 Linux 上的**入口**
 *       （=> 而那 `assetEntry` 那一格、=> 而那 2838153 那一笔逐字修好了它）
 *   => 而那一条会话逐字跑那解释器、=> 而那它逐字**保持活着**
 *     （=> 而那它逐字**不**是 `runToCompletion` 那一族、=> 而那一条逐字**等退出**）
 *
 * **而那它逐字在哪块屏幕上**：=> 而那一格逐字归 04 + 02 + 05（=> 而那「要做什么」的裁权），
 *   => 而那本笔逐字落的是那**承载**那一侧（=> 而那它逐字是那**持有者**、=> 而那它逐字**可**被
 *   任何那一面消费、=> 而那它逐字**不**自己摆 UI）。
 */
class TerminalSession private constructor(
    val session: PtySession.Session,
    /** 那一条会话逐字什么时候起的（=> 而那它逐字是那**读数**）。 */
    val startedAtMs: Long,
) {
    /** 那一条会话逐字**已经**退了没有（=> 而那它逐字是那**读数**）。 */
    @Volatile var exited: Boolean = false
        private set

    /** 那退出码（=> 而那它逐字是那**读数**、=> 而那它逐字**不**是推出来的）。 */
    @Volatile var exitStatus: Int = -1
        private set

    /** 那信号（=> 而那它逐字是那**读数**）。 */
    @Volatile var exitSignal: Int = 0
        private set

    /** 那**输出**逐字去哪儿（=> 而那消费者逐字**自己**接）。 */
    @Volatile var onOutput: ((ByteArray) -> Unit)? = null

    /** 那**退出**逐字去哪儿（=> 而那消费者逐字**自己**接）。 */
    @Volatile var onClosed: ((status: Int, signal: Int) -> Unit)? = null

    /** 写一条命令进去（=> 而那它逐字是那 [PtySession.Session.write] 那一格）。 */
    fun write(text: String) = session.write(text)

    /** 改那终端的大小（=> 而那它逐字是那 [PtySession.Session.resize] 那一格）。 */
    fun resize(rows: Int, cols: Int) = session.resize(rows, cols)

    /** 关掉那一条会话（=> 而那它逐字是那 [PtySession.Session.close] 那一格）。 */
    fun close() = session.close()

    /** 那一条会话逐字**还活着**没有。 */
    val alive: Boolean get() = !exited && !session.isClosed

    companion object {
        /** 那**默认**那尺寸（=> 而那它逐字是那 PTY 那一族的默认值）。 */
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80

        /**
         * 起**一条**会话（=> 而那它逐字**持有**它、=> 而那它逐字**不**是那一次性的那一族）。
         *
         * @param ctx 那上下文
         * @param argv 那要跑的程序与它的参数（=> 而那它逐字**默认**是那底座那一个解释器）
         * @return 那一条会话（=> 而那它逐字**持有**它、=> 而那它逐字**不**是 null）
         * @throws IllegalStateException 那底座那一个解释器逐字**没有**（=> 而那按 C-05 报缺、=> 而不猜填）
         */
        @Throws(IllegalStateException::class)
        fun start(
            ctx: Context,
            argv: List<String> = emptyList(),
            rows: Int = DEFAULT_ROWS,
            cols: Int = DEFAULT_COLS,
            timeoutMs: Long = 5_000,
        ): TerminalSession {
            //  => 而那**底座那一个解释器**逐字**要**那登记那一个入口
            //     （=> 而那 `ShellEntry.require` 那一格逐字**报缺**而不猜填）
            val sh = lobos.services.supply.ShellEntry.require(ctx)
            //  => 而那**宿主**逐字要在（=> 而那它逐字**只**是那 APK 里那一份、=> 而那它逐字不是要另装的件）
            val ptyBin = lobos.kernel.compat.Compat.ptyHost(ctx)
                ?: throw IllegalStateException(
                    "PTY 宿主不在（librivospty 缺失）—— 报缺，不猜（=> 而那它逐字是那 APK 里那一份）",
                )
            //  => 而那 argv 逐字**默认**是那解释器（=> 而那它逐字**没有**参数 => 而那它逐字**等**输入）
            //
            //  缺陷名（C-102）**那一档逐字只给那解释器、=> 而而那它逐字没有那个 `-i`**
            //    => 而那旧版逐字是 `listOf(sh.absolutePath)`
            //      => 而那 argv 逐字经那 `ExecEntry.argv` 那一族逐字变成
            //        `[SYSTEM_LINKER, <解释器>]`（=> 而那 args.drop(1) 逐字是**空**）
            //      => 而那 Android linker64 逐字把那 argv **原样**传给那程序
            //        （=> 而那程序逐字的 argv[0] 逐字是那 linker 自己、=> 而那 argv[1] 逐字
            //          **就是**那程序逐字的第一个参数）
            //        => 而那解释器逐字收到的 argv = `[<解释器>, <解释器>]`
            //      => 而那 bash 那一族逐字**没有** `-c` 也没有 `-s` 时
            //        逐字把 argv[1] 逐字**当脚本名**逐字去 exec
            //        => 而那脚本名逐字就是那解释器自己
            //        => 而那**错误**逐字是
            //          `…/files/usr/bin/bash: …/files/usr/bin/bash: cannot execute binary file`
            //      => 而那**会话**逐字**当场就退出了**（=> 而那 status=0 signal=0）
            //    => 而那**那**与那 `-c` 那一档逐字**不一样**：
            //      => 而那 `LocalExec` 那一族逐字给的是 `[<解释器>, "-c", <命令>]`
            //        => 而那 argv 逐字是 `[SYSTEM_LINKER, <解释器>, "-c", <命令>]`
            //        => 而那 bash 逐字收到 argv = `[<解释器>, "-c", <命令>]`
            //        => 而那 `-c` 逐字是那**命令**、=> 而那**脚本名**那一格逐字**不**是它
            //    => 而那**处置**：=> 而那**交互式**那一档逐字给那解释器逐字**加**一个 `-i`
            //      （=> 而那它逐字是「那解释器逐字是交互式」那一档的声明）
            //      => 而那加了它之后 bash 逐字**不**把 argv[1] 逐字当脚本名
            //      => 而那 argv 逐字是 `[SYSTEM_LINKER, <解释器>, "-i"]`
            //      => 而那 bash 逐字收到 argv = `[<解释器>, "-i"]`、=> 而那它逐字**读**那 stdin
            val effArgv = if (argv.isEmpty()) listOf(sh.absolutePath, "-i") else argv
            val s = lobos.kernel.ipc.PtySession.openSession(ctx, ptyBin, effArgv, rows, cols, timeoutMs)
            val t = TerminalSession(s, System.currentTimeMillis())
            //  => 而那**四条**逐字接上（=> 而那它们逐字正是 01 裁的那四条）
            //     · 能读 onData => 而那输出逐字去消费者
            s.onData = { d -> t.onOutput?.invoke(d) }
            //     · 在 onExit 收尾 => 而那退出码与那信号逐字**记**下来
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
 * **缺陷名（C-102）那会话逐字没人持有、=> 而而那它逐字只活在那一次调用里** ——
 *   => 而那旧版逐字**没有**任何一处逐字持有那一条 `Session`
 *     => 而那 `PtySession.openSession` 逐字零调用者
 *       （=> 而那 01 本机实跑确认、=> 而那 07 本轮逐字核到设备上逐字没有会话）
 *   => 而那**处置**：=> 而那**这一格**逐字持有那几条
 *     => 而那它们逐字**跨调用**活着（=> 而那它们逐字**不是**那一次性的那一族）
 *     => 而那 `PtySession.activeSessions(ctx)` 逐字**因此**非空
 *       （=> 而那 `files/dev/tty/pts/<NNN>` 逐字**因此**存在、=> 而那**一格**逐字**解**了）
 */
object TerminalSessions {
    private val lock = Any()
    private val held = LinkedHashMap<String, TerminalSession>()

    /**
     * 起**一条**会话并**持有**它（=> 而那它逐字**跨调用**活着）。
     *
     * @param id 那一条会话的**名字**（=> 而那它逐字是那**消费者**给的那一个）
     * @return 那一条会话
     */
    fun open(ctx: Context, id: String, argv: List<String> = emptyList()): TerminalSession {
        val t = TerminalSession.start(ctx, argv)
        synchronized(lock) {
            held.put(id, t)?.let { old ->
                //  => 而那**同名**那一条逐字**先关掉**（=> 而那它逐字**不**留着两条）
                runCatching { old.close() }
            }
            held[id] = t
        }
        return t
    }

    /** 那**持有**的那几条（=> 而那它逐字是那**读数**、=> 而那它逐字**不**是推出来的）。 */
    fun list(): List<TerminalSession> = synchronized(lock) { held.values.toList() }

    /** 那**持有**的那一条（=> 而那它逐字**读不到**逐字是 null）。 */
    fun get(id: String): TerminalSession? = synchronized(lock) { held[id] }

    /** 关掉**一条**（=> 而那它逐字**从**那持有**那一族**里逐字去掉）。 */
    fun close(id: String): Boolean {
        val t = synchronized(lock) { held.remove(id) } ?: return false
        runCatching { t.close() }
        return true
    }

    /** 关掉**全部**（=> 而那它逐字**从**那持有**那一族**里逐字清空）。 */
    fun closeAll() {
        val all = synchronized(lock) {
            val c = held.values.toList()
            held.clear()
            c
        }
        for (t in all) runCatching { t.close() }
    }
}

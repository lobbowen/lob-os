package lobos.runtime

import android.content.Context
import java.io.File

/**
 * 本地执行 —— 程序要跑一条命令时的默认路径。
 *
 * ── 为什么不用 ADB ──
 * 原来 `shell.exec` 走 `AdbClientRunner`（无线调试）。后果很直接：
 * **关掉无线调试，程序就不能执行命令**。而无线调试是「配对一次、随时可能断」
 * 的东西 —— 把它放在命令执行的关键路径上，等于把系统能力挂在一条会断的链路上。
 * 那正是「程序要终端就报错」「断开 ADB 就不能执行命令」的根因。
 *
 * ── 现在的路径 ──
 * 优先本地 PTY 执行（[PtySession]）：不开 PTY 时退回 ProcessBuilder，
 * 两条都不通才回落 ADB，并在返回值里**明说**用的是哪条路。
 *
 * ── 为什么保留 ADB 回落 ──
 * 不是「不敢去掉」，而是 ADB 在某些场景确实有独有价值（它能给设备上的
 * 另一个进程执行）。但它必须是**降级路径**而不是主路径，且降级要可见 ——
 * 调用方能知道自己拿到的是哪条路的结果。
 *
 * ── 命令形态 ──
 * [LocalExec.run] 收的是「程序 + 参数」，不是一整串 shell 命令。
 * 需要 shell 语法（管道、重定向、变量）时用 [LocalExec.runShell]，
 * 它显式经底座的 bash —— 不做「猜用户想不想要 shell」的隐式转换。
 */
object LocalExec {

    /** 执行通路 —— 调用方据此知道结果的可信度与差异。 */
    enum class Via {
        /** 底座 PTY（程序得到真终端：isatty 为真、可交互、能读窗口大小） */
        PTY,

        /** ProcessBuilder（无 PTY，但完全本地，不需要任何外部连接） */
        PLAIN,

        /** ADB 回落（无线调试；关掉它这条就不通） */
        ADB,
    }

    data class Outcome(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val via: Via,
        val error: String? = null,
    )

    /**
     * 执行 argv。程序自己已经把参数拆好了 —— 不用 shell 就不会有一整类转义问题。
     *
     * 优先给 PTY：PTY 的输出天然合并 stdout/stderr（与真实终端一致），
     * 所以 [Outcome.stdout] 里含两者；[Outcome.stderr] 在 PTY 通路下为空。
     */
    fun run(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
        preferPty: Boolean = true,
    ): Outcome {
        if (argv.isEmpty()) return Outcome(false, -1, "", "", Via.PLAIN, "argv 为空")

        if (preferPty) {
            val r = PtySession.runToCompletion(ctx, argv, env, cwd, timeoutMs)
            if (r.ok) return Outcome(true, 0, r.output, "", Via.PTY)
            // PTY 失败**不静默**：记下原因再往下走，调用方在结果里能看到。
            val ptyWhy = r.error ?: "PTY 执行失败"
            val plain = runPlain(ctx, argv, env, cwd, timeoutMs)
            if (plain.ok) return plain.copy(via = Via.PLAIN, error = "PTY 不可用（$ptyWhy），已用无 PTY 通路")
            return Outcome(
                false, -1, plain.stdout, plain.stderr, Via.PLAIN,
                "PTY 不可用（$ptyWhy）；无 PTY 通路也失败：${plain.error ?: plain.stderr.take(200)}",
            )
        }
        return runPlain(ctx, argv, env, cwd, timeoutMs)
    }

    /** 无 PTY 的本地执行 —— 仍是本地，只是不给终端。 */
    fun runPlain(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
    ): Outcome {
        return try {
            val s = ProcessSupervisor.spawn(
                command = argv,
                cwd = cwd,
                env = env.takeIf { it.isNotEmpty() },
                envMode = if (env.isEmpty()) ProcessSupervisor.ENV_INHERIT else ProcessSupervisor.ENV_MERGE,
                redirectErrorStream = false,
                owner = ProcessSupervisor.OWNER_PROGRAM,
            )
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { runCatching { s.process.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } } }
            val tErr = Thread { runCatching { s.process.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } } }
            tOut.isDaemon = true; tErr.isDaemon = true
            tOut.start(); tErr.start()
            val exited = s.process.waitFor(timeoutMs.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                s.process.destroy()
                return Outcome(false, -1, out.toString(), err.toString(), Via.PLAIN, "超时 ${timeoutMs}ms")
            }
            tOut.join(500); tErr.join(500)
            val code = s.process.exitValue()
            Outcome(true, code, out.toString(), err.toString(), Via.PLAIN)
        } catch (e: Throwable) {
            Outcome(false, -1, "", "", Via.PLAIN, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 执行一条**需要 shell 语法**的命令（管道 / 重定向 / 变量 / 通配）。
     *
     * 显式走底座的 bash，不隐式把字符串塞进 `sh -c`：
     *   · 底座 bash 缺失时明确报错，而不是悄悄落到 `/system/bin/sh`
     *     （那会让用户的脚本在另一套语义下跑出别的结果）；
     *   · 缺 bash 本身是底座不完整，要看得见。
     */
    fun runShell(
        ctx: Context,
        command: String,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
        preferPty: Boolean = true,
    ): Outcome {
        if (command.isBlank()) return Outcome(false, -1, "", "", Via.PLAIN, "命令为空")
        val bash = PrefixProvisioner.bashBin(ctx)
            ?: return Outcome(
                false, -1, "", "", Via.PLAIN,
                "底座没有 bash —— 不能静默落到 /system/bin/sh（那会让命令在另一套语义下跑）。" +
                    "底座不完整。",
            )
        return run(ctx, listOf(bash.absolutePath, "-c", command), env, cwd, timeoutMs, preferPty)
    }

    /**
     * ADB 回落路径 —— 只在本地两条都不通时用。
     *
     * 单独成函数是为了让「我们退回了 ADB」这件事在代码里**看得见**，
     * 而不是散落在业务代码的 if 里。
     */
    fun viaAdb(
        ctx: Context,
        command: String,
        timeoutMs: Long,
    ): Outcome {
        return try {
            val r = lobos.capability.AdbClientRunner.shell(ctx, command, null, null, timeoutMs)
            if (r.ok) {
                Outcome(
                    true, 0, r.json?.optString("out", "") ?: r.raw, "", Via.ADB,
                    "注意：本条经无线调试执行（本地 PTY 与无 PTY 通路都不可用）。" +
                        "关掉无线调试就没有这条路了。",
                )
            } else {
                Outcome(false, -1, "", "", Via.ADB, "ADB 也失败：${r.error ?: r.raw.take(200)}")
            }
        } catch (e: Throwable) {
            Outcome(false, -1, "", "", Via.ADB, "ADB 不可用：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
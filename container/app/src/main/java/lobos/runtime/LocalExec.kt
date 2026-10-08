package lobos.runtime

import android.content.Context
import java.io.File

object LocalExec {

    enum class Via {
        PTY,

        PLAIN,

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
            if (r.completed) {
                return Outcome(
                    ok = r.ok,
                    exitCode = r.exitCode,
                    stdout = r.output,
                    stderr = "",
                    via = Via.PTY,
                    error = r.error,
                )
            }
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

    fun runShell(
        ctx: Context,
        command: String,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
        preferPty: Boolean = true,
    ): Outcome {
        if (command.isBlank()) return Outcome(false, -1, "", "", Via.PLAIN, "命令为空")
        val shell = PrefixProvisioner.shellBin(ctx)
            ?: return Outcome(
                false, -1, "", "", Via.PLAIN,
                "底座没有命令解释器 —— 不能静默落到 /system/bin/sh（那会让命令在另一套语义下跑）。" +
                    "底座不完整。",
            )
        return run(ctx, listOf(shell.absolutePath, "-c", command), env, cwd, timeoutMs, preferPty)
    }

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
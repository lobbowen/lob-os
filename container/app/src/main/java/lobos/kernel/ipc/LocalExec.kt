package lobos.kernel.ipc

import android.content.Context
import java.io.File
import lobos.kernel.proc.ProcessSupervisor

object LocalExec {

    enum class Via {
        PTY,

        PLAIN,
    }

    data class Outcome(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val via: Via,
        val error: String? = null,
    )

    const val MAX_CAPTURED_OUTPUT_CHARS = 4 * 1024 * 1024

    private fun droppedNote(outDropped: Long, errDropped: Long): String? {
        if (outDropped <= 0L && errDropped <= 0L) return null
        return "那输出逐字超了上限、**丢**的一截逐字 = stdout " + outDropped +
            " 字节、stderr " + errDropped + " 字节、按 C-05 报缺"
    }

    fun run(
        ctx: Context,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
        preferPty: Boolean = true,
    ): Outcome {
        if (argv.isEmpty()) return Outcome(false, -1, "", "", Via.PLAIN, "argv 为空")

        val ptyBin = lobos.kernel.compat.Compat.ptyHost(ctx)
        if (preferPty && ptyBin != null) {
            val r = PtySession.runToCompletion(ctx, ptyBin, argv, env, cwd, timeoutMs)
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
                ctx,
                command = argv,
                cwd = cwd,
                env = env.takeIf { it.isNotEmpty() },
                envMode = if (env.isEmpty()) ProcessSupervisor.ENV_INHERIT else ProcessSupervisor.ENV_MERGE,
                redirectErrorStream = false,
                owner = ProcessSupervisor.OWNER_PROGRAM,
            )

            val out = StringBuilder()
            val err = StringBuilder()
            var outDropped = 0L
            var errDropped = 0L
            val tOut = Thread {
                runCatching {
                    s.process.inputStream.bufferedReader().forEachLine {
                        synchronized(out) {
                            if (out.length + it.length + 1 <= MAX_CAPTURED_OUTPUT_CHARS) {
                                out.append(it).append('\n')
                            } else {
                                outDropped += it.length + 1
                            }
                        }
                    }
                }
            }
            val tErr = Thread {
                runCatching {
                    s.process.errorStream.bufferedReader().forEachLine {
                        synchronized(err) {
                            if (err.length + it.length + 1 <= MAX_CAPTURED_OUTPUT_CHARS) {
                                err.append(it).append('\n')
                            } else {
                                errDropped += it.length + 1
                            }
                        }
                    }
                }
            }
            tOut.isDaemon = true; tErr.isDaemon = true
            tOut.start(); tErr.start()
            val exited = s.process.waitFor(timeoutMs.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) {
                s.process.destroy()
                return Outcome(
                    false, -1, out.toString(), err.toString(), Via.PLAIN,
                    "超时 ${timeoutMs}ms" + droppedNote(outDropped, errDropped),
                )
            }
            tOut.join(500); tErr.join(500)
            val code = s.process.exitValue()
            Outcome(true, code, out.toString(), err.toString(), Via.PLAIN, droppedNote(outDropped, errDropped))
        } catch (e: ProcessSupervisor.NotExecutable) {

            Outcome(
                false,
                ProcessSupervisor.ENOEXEC_EXIT_CODE,
                "",
                e.message ?: ProcessSupervisor.ENOEXEC_DIAGNOSTIC,
                Via.PLAIN,
                e.message ?: ProcessSupervisor.ENOEXEC_DIAGNOSTIC,
            )
        } catch (e: Throwable) {
            Outcome(false, -1, "", "", Via.PLAIN, e.message ?: e.javaClass.simpleName)
        }
    }

    fun runShell(
        ctx: Context,
        command: String,
        shellBin: File,
        env: Map<String, String> = emptyMap(),
        cwd: File? = null,
        timeoutMs: Long = 10_000,
        preferPty: Boolean = true,
    ): Outcome {
        if (command.isBlank()) return Outcome(false, -1, "", "", Via.PLAIN, "命令为空")
        if (!shellBin.isFile) {
            return Outcome(
                false, -1, "", "", Via.PLAIN,
                "底座没有命令解释器（" + shellBin.path + "）—— 不能静默落到 /system/bin/sh" +
                    "（那会让命令在另一套语义下跑）。底座不完整。",
            )
        }
        return run(ctx, listOf(shellBin.absolutePath, "-c", command), env, cwd, timeoutMs, preferPty)
    }
}
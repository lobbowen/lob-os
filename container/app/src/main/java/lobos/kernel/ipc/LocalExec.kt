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

    /**
     * 那**输出**逐字那一个上限（=> 而那它逐字**有**那个上限、=> 而那超出那一截逐字**丢**）。
     *
     *   => 而那**上限**逐字是那 **4 MiB** 逐字（=> 而与那 `PtySession` 那一族那一个上限逐字**同一个**）
     *   => 而那**期望输出**那一族逐字**要**的是那**开头**那一些（=> 而那它逐字**不**是全量）
     *   => 而那**丢了多少**逐字**说清**（=> 而那按 C-05 不猜填）
     */
    const val MAX_CAPTURED_OUTPUT_CHARS = 4 * 1024 * 1024

    /** 那**丢了多少**逐字**说清**（=> 而那按 C-05 不猜填、=> 而那**丢**这一件逐字**报缺**）。 */
    private fun droppedNote(outDropped: Long, errDropped: Long): String? {
        if (outDropped <= 0L && errDropped <= 0L) return null
        return "那输出逐字超了上限、=> 而那**丢**的一截逐字 = stdout " + outDropped +
            " 字节、stderr " + errDropped + " 字节、=> 而那按 C-05 报缺"
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
            //  缺陷名（C-102）**那一格逐字把那**全部**输出逐字**累积**进那堆、=> 而而那它逐字**没有上限** ——
            //    => 而那旧版逐字是 `val out = StringBuilder()` / `val err = StringBuilder()`
            //      => 而那**两**个逐字**无上限**地逐字涨
            //        （=> 而那 `StringBuilder` 逐字**每个**字符逐字**再存**一份、
            //          => 而那 `toString()` 逐字**又**是**一份**、=> 而那**峰值**逐字是**三**份）
            //      => 而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
            //        · 而那 logcat 逐字 `Throwing OutOfMemoryError "Failed to allocate a 117031556 byte
            //          allocation …"`（逐字 13:34:57.367、=> 而那 App 逐字刚起那 10 秒）
            //        => 而那**分配量**逐字 117 MB、=> 而与设备上逐字那一件逐字**同形**
            //        （=> 而那 `node` 那一件逐字 116779696 字节）
            //      => 而那**常驻**那一族逐字是「逐字那 App 逐字常驻逐字整读那一大件」
            //        => 而那**判据**逐字是「那一份逐字在那一段逐字还在逐字被读」那一个事实上
            //    => 而那**处置**：=> 而那两段逐字**有**一个上限、=> 而那超出那一截逐字**丢**
            //      => 而那**期望输出**那一族逐字**要**的是那**开头**那一些（=> 而那它逐字**不**是全量）
            //      => 而那**上限**逐字**要**报缺（=> 而那按 C-05 不猜填、=> 而那**丢了多少**逐字**说清**）
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
            //  => 而那 M7-05 那一族逐字要的那两样：=> 而那**退出码**逐字是那一个具体的数、
            //    => 而那**诊断原文**逐字**进**那 stderr
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
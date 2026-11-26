package lobos.system

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * V2 command execution: run a program inside the Ubuntu rootfs through the
 * fastroot syscall engine. Independent of the V1 LocalExec/ProcessSupervisor
 * path so both engines can coexist during migration.
 *
 * Design: docs/spec/SYSTEM-ARCHITECTURE-V2.md
 */
object SystemExec {

    data class Outcome(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String? = null,
    )

    /**
     * Run [program] (a virtual path inside Ubuntu, e.g. "/usr/bin/apt-get")
     * with [args], capture stdout/stderr, wait up to [timeoutMs].
     * Must be called off the main thread.
     */
    fun run(
        ctx: Context,
        program: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): Outcome {
        if (!UbuntuSystem.isReady(ctx)) {
            return Outcome(false, -1, "", "",
                "系统盘未就绪：检查内置 asset 解压与 fastroot native 库")
        }
        return try {
            val argv = UbuntuSystem.launchArgv(ctx, program, args)
            val pb = ProcessBuilder(argv)
            // Merge stderr into stdout before reading: reading two pipes
            // sequentially can deadlock when the child fills the second pipe.
            pb.redirectErrorStream(true)
            // Fastroot needs a clean environment; only FR_* vars plus caller extras.
            pb.environment().clear()
            pb.environment().putAll(UbuntuSystem.environment(ctx))
            pb.environment()["FR_EXE"] = UbuntuSystem.frExe(program)
            pb.environment().putAll(env)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!done) {
                p.destroyForcibly()
                Outcome(false, -1, out, "", "命令超时 ${timeoutMs}ms")
            } else {
                Outcome(p.exitValue() == 0, p.exitValue(), out, "")
            }
        } catch (t: Throwable) {
            Outcome(false, -1, "", "", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }
}

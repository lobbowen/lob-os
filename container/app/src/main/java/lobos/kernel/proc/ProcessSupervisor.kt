package lobos.kernel.proc

import android.content.Context
import java.io.File

object ProcessSupervisor {

    const val OWNER_PROGRAM = "program"
    const val OWNER_VERIFIER = "verifier"
    const val OWNER_CHECK = "check"

    const val ENV_INHERIT = "inherit"
    const val ENV_CLEAR = "clear"
    const val ENV_MERGE = "merge"

    data class Spawned(
        val process: Process,
        val pid: Int,
        val owner: String,
        val programId: String?,
    )

    const val ENOEXEC_EXIT_CODE = 126
    const val ENOEXEC_DIAGNOSTIC = "入口不是可执行的形态（既不是 ELF 也不是 #!）—— 拒绝调起，不当程序跑"

    fun enoexecDiagnostic(entry: File): String =
        ENOEXEC_DIAGNOSTIC + "：" + entry.absolutePath +
            "（" + ExecEntry.describe(entry) + "）"

    class NotExecutable(val entry: File) : IllegalStateException(
        enoexecDiagnostic(entry)
    )

    fun spawn(
        ctx: Context,
        command: List<String>,
        cwd: File? = null,
        env: Map<String, String>? = null,
        envMode: String = ENV_INHERIT,
        redirectErrorStream: Boolean = false,
        owner: String,
        programId: String? = null,
    ): Spawned {
        val first = java.io.File(command[0])
        val real = ExecEntry.argv(first, command)

        if (real == null) {
            val diag = enoexecDiagnostic(first)

            System.err.println(diag)
            runCatching {
                android.util.Log.w("ProcessSupervisor", diag)
            }
            throw NotExecutable(first)
        }
        val pb = ProcessBuilder(real)
        if (cwd != null) pb.directory(cwd)
        val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
            ctx,
            lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
            null,
        )
        if (envMode != ENV_INHERIT) pb.environment().clear()
        for ((k, v) in view) pb.environment()[k] = v
        pb.environment().remove("LD_PRELOAD")
        if (env != null) pb.environment().putAll(env)
        if (redirectErrorStream) pb.redirectErrorStream(true)

        val p = pb.start()
        val pid = pidOf(p)

        return Spawned(p, pid, owner, programId)
    }

    private fun pidOf(p: Process?): Int {
        if (p == null) return -1
        val m = Regex("""pid=(\d+)""").find(p.toString()) ?: return -1
        return m.groupValues.getOrNull(1)?.toIntOrNull() ?: -1
    }
}
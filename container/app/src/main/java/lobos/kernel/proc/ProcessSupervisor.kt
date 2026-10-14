package lobos.kernel.proc

import android.content.Context
import java.io.File

object ProcessSupervisor {

    const val OWNER_PROGRAM = "program"
    const val OWNER_ADB_CLIENT = "adb-client"
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
        val real = ExecEntry.argv(java.io.File(command[0]), command)
        val pb = ProcessBuilder(if (real != null) real else command)
        if (cwd != null) pb.directory(cwd)
        val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(
            ctx,
            lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx),
            null,
        )
        if (envMode != ENV_INHERIT) pb.environment().clear()
        for ((k, v) in view) pb.environment()[k] = v
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
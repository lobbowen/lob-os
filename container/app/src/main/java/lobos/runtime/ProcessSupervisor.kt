package lobos.runtime

import java.io.File

object ProcessSupervisor {

    const val OWNER_PROGRAM = "program"
    const val OWNER_ADB_CLIENT = "adb-client"
    const val OWNER_VERIFIER = "verifier"
    const val OWNER_PROBE = "probe"

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
        command: List<String>,
        cwd: File? = null,
        env: Map<String, String>? = null,
        envMode: String = ENV_INHERIT,
        redirectErrorStream: Boolean = false,
        owner: String,
        programId: String? = null,
    ): Spawned {
        val pb = ProcessBuilder(command)
        if (cwd != null) pb.directory(cwd)
        if (envMode != ENV_INHERIT) pb.environment().clear()
        if (env != null) pb.environment().putAll(env)
        if (redirectErrorStream) pb.redirectErrorStream(true)

        val p = pb.start()
        val pid = pidOf(p)

        return Spawned(p, pid, owner, programId)
    }

    private fun pidOf(p: Process?): Int {
        if (p == null) return -1
        return runCatching {
            Regex("""pid=(\d+)""").find(p.toString())?.groupValues?.get(1)?.toIntOrNull()
        }.getOrDefault(-1)
    }
}
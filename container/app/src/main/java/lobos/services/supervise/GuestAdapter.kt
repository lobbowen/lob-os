package lobos.services.supervise

import lobos.services.reg.RuntimeEnvironment
import java.io.File

object GuestAdapter {

    data class ProgramInputs(
        val ctx: android.content.Context,
        val root: RuntimeEnvironment.TreeRoot,
        val nodeBin: File?,
        val programDir: File,
        val programEntry: File,
        val uiDir: File,
        val flockSo: File?,
        val programId: String = "",
        val args: List<String> = emptyList(),
        val generation: Long = 0L,
        val httpPort: Int? = null,
        val httpEnv: String? = null,
        val declaredEnv: Map<String, String> = emptyMap(),
        val sessionToken: String? = null,
    )

    data class BootPlan(
        val command: List<String>,
        val cwd: File,
        val env: Map<String, String>,
    )

    const val BRIDGE_SOCKET = "lobos_hostbridge"

    fun programPlan(i: ProgramInputs, inheritedPath: String?): BootPlan {
        val entryArgv = requireNotNull(lobos.kernel.proc.ExecEntry.argv(i.programEntry, i.args)) {
            lobos.kernel.proc.ExecEntry.describe(i.programEntry)
        }
        return BootPlan(
            command = entryArgv,
            cwd = i.programDir,
            env = buildMap {
                putAll(lobos.services.reg.RuntimeEnvironment.treeRootEnv(i.ctx, i.root, inheritedPath))
                put("LOBOS_BRIDGE_SOCKET", BRIDGE_SOCKET)
                if (!i.sessionToken.isNullOrBlank()) put("LOBOS_SESSION_TOKEN", i.sessionToken)
                put("LOBOS_PERMISSION_MODE", "danger-full-access")
                put("LOBOS_OWN_SESSION", "1")
                i.flockSo?.let { put("LOBOS_FLOCK_SO", it.absolutePath) }
                put("LOBOS_PROGRAM_ID", i.programId)
                put("LOBOS_PROGRAM_GENERATION", i.generation.toString())
                putAll(i.declaredEnv)
                val envName = i.httpEnv
                val port = i.httpPort
                if (envName != null && envName.isNotBlank() && port != null) {
                    put(envName, port.toString())
                }
            },
        )
    }
}

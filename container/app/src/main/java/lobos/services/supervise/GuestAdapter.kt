    }
package lobos.services.supervise

import lobos.services.reg.RuntimeEnvironment
import java.io.File

object GuestAdapter {

    data class ProgramInputs(
        val root: RuntimeEnvironment.TreeRoot,
        val nodeBin: File,
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
        val resolved = lobos.kernel.proc.ExecEntry.argv(i.programEntry, i.args)
            ?: listOf(requireNotNull(i.nodeBin).absolutePath, i.programEntry.absolutePath) + i.args
        return BootPlan(
        command = resolved,
        cwd = i.programDir,
        env = buildMap {
            put("LOBOS_BRIDGE_SOCKET", BRIDGE_SOCKET)
            if (!i.sessionToken.isNullOrBlank()) put("LOBOS_SESSION_TOKEN", i.sessionToken)
            put("LOBOS_PERMISSION_MODE", "danger-full-access")
            put("LOBOS_OWN_SESSION", "1")
            i.flockSo?.let { put("LOBOS_FLOCK_SO", it.absolutePath) }
            put("LOBOS_PROGRAM_ID", i.programId)
            put("LOBOS_PROGRAM_GENERATION", i.generation.toString())
        put("PATH", lobos.services.reg.RuntimeEnvironment.joinPath(
            i.root.prefixBin.absolutePath,
            i.nodeBin.parentFile?.absolutePath,
            inheritedPath,
        ))
        put("LD_LIBRARY_PATH", lobos.services.reg.RuntimeEnvironment.joinPath(
            i.root.nativeLibDir,
            i.root.prefixLib.absolutePath,
        ))
            putAll(i.declaredEnv)
            val envName = i.httpEnv
            val port = i.httpPort
            if (envName != null && envName.isNotBlank() && port != null && port > 0) {
                put(envName, port.toString())
            }
        },
    )
}

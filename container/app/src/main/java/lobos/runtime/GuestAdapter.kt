package lobos.runtime

import lobos.os.RuntimeEnvironment
import java.io.File

object GuestAdapter {

    data class ProgramInputs(
        val root: RuntimeEnvironment.TreeRoot,
        val programDir: File,
        val programEntry: File,
        val uiDir: File,
        val flockNative: File,
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

    const val PROBE_PORT = 3080

    const val BRIDGE_SOCKET = "lobos_hostbridge"

    fun probePlan(root: RuntimeEnvironment.TreeRoot, script: File, inheritedPath: String?): BootPlan = BootPlan(
        command = listOf(root.nodeBin.absolutePath, script.absolutePath, "--port", PROBE_PORT.toString()),
        cwd = root.home,
        env = RuntimeEnvironment.treeRootEnv(root, inheritedPath),
    )

    fun programPlan(i: ProgramInputs, inheritedPath: String?): BootPlan = BootPlan(
        command = listOf(i.root.nodeBin.absolutePath, i.programEntry.absolutePath) + i.args,
        cwd = i.programDir,
        env = buildMap {
            putAll(RuntimeEnvironment.treeRootEnv(i.root, inheritedPath))
            put(
                "NODE_PATH",
                listOf(
                    File(i.programDir, "node_modules"),
                    NodeProvisioner.globalNodeModules(i.root.home),
                ).joinToString(File.pathSeparator) { it.absolutePath }
            )
            put("LOBOS_ANDROID", "1")
            put("LOBOS_PLATFORM", "android")
            put("LOBOS_SUPERVISOR_HOME", i.root.home.absolutePath)
            put("LOBOS_UI_DIR", i.uiDir.absolutePath)
            val socket = if (i.sessionToken.isNullOrBlank()) {
                BRIDGE_SOCKET
            } else {
                lobos.os.SessionRegistry.socketName(i.sessionToken)
            }
            put("LOBOS_BRIDGE_SOCKET", socket)
            if (!i.sessionToken.isNullOrBlank()) put("LOBOS_SESSION_TOKEN", i.sessionToken)
            put("LOBOS_PERMISSION_MODE", "danger-full-access")
            put("LOBOS_OWN_SESSION", "1")
            put("LOBOS_FLOCK_NATIVE", i.flockNative.absolutePath)
            put("LOBOS_PROGRAM_ID", i.programId)
            put("LOBOS_PROGRAM_GENERATION", i.generation.toString())
            putAll(i.declaredEnv)
            val envName = i.httpEnv
            val port = i.httpPort
            if (envName != null && envName.isNotBlank() && port != null && port > 0) {
                put(envName, port.toString())
            }
        },
    )
}

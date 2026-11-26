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

    private fun applyRuntimeBindings(env: MutableMap<String, String>, i: ProgramInputs) {
        if (i.programId.isBlank()) return
        val bindings = runCatching {
            lobos.services.reg.ProgramIndex.get(i.ctx, i.programId)?.runtimeBindings
        }.getOrNull() ?: return
        if (bindings.isEmpty()) return
        val opt = java.io.File(i.ctx.filesDir, "opt")
        val bins = ArrayList<String>()
        val libs = ArrayList<String>()
        for ((runtime, binding) in bindings) {
            val ver = binding.resolved
            if (runtime.isBlank() || ver.isBlank()) continue
            val base = java.io.File(java.io.File(opt, runtime), ver)
            val bin = java.io.File(base, "bin")
            val lib = java.io.File(base, "lib")
            if (bin.isDirectory) bins.add(bin.absolutePath)
            if (lib.isDirectory) libs.add(lib.absolutePath)
        }
        if (bins.isNotEmpty()) {
            val cur = env["PATH"] ?: ""
            env["PATH"] = (bins + listOf(cur)).filter { it.isNotBlank() }.joinToString(":")
        }
        if (libs.isNotEmpty()) {
            val cur = env["LD_LIBRARY_PATH"] ?: ""
            env["LD_LIBRARY_PATH"] = (libs + listOf(cur)).filter { it.isNotBlank() }.joinToString(":")
        }
        for ((runtime, binding) in bindings) {
            if (runtime != "python") continue
            val ver = binding.resolved
            if (ver.isBlank()) continue
            val base = java.io.File(java.io.File(opt, runtime), ver)
            if (base.isDirectory) env["PYTHONHOME"] = base.absolutePath
        }
    }

    fun programPlan(i: ProgramInputs, inheritedPath: String?): BootPlan {

        val entryArgv = requireNotNull(
            lobos.kernel.proc.ExecEntry.argv(i.programEntry, listOf(i.programEntry.absolutePath) + i.args)
        ) {
            lobos.kernel.proc.ExecEntry.describe(i.programEntry)
        }
        return BootPlan(
            command = entryArgv,
            cwd = i.programDir,
            env = buildMap {
                putAll(lobos.services.reg.RuntimeEnvironment.treeRootEnv(i.ctx, i.root, inheritedPath))
                applyRuntimeBindings(this, i)
                // Bridge removed: no LOBOS_BRIDGE_SOCKET / LOBOS_SESSION_TOKEN.
                // Program identity (not bridge credential) still flows here.
                put("LOBOS_PERMISSION_MODE", "danger-full-access")
                put("LOBOS_OWN_SESSION", "1")
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

package lobos.runtime

import lobos.os.RuntimeEnvironment
import java.io.File

object GuestAdapter {

    data class ProgramInputs(
        val root: RuntimeEnvironment.TreeRoot,
        /** node 这个件的可执行件在哪 —— 构造它的调用方按需给出 */
        val nodeBin: File,
        val programDir: File,
        val programEntry: File,
        val uiDir: File,
        /**
         * flock 这个件的可执行体在哪 —— 可空。
         *
         * flock 是 24 项清单里独立的一件，可能没装；此前这里声明成非空 File，
         * 而 InstanceHost 传的是 PieceScan.pieceFile()（File?），编译不过。
         * 没装就不给这个变量：程序自己落 flock 时靠 PATH 找 usr/lib/flock/，
         * 不该被一个指向不存在文件的变量误导。
         */
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

    fun programPlan(i: ProgramInputs, inheritedPath: String?): BootPlan = BootPlan(
        command = listOf(requireNotNull(i.nodeBin).absolutePath, i.programEntry.absolutePath) + i.args,
        cwd = i.programDir,
        env = buildMap {
            put("LOBOS_BRIDGE_SOCKET", socket)
            if (!i.sessionToken.isNullOrBlank()) put("LOBOS_SESSION_TOKEN", i.sessionToken)
            put("LOBOS_PERMISSION_MODE", "danger-full-access")
            put("LOBOS_OWN_SESSION", "1")
            i.flockSo?.let { put("LOBOS_FLOCK_SO", it.absolutePath) }
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

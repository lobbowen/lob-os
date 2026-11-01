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

    /**
     * 那**拒绝**那一格逐字要报的诊断（=> 而那是 M7-05 那一条 negative 断言逐字**要**的那原文）。
     *
     * 缺陷名（C-102）**那一格逐字把一个坏 ELF 当程序调起、=> 而而那它逐字不许** ——
     *   => 而那旧版逐字是 `val real = ExecEntry.argv(File(command[0]), command)`
     *     => 而而那 `ExecEntry.argv` 那一族逐字对**认不出**的那一档逐字返回 **null**
     *       （=> 而那 `Shape.Unknown -> null`）
     *     => 而而那下面那一行逐字 `if (real != null) real else command`
     *       => 而而那它逐字**拿回那原样的 argv**、=> 而而那**坏 ELF 逐字被当程序调起**
     *   => 而而那**后果**（04 逐字落的 M7-05 那一条 negative 断言）：
     *     「那一件逐字收到一个坏 ELF 时，=> 而而那它逐字不许把它当程序调起、
     *       => 而而那它逐字必须逐字以那一个具体的退出码退出、
     *       => 而而那它逐字必须逐字在 stderr 上留那一条诊断原文」
     *   => 而那**处置**：=> 而那**认不出**那一档逐字**不**调起、=> 而而那它逐字**报缺**
     *     （=> 而按 C-05 不猜填、=> 而**不**拿一个假的 argv 顶上去）
     *     => 而而那**退出码**逐字是那一个具体的数、=> 而而那那**诊断原文**逐字是那一条
     */
    const val ENOEXEC_EXIT_CODE = 126
    const val ENOEXEC_DIAGNOSTIC = "入口不是可执行的形态（既不是 ELF 也不是 #!）—— 拒绝调起，不当程序跑"

    /** 那**拒绝**那一格逐字要报的那一条（=> 而而那它逐字**是**那原文、=> 而而那它逐字**可机读**）。 */
    fun enoexecDiagnostic(entry: File): String =
        ENOEXEC_DIAGNOSTIC + "：" + entry.absolutePath +
            "（" + ExecEntry.describe(entry) + "）"

    /**
     * 那**认不出**那一档逐字**报缺**（=> 而而那它逐字**不**调起）。
     *   => 而而那它逐字**答**的是「那一件逐字没有可调起的入口」那一族
     *   => 而而那它逐字**不**是「那一件没有入口」（=> 而那入口那一格逐字**有**、=> 而那**形态**认不出）
     */
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
        //  => 而那**认不出**那一档逐字**报缺**、=> 而而那它逐字**不**调起（=> 而那 M7-05 那一族）
        //    => 而那旧版逐字是 `if (real != null) real else command`
        //      => 而而那它逐字**拿回那原样的 argv**、=> 而而那**坏 ELF 逐字被当程序调起**
        //    => 而而那**判据**逐字是「那形态**认不出**」=> 而而那它逐字**只**是那**认不出**那一族
        //      （=> 而那 `Shape.Unknown` 逐字是「既不是 ELF 也没有 #!」、=> 而而那它逐字**正是**那坏 ELF 那一族）
        //    => 而而那 `Pe` 那一族逐字**也**是那**认不出**（=> 而那它逐字**该**同样被拒、
        //      => 而那按 C-105 逐字**不**另分一档）
        if (real == null) {
            val diag = enoexecDiagnostic(first)
            //  => 而而那那**诊断原文**逐字**进**那 stderr（=> 而那是 M7-05 那一条逐字**要**的那一处）
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
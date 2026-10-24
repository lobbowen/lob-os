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
        // 缺陷名（C-102）**那一组用户声明的参数被丢掉了第一个**。
        //
        // 真机读数（07，2026-10-24）：给 bash 设的 args 是
        //   [/data/user/0/lobos.os/files/tmp/hold.sh]
        // 而那个活进程的 argv 逐字只有两项：
        //   /system/bin/linker64 /data/user/0/lobos.os/files/opt/bash/5.2.15/bin/bash
        // **=> 而那一份 args 不在里面。**
        //
        // 而成因：ExecEntry.argv 的 args 形参在两支里都是 `+ args.drop(1)`，
        // **而那一句的语义是「丢掉 argv[0] 那个程序名」**
        //   => 而 ProcessSupervisor:33 传的是 command（整条 argv，argv[0] 是程序名）=> 对
        //   => 而 InstalledRuntime:31 传的是 listOf(bin.absolutePath) + args => 对
        //   => 而 PtySession:294 / :439 传的是 listOf(checked.absolutePath) => 对
        //   => 而**这里传的是 i.args（用户声明的那些，里面没有程序名）=> 而 drop(1) 丢的是用户第一个参数**
        //
        // => 而后果：那一件拿到的参数**全部**不对（=> 而第一个直接没了），
        //   而像 rg 那样「没有 pattern 就退出」那一族因此被触发（=> 而 07 核到 rg 的 exit=2，
        //   而 stderr 逐字是「rg: ripgrep requires at least one pattern to execute a search」），
        // 而像 bash 那样「没有参数就立刻 EOF 退出」那一族也一样（=> 而 bash 与 jq 的 exit=0）。
        //
        // => 而处置：这里把 args 补成 argv 的形状（=> 而程序名在前），
        //   而那正是 ExecEntry.argv 那个形参要的形状。
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

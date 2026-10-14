package lobos.services.supervise

import android.content.Context
import lobos.services.log.RuntimeDiagnostics
import lobos.services.supply.ShellEntry

class SvcDecl(
    val id: String,
    val after: List<String>,
    val act: (Context) -> Unit,
)

object ServiceRegistry {

    private fun decl(id: String, after: List<String>, f: (Context) -> Unit): SvcDecl =
        SvcDecl(id, after, f)

    fun decls(): List<SvcDecl> {
        val out = ArrayList<SvcDecl>()
        out.add(
            decl("prefix", ArrayList<String>()) { ctx ->
                runCatching { lobos.services.app.PreinstalledPieces.installAll(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "prefix", false, "预装件装置过程中出错",
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                    }
            }
        )
        out.add(
            decl("view", listOf("prefix")) { ctx ->
                runCatching { lobos.services.reg.RuntimeEnvironment.ensure(ctx) }
                    .onSuccess { snap ->
                        val linked = lobos.kernel.compat.Compat.linkInto(ctx)
                        val root = lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx)
                        val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(ctx, root, null)
                        var wrote = 0
                        for ((k, v) in view) {
                            if (lobos.kernel.compat.LobosEnv.set(k, v)) wrote++
                        }
                        RuntimeDiagnostics.append(
                            ctx, "sysview", wrote > 0,
                            "系统视图写入宿主进程环境 " + wrote + "/" + view.size + " 项；内核垫片入系统库 " + linked + " 个",
                            view.entries.joinToString(" | ") { it.key + "=" + it.value.take(70) },
                        )
                    }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "view", false, "算系统视图失败",
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                    }
            }
        )
        out.add(
            decl("pty", listOf("view")) { ctx ->
                runCatching { lobos.kernel.ipc.PtySession.activeSessions(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "service", false, "PTY 宿主就位失败",
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                    }
            }
        )
        out.add(
            decl("termselftest", listOf("view")) { ctx ->
                val sh = lobos.services.supply.ShellEntry.require(ctx)
                val r = runCatching {
                    lobos.kernel.ipc.PtySession.runToCompletion(
                        ctx, sh,
                        listOf("-c", "echo __LD__=\$LD_LIBRARY_PATH; echo __PATH__=\$PATH; ls /data/user/0/lobos.os/files/usr/bin"),
                        timeoutMs = 8_000L,
                    )
                }.getOrNull()
                val outTxt = r?.output.orEmpty()
                val ld = outTxt.lineSequence().firstOrNull { it.startsWith("__LD__=") }?.removePrefix("__LD__=").orEmpty()
                val pathV = outTxt.lineSequence().firstOrNull { it.startsWith("__PATH__=") }?.removePrefix("__PATH__=").orEmpty()
                val libOk = ld.contains("usr/lib")
                val binOk = pathV.contains("usr/bin")
                RuntimeDiagnostics.append(
                    ctx, "terminal", libOk && binOk,
                    "终端自检（Lob OS 自己的 PTY 跑一条命令）",
                    "LD_LIBRARY_PATH 含 usr/lib：$libOk" +
                        "\nPATH 含 usr/bin：$binOk" +
                        "\n输出=" + outTxt.replace('\n', '|').take(300) +
                        (if (r?.error != null) "\n错误=" + r.error else "")
                )
            }
        )
        out.add(
            decl("sysdev", listOf("view")) { ctx ->
                runCatching { lobos.kernel.sysdev.SysDev.render(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "sysdev", false, "渲染 files/dev 失败",
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                    }
            }
        )
        return out
    }

    fun ready(ctx: Context, alive: Set<String>): List<SvcDecl> {
        val out = ArrayList<SvcDecl>()
        for (d in decls()) {
            var ok = true
            for (a in d.after) {
                if (!alive.contains(a)) { ok = false }
            }
            if (ok) out.add(d)
        }
        return out
    }

    fun state(ctx: Context, alive: Set<String>): String {
        if (decls().isEmpty()) return "（无内置服务）"
        val sb = StringBuilder()
        for (d in decls()) {
            if (sb.length > 0) sb.append("; ")
            sb.append(d.id)
            if (alive.contains(d.id)) {
                sb.append("=起")
            } else {
                sb.append("=等")
                if (d.after.isNotEmpty()) sb.append("(").append(d.after.joinToString(",")).append(")")
            }
        }
        return sb.toString()
    }
}

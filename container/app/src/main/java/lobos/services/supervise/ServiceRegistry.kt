package lobos.services.supervise

import android.content.Context
import lobos.services.log.Journal
import lobos.services.log.RuntimeDiagnostics

class SvcDecl(
    val id: String,
    val after: List<String>,
    val act: (Context) -> Unit,
)

object ServiceRegistry {

    private fun noop() { }

    private fun actOf(id: String, f: (Context) -> Unit): (Context) -> Unit = { ctx ->
        Journal.note(ctx, "svc-probe", null, id + " enter")
        f(ctx)
        Journal.note(ctx, "svc-probe", null, id + " done")
    }

    private fun decl(id: String, after: List<String>, f: (Context) -> Unit): SvcDecl {
        val a: (Context) -> Unit = actOf(id, f)
        return SvcDecl(id, after, a)
    }

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

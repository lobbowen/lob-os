package lobos.services.supervise

import android.content.Context
import lobos.services.log.RuntimeDiagnostics

object ServiceRegistry {

    class Decl(
        val id: String,
        val after: List<String>,
        val start: (Context) -> () -> Unit,
    )


    fun decls(): List<Decl> = listOf(
        svc("prefix", emptyList()) { ctx ->
            runCatching { lobos.services.app.PreinstalledPieces.installAll(ctx) }
                .onFailure { ex ->
                    RuntimeDiagnostics.append(
                        ctx, "prefix", false, "预装件装置过程中出错",
                        ex.javaClass.name + ": " + (ex.message ?: ""),
                    )
                }
        },
        svc("view", listOf("prefix")) { ctx ->
            runCatching { lobos.services.reg.RuntimeEnvironment.ensure(ctx) }
                .onFailure { ex ->
                    RuntimeDiagnostics.append(
                        ctx, "view", false, "算系统视图失败",
                        ex.javaClass.name + ": " + (ex.message ?: ""),
                    )
                }
        },
        svc("pty", listOf("view")) { ctx ->
            runCatching { lobos.kernel.ipc.PtySession.activeSessions(ctx) }
                .onFailure { ex ->
                    RuntimeDiagnostics.append(
                        ctx, "service", false, "PTY 宿主就位失败",
                        ex.javaClass.name + ": " + (ex.message ?: ""),
                    )
                }
        },
        svc("sysdev", listOf("view")) { ctx ->
            runCatching { lobos.kernel.sysdev.SysDev.render(ctx) }
                .onFailure { ex ->
                    RuntimeDiagnostics.append(
                        ctx, "sysdev", false, "渲染 files/dev 失败",
                        ex.javaClass.name + ": " + (ex.message ?: ""),
                    )
                }
        },
    )

    private fun svc(
        id: String,
        after: List<String>,
        stop: () -> Unit = {},
        body: (Context) -> Unit,
    ): Decl = Decl(id, after, stop) { ctx -> { body(ctx); stop } }

    fun ready(ctx: Context, alive: Set<String>): List<Decl> =
        decls().filter { d -> d.after.all { it in alive } }

    fun state(ctx: Context, alive: Set<String>): String =
        decls().joinToString("; ") { d ->
            d.id + (if (d.id in alive) "=起" else "=等" +
                (if (d.after.isEmpty()) "" else "(" + d.after.joinToString(",") + ")"))
        }.ifBlank { "（无内置服务）" }
}

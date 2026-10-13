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
        Decl(
            id = "prefix",
            after = emptyList(),
            start = { ctx ->
                runCatching { lobos.services.app.PreinstalledPieces.installAll(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "prefix", false, "预装件装置过程中出错",
                            e::class.java.simpleName + ": " + (e.message ?: ""),
                        )
                    }
                { }
            },
        ),
        Decl(
            id = "view",
            after = listOf("prefix"),
            start = { ctx ->
                runCatching { lobos.services.reg.RuntimeEnvironment.ensure(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "view", false, "算系统视图失败",
                            e::class.java.simpleName + ": " + (e.message ?: ""),
                        )
                    }
                { }
            },
        ),
        Decl(
            id = "pty",
            after = listOf("view"),
            start = { ctx ->
                runCatching { lobos.kernel.ipc.PtySession.activeSessions(ctx) }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "service", false, "PTY 宿主就位失败",
                            e::class.java.simpleName + ": " + (e.message ?: ""),
                        )
                    }
                { }
            },
        ),
        Decl(
            id = "sysdev",
            after = listOf("view"),
            start = { ctx ->
                runCatching { lobos.kernel.sysdev.SysDev.render(ctx) }
                    .onFailure { er ->
                        RuntimeDiagnostics.append(
                            ctx, "sysdev", false, "渲染 files/dev 失败",
                            er.javaClass.name + ": " + (er.message ?: ""),
                        )
                    }
                { }
            },
        ),
    )

    fun ready(ctx: Context, alive: Set<String>): List<Decl> =
        decls().filter { d -> d.after.all { it in alive } }

    fun state(ctx: Context, alive: Set<String>): String =
        decls().joinToString("; ") { d ->
            d.id + (if (d.id in alive) "=起" else "=等" +
                (if (d.after.isEmpty()) "" else "(" + d.after.joinToString(",") + ")"))
        }.ifBlank { "（无内置服务）" }

    fun note(ctx: Context, text: String) {
        RuntimeDiagnostics.append(ctx, "service", null, "内置服务", text)
    }
}

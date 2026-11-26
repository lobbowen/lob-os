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

    fun decls(ctx: Context): List<SvcDecl> {
        val out = ArrayList<SvcDecl>()

        val pieceIds = runCatching { lobos.services.app.PreinstalledPieces.pieceIds(ctx) }
            .getOrDefault(emptyList())
        for (pid in pieceIds) {
            out.add(
                decl("piece-" + pid, ArrayList<String>()) { c ->
                    runCatching { lobos.services.app.PreinstalledPieces.installOneById(c, pid) }
                        .onFailure { e ->
                            RuntimeDiagnostics.append(
                                c, "prefix", false, "预装件装置过程中出错 " + pid,
                                e.javaClass.name + ": " + (e.message ?: ""),
                            )
                        }
                }
            )
        }

        val processShaped = pieceIds.filter { pid ->
            runCatching {
                lobos.services.supply.PieceScan.roleFromDisk(ctx, pid) !=
                    lobos.services.supply.PieceScan.LIBRARY
            }.getOrDefault(true)
        }
        out.add(
            decl("prefix", processShaped.map { "piece-" + it }) { ctx ->
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
                val ready = lobos.system.UbuntuSystem.isReady(ctx)
                RuntimeDiagnostics.append(
                    ctx, "sysview", ready,
                    "Ubuntu 系统盘 " + (if (ready) "就绪" else "未就绪"),
                    "root=" + lobos.system.UbuntuSystem.root(ctx).absolutePath,
                )
            }
        )
        // V2: no eager PTY host here. The host process carries fastroot env
        // (FR_ROOT/FR_SHIM/...) and is a singleton in PtySession; pre-starting
        // it without that env would freeze a V1 host that the terminal's
        // fastroot session then cannot join. TerminalSession creates it on
        // demand with the right environment.
        out.add(
            decl("sysdev", listOf("view")) { ctx ->

                runCatching { lobos.kernel.sysdev.SysDev.render() }
                    .onFailure { e ->
                        RuntimeDiagnostics.append(
                            ctx, "sysdev", false, "渲染 files/dev 失败",
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                    }
                    .getOrNull()
                    ?: throw IllegalStateException(
                        "渲染 files/dev 失败（ render**答不出**那目录、" +
                            "一族**要**重起）"
                    )
            }
        )
        return out
    }

    fun ready(ctx: Context, alive: Set<String>): List<SvcDecl> {
        val out = ArrayList<SvcDecl>()
        for (d in decls(ctx)) {
            var ok = true
            for (a in d.after) {
                if (!alive.contains(a)) { ok = false }
            }
            if (ok) out.add(d)
        }
        return out
    }

    fun state(ctx: Context, alive: Set<String>): String {
        if (decls(ctx).isEmpty()) return "（无内置服务）"
        val sb = StringBuilder()
        for (d in decls(ctx)) {
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

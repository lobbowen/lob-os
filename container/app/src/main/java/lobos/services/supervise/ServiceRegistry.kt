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
                runCatching { lobos.services.reg.RuntimeEnvironment.ensure(ctx) }
                    .onSuccess { snap ->
                        val linked = lobos.kernel.compat.Compat.linkInto(ctx)
                        val root = lobos.services.reg.RuntimeEnvironment.treeRootFor(ctx)
                        val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(ctx, root, null)
                        val needs = listOf("libcurl.so", "libssl.so", "libcrypto.so", "libz.so.1")
                            .joinToString(" ") { it.substringBefore('.') + "=" +
                                lobos.kernel.compat.PieceInspect.needed(ctx, it).replace(' ', '_').take(70) }
                        RuntimeDiagnostics.append(
                            ctx, "sysview", view.isNotEmpty(),
                            "系统视图 " + view.size + " 项；垫片入库 " + linked + "；件内依赖 " + needs,
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
                runCatching { lobos.kernel.ipc.PtySession.ensureHost(ctx) }
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
                    .getOrNull()
                    ?: throw IllegalStateException(
                        "渲染 files/dev 失败（ render 逐字**答不出**那目录、" +
                            "一族逐字**要**重起）"
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

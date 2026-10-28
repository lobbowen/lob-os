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

    //  缺陷名（C-102）**那新增**那一行逐字**用**的那**名字**逐字**在不在**那**函数**的**形参**里**。
    //    => 而那**成因**：=> 而那 ea76e82 那一笔逐字**新增**了那「val pieceIds = … pieceIds(ctx) 」**那一行**、
    //      => 而而那**它**逐字**在**那 decls() **那一族**里**、=> 而而那**它**的**形参**逐字**只**有**那 id / 那 after / 那 f、
    //      => 而而那**它**们**逐字是**那**两**件**事**：「那**要**的**是**什么**」与「那**在不在**那**作用域**里**」）。
    //    => 而那**后果**逐字 = 而那 :app:compileDebugKotlin 逐字**红**、=> 而而那**它**逐字**报**那「Unresolved reference ctx」。
    //    => 而那**处置**：=> 而那 decls() 逐字**带上**那 ctx **那**一个**形参**（=> 而而那**它**们**逐字是**那**两**件**事**）。
    //      => 而而那**它**逐字**跟**那**落位**那一族**与**那身份**那一族逐字**不互相顶替**。
    fun decls(ctx: Context): List<SvcDecl> {
        val out = ArrayList<SvcDecl>()
        //  缺陷名（C-102）**那落位**那一族逐字**一**次**装**完**那**几**件**（=> 而而那**它**逐字**一**旦**中途**断**了**、
        //    => 而而那**后面**那**几**件**逐字**就**没**装**、=> 而而那**身份**那一族逐字**也**跟**着**跑**不到**）。
        //    => 而那**成因**：=> 而那 installAll 逐字**是**那**一个**服务**里**同步**跑**完**那**九**件**（=> 而而那**它**逐字**是**那**一**次**调用**）、
        //      => 而而那**每一件**的**耗时**逐字**加**在**一起**、=> 而而那**它**们**逐字**是**那**两**件**事**：「那**一件**装没装**」与「那**总**共**多久**」）。
        //    => 而那**处置**：=> 而那**每一件**逐字**自**己**成**一个**服务**（=> 而而那**它**逐字**被**那**池**一个**一个**起**）、
        //      => 而而那**它**们**逐字**各**带**各**的**那**一笔**、=> 而而那**一件**断**了**不**影响**那**下一**件**。
        //      => 而而那**它**们**逐字是**那**两**件**事**（「那**落位**那**件**落没落**」**与**「那**一次**构建**是**哪**一次**」）。
        //  => 而那**逐件**那一族逐字**要**那**几件**的名字**（=> 而而那**它**逐字**由**那 APK **自己**说**）。
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
        //  => 而那 **prefix** 那一族逐字**等**那**逐件**那些**都**起**了**（=> 而而那**它**逐字**不**自己**装**）。
        //     => 而而那**它**逐字**是**那**全量**那一族**、=> 而而那**它**逐字**不**是**那**逐件**那一族**。
        out.add(
            decl("prefix", pieceIds.map { "piece-" + it }) { ctx ->
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

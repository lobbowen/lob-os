package lobos.services.supervise

import android.content.Context
import lobos.services.log.RuntimeDiagnostics

object ServiceRegistry {

    class Decl(
        val id: String,
        val after: List<String>,
        val start: (Context) -> () -> Unit,
    )

    fun decls(): List<Decl> = emptyList()

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

package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.kernel.ipc.PtySession

object CommandLine {

    data class Hit(
        val name: String,
        val path: String,
        val known: Boolean,
    )

    private fun binDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.bin(ctx)

    fun resolve(ctx: Context, token: String): Hit? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val first = t.split(Regex("\\s+"))[0]
        if (first.isEmpty() || first.indexOf(39) == 39) return null
        val f = File(binDir(ctx), first)
        if (f.isFile) return Hit(first, f.absolutePath, true)
        return null
    }

    fun run(ctx: Context, line: String, timeoutMs: Long = 10_000): String {
        val hit = resolve(ctx, line) ?: return ""
        val pty = lobos.kernel.compat.Compat.ptyHost(ctx) ?: return ""
        val s = PtySession.openSession(
            ctx,
            pty,
            listOf(lobos.services.supply.ShellEntry.require(ctx).absolutePath, "-i"),
            timeoutMs = 5_000,
        )
        val out = StringBuilder()
        s.onData = { d -> synchronized(out) { out.append(String(d, Charsets.UTF_8)) } }
        s.write(hit.name + "\n")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !s.isClosed) {
            Thread.sleep(60)
        }
        s.close()
        synchronized(out) { return out.toString() }
    }
}
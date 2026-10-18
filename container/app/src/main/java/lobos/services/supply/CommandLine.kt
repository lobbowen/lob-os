package lobos.services.supply

import android.content.Context
import java.io.File

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
        if (first.isEmpty() || first.contains('\'')) return null
        val f = File(binDir(ctx), first)
        if (f.isFile) return Hit(first, f.absolutePath, true)
        return null
    }

    fun run(ctx: Context, line: String, timeoutMs: Long = 10_000): lobos.kernel.ipc.LocalExec.Outcome {
        val hit = resolve(ctx, line) ?: return lobos.kernel.ipc.LocalExec.Outcome(
            false, -1, "", "", lobos.kernel.ipc.LocalExec.Via.PLAIN, "usr/bin 下没有这个名字",
        )
        return lobos.kernel.ipc.LocalExec.run(
            ctx,
            listOf(hit.path, "--version"),
            emptyMap(),
            null,
            timeoutMs,
            true,
        )
    }
}
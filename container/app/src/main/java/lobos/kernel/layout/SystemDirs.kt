package lobos.kernel.layout

import android.content.Context
import java.io.File

object SystemDirs {

    const val REL_OPT = "opt"

    fun etc(ctx: Context): File = File(ctx.filesDir, "etc")

    fun usr(ctx: Context): File = File(ctx.filesDir, "usr")
    fun bin(ctx: Context): File = File(usr(ctx), "bin")
    fun lib(ctx: Context): File = File(usr(ctx), "lib")
    fun include(ctx: Context): File = File(usr(ctx), "include")

    fun vardir(ctx: Context): File = File(ctx.filesDir, "var")
    fun libvar(ctx: Context): File = File(vardir(ctx), "lib")
    fun log(ctx: Context): File = File(vardir(ctx), "log")

    fun run(ctx: Context): File = File(ctx.filesDir, "run")

    fun opt(ctx: Context): File = File(ctx.filesDir, REL_OPT)

    fun pieceDir(ctx: Context, id: String): File = File(lib(ctx), id)

    fun pieceDir(ctx: Context, id: String, version: String): File =
        File(pieceDir(ctx, id), version)

    fun clearRun(ctx: Context): Int {
        val root = run(ctx)
        if (!root.isDirectory) return 0
        val kids = root.list() ?: return 0
        var n = 0
        for (k in kids) {
            val f = File(root, k)
            val ok = runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                .getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    fun ensureAll(ctx: Context) {
        for (d in listOf(
            etc(ctx), usr(ctx), bin(ctx), lib(ctx), include(ctx),
            vardir(ctx), libvar(ctx), log(ctx), run(ctx), opt(ctx),
        )) {
            runCatching { d.mkdirs() }
        }
    }
}
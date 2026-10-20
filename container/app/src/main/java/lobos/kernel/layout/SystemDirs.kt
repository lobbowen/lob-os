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

    fun pieceDir(ctx: Context, id: String): File = File(opt(ctx), id)

    fun pieceDir(ctx: Context, id: String, version: String): File =
        File(pieceDir(ctx, id), version)

    /** 登记那一格（stateDir）的**唯一形态**：opt/<id>（相对 filesDir）。
     *
     *  族一（一个件在磁盘上落在哪）：这一格有四个写侧，今天它们各拼同一串字面量
     *  （REL_OPT + "/" + id），而读侧只有一种解析方式（File(ctx.filesDir, e.stateDir)）——
     *  **一旦有一处拼错，跟着读的 20 处全部跟着错**（D 组的正确性依赖 C 组拼对了）。
     *  => 所以这一格只有一个生成点，就是这里。
     *  而版本**不进这一格**：版本由目录名承担（落位那一支的 dest = File(dir, safeVer) 自己拼）。
     */
    fun relPieceDir(id: String): String = REL_OPT + "/" + id

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

    fun clearRunVolatile(ctx: Context): Int {
        val root = run(ctx)
        if (!root.isDirectory) return 0
        var n = 0
        for (k in listOf("tmp", "staging", "install.lock")) {
            val f = File(root, k)
            if (runCatching { f.deleteRecursively() }.getOrDefault(false)) n++
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
package lobos.os

import android.content.Context
import java.io.File

/**
 * 系统目录 —— 照抄 FHS 3.0 的命名（hier(7)）。
 *
 * 我们在应用私有目录 `filesDir` 下造这些面，与 Linux 的 `/` 同构：
 *
 *   etc/      本机配置（channel.json、installed.json、.<件>.ok）
 *   usr/      共享只读数据
 *     bin/      全局入口，在 PATH 里
 *     lib/      目标库 + 库件落位 `lib/<id>/<版本>/`
 *     include/  C/C++ 头文件
 *   var/
 *     lib/      持久状态：装了什么、用了哪个版本、探测结果
 *     log/      会增长的日志与事件
 *   run/      运行时：现在什么状态，重启即丢
 *   opt/      装进来的程序
 *
 * 与 Linux 唯一的实质差别：Linux 的 `/run` 是 tmpfs（内核保证重启即失），
 * 我们在普通文件系统上，所以**「重启即丢」由 [clearRun] 在启动时保证**。
 */
object SystemDirs {

    fun etc(ctx: Context): File = File(ctx.filesDir, "etc")

    fun usr(ctx: Context): File = File(ctx.filesDir, "usr")
    fun bin(ctx: Context): File = File(usr(ctx), "bin")
    fun lib(ctx: Context): File = File(usr(ctx), "lib")
    fun include(ctx: Context): File = File(usr(ctx), "include")

    fun var(ctx: Context): File = File(ctx.filesDir, "var")
    fun libvar(ctx: Context): File = File(var(ctx), "lib")
    fun log(ctx: Context): File = File(var(ctx), "log")

    fun run(ctx: Context): File = File(ctx.filesDir, "run")

    fun opt(ctx: Context): File = File(ctx.filesDir, "opt")

    /** 库的落位：`lib/<id>/`（件的内含物在 `<版本>/` 下面） */
    fun pieceDir(ctx: Context, id: String): File = File(lib(ctx), id)

    /**
     * 清空 `/run` —— Linux 靠 tmpfs 自动清，我们没有，所以启动时自己清。
     *
     * 只清 `/run` 自己那棵树，不碰别的目录。删不掉的文件跳过（可能有进程正持有）。
     * 返回清掉的项数，供诊断用。
     */
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

    /** 启动时该建的面 —— 缺了就建，顺序按依赖（父在前） */
    fun ensureAll(ctx: Context) {
        for (d in listOf(
            etc(ctx), usr(ctx), bin(ctx), lib(ctx), include(ctx),
            var(ctx), libvar(ctx), log(ctx), run(ctx), opt(ctx),
        )) {
            runCatching { d.mkdirs() }
        }
    }
}
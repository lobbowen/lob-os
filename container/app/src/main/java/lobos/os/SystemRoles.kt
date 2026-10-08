package lobos.os

import android.content.Context
import java.io.File

/**
 * 角色 —— 内核用它回答「我要一个能执行命令的东西」。
 *
 * **内核不预置任何一件的清单**（照抄 ldconfig：只扫目录）。角色由**落位形状**决定：
 *
 *   usr/lib/<id>/<版本>/bin/<名字>    命令（\$PREFIX/bin 在 PATH 里）
 *   usr/lib/<id>/<版本>/lib<名字>.so  库（usr/lib 已在库搜索路径里）
 *   usr/lib/<id>/include/头文件集
 *
 * 而「一个二进制提供多个命令」（multi-command）推不出来 —— **件自己在说明里声明**
 * （见 component-meta.json），这里读它。
 *
 * 说明里没有的字段（如 provides / applets / installName）一律不读 ——
 * Debian 的 control 里那些字段是别的意思（Provides 是虚拟包名），不是「我能干什么」。
 */
object SystemRoles {
    const val SHELL = "shell"
    const val MULTI_COMMAND = "multi-command"
    const val HEADERS = "headers"
    const val LIBRARY = "library"
    const val EXEC = "exec"

    /** 某个 id 现在落位在哪 —— 扫落位找，不查表 */
    fun pieceDir(ctx: Context, id: String): File? =
        PieceScan.scan(ctx).firstOrNull { it.id == id }?.dir

    /** 某个 id 的入口文件（命令）或库文件 */
    fun pieceFile(ctx: Context, id: String): File? {
        for (f in PieceScan.scan(ctx)) {
            if (f.id == id) return File(f.dir, f.entry)
        }
        return null
    }

    /** 某个 id 落位那一件的说明（件自带） */
    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? =
        PieceScan.scan(ctx).firstOrNull { it.id == id }?.meta

    /**
     * 命令解释器 —— **问它自己是什么形态**，不按名字找。
     *
     * 形态是落位形状决定的（有 bin/ 是命令· 只有 .so 是库），
     * 见 [PieceScan.roleOf]。件里没有任何一个叫「shell」的东西。
     */
    fun shellBin(ctx: Context): File? = firstEntry(ctx)

    /**
     * 一个二进制提供多个命令的那件。
     *
     * 判据是「它的 bin/ 下不止一个文件」—— applet 软链与本体同目录落位
     * （busybox 官方机制：make install 自己建好那些软链），
     * 所以「不止一个入口」就是 multi-command 的形状，不声明也不查表。
     */
    fun multiCommandBin(ctx: Context): File? {
        for (f in PieceScan.scan(ctx)) {
            if (f.role != EXEC) continue
            val bin = File(f.dir, "bin")
            val n = bin.listFiles()?.count { it.isFile || it.isSymbolicLink } ?: 0
            if (n > 1) return File(f.dir, f.entry).takeIf { it.isFile }
        }
        return null
    }

    /** 第一个命令形态的入口（落位形状里 bin/ 的那个） */
    private fun firstEntry(ctx: Context): File? {
        for (f in PieceScan.scan(ctx)) {
            if (f.role == EXEC) return File(f.dir, f.entry).takeIf { it.isFile }
        }
        return null
    }
}

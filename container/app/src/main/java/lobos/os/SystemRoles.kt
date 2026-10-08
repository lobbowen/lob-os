package lobos.os

import android.content.Context
import java.io.File

/**
 * 角色 —— 内核用它回答「我要一个能执行命令的东西」，而不是代码里写死 `shellBin()`。
 *
 * **内核不预置任何一件的清单**（照抄 ldconfig：只扫目录；deb-control(5)：
 * 每个包自带说明）。角色是**落位形状**决定的：
 *
 *   usr/lib/<id>/<版本>/bin/<名字>    命令
 *   usr/lib/<id>/<版本>/lib<名字>.so  库
 *   usr/lib/<id>/include/头文件集
 *
 * 而「一个二进制提供多个命令」（multi-command）推不出来 —— **件自己在
 * component-meta.json 里声明**，这里读它。
 */
object SystemRoles {
    const val SHELL = "shell"
    const val MULTI_COMMAND = "multi-command"
    const val HEADERS = "headers"
    const val LIBRARY = "library"
    const val EXEC = "exec"

    /**
     * 这个件是可执行入口还是库 —— **只看落位形状**，不看任何字段声明。
     * 有 bin/ 是命令，只有 .so 是库。
     */
    fun isEntry(meta: org.json.JSONObject): Boolean =
        meta.optString("form", "") == EXEC ||
            meta.optString("form", "") == SHELL ||
            meta.optString("form", "") == MULTI_COMMAND

    /** 某个 id 现在落位在哪 —— 扫落位找，不查表 */
    fun pieceDir(ctx: Context, id: String): File? {
        for (f in PieceScan.scan(ctx)) {
            if (f.id == id) return f.dir
        }
        return null
    }

    /** 某个 id 的入口文件（命令）或库文件 —— 扫落位找 */
    fun pieceFile(ctx: Context, id: String): File? {
        for (f in PieceScan.scan(ctx)) {
            if (f.id == id) return File(f.dir, f.entry)
        }
        return null
    }

    /** 某个 id 落位的那一件的说明 —— 扫落位读它自带的 */
    fun pieceMeta(ctx: Context, id: String): org.json.JSONObject? =
        PieceScan.scan(ctx).firstOrNull { it.id == id }?.meta

    /** 命令解释器（SHELL 角色）—— 由件自己声明，不按名字找 */
    fun shellBin(ctx: Context): File? {
        val f = PieceScan.scan(ctx).firstOrNull {
            it.meta?.optString("form", "") == SHELL
        } ?: return null
        return File(f.dir, f.entry).takeIf { it.isFile }
    }

    /** 一个二进制提供多个命令的那件 */
    fun multiCommandBin(ctx: Context): File? {
        val f = PieceScan.scan(ctx).firstOrNull {
            it.meta?.optString("form", "") == MULTI_COMMAND
        } ?: return null
        return File(f.dir, f.entry).takeIf { it.isFile }
    }

    /** 这个系统对外提供哪些具名能力 —— 从各件自带的说明里汇总 */
    fun capabilities(ctx: Context): List<String> =
        PieceScan.scan(ctx).flatMap { it.provides }.distinct().sorted()
}

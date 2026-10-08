package lobos.os

import android.content.Context
import java.io.File

/**
 * 角色 —— 内核用它回答「我要一个能执行命令的东西」，而不是代码里写死 shellBin()。
 *
 * 此前内核里有 shellBin() / multiCommandBin() / SYSROOT_ID / BUSYBOX_APPLETS 这些常量表 ——
 * 换一件命令解释器就得改内核代码。现在这些是数据（每件声明自己的 role），
 * 内核只提供「按角色找」这一个机制。
 *
 * SHELL 命令解释器 · MULTI_COMMAND 一个二进制提供多个命令 ·
 * HEADERS 头文件集 · LIBRARY 共享库 · EXEC 普通命令
 */
object SystemRoles {
    const val SHELL = "shell"
    const val MULTI_COMMAND = "multi-command"
    const val HEADERS = "headers"
    const val LIBRARY = "library"
    const val EXEC = "exec"

    private fun all(ctx: Context) = lobos.pieces.PieceRegistry.ALL

    private fun byRole(ctx: Context, role: String) = all(ctx).filter { it.role == role }

    fun shellBin(ctx: Context): File? {
        val e = byRole(ctx, SHELL).firstOrNull() ?: return null
        return File(SystemDirs.bin(ctx), e.installedAs).takeIf { it.isFile }
    }

    fun multiCommandBin(ctx: Context): File? {
        val e = byRole(ctx, MULTI_COMMAND).firstOrNull() ?: return null
        return File(SystemDirs.bin(ctx), e.installedAs).takeIf { it.isFile }
    }

    fun headersPieceDir(ctx: Context): File? {
        byRole(ctx, HEADERS).firstOrNull()?.let {
            return SystemDirs.pieceDir(ctx, it.id).takeIf { d -> d.isDirectory }
        }
        for (e in ProgramIndex.all(ctx)) {
            if (e.stateDir.isBlank()) continue
            val dir = File(e.stateDir)
            if (File(dir, "include").isDirectory) return dir
        }
        return null
    }

    fun executableNames(ctx: Context): List<String> =
        all(ctx)
            .filter { it.role == SHELL || it.role == EXEC || it.role == MULTI_COMMAND }
            .map { it.installedAs }
            .sorted()

    fun libraryNames(ctx: Context): List<String> =
        byRole(ctx, LIBRARY).map { it.libName }.sorted()

    fun capabilities(ctx: Context): List<String> =
        all(ctx).flatMap { it.provides }.distinct().sorted()

    /** 这个件是可执行入口（落 `$PREFIX/bin`）还是库（落 `$PREFIX/lib`）—— 判据是 role，不是名字 */
    fun isEntry(e: lobos.pieces.Piece): Boolean =
        e.role == SHELL || e.role == EXEC || e.role == MULTI_COMMAND

    /**
     * 这个 id 是不是一件（系统文件），它落在哪 —— 不是则返回 null。
     *
     * 「是不是件」由它自己的 role 决定（注册表声明），不靠包外清单的 kind字符串：
     * 换一份清单不该改变「它落哪」。
     */
    /**
     * 某个件的**可执行件/库文件本身**在落位的哪 ——
     * 落位在 usr/lib/<id>/（库）或 usr/bin/（入口）下，按件自己声明的 role 与名字找。
     *
     * 内核要用某件时问这里，不要在数据结构里存「那个件的路径」——
     * 存一份就等于把「某一件」写进了机制。
     */
    fun pieceFile(ctx: Context, id: String): File? {
        val e = lobos.pieces.PieceRegistry.of(id) ?: return null
        val name = e.landingName
        val f = if (isEntry(e)) File(SystemDirs.bin(ctx), name)
                else File(SystemDirs.pieceDir(ctx, id), name)
        return f.takeIf { it.isFile }
    }

    fun pieceDirFor(ctx: Context, id: String): File? {
        val e = lobos.pieces.PieceRegistry.of(id) ?: return null
        val dir = if (isEntry(e)) SystemDirs.bin(ctx).parentFile else SystemDirs.pieceDir(ctx, id)
        return dir.takeIf { it.isDirectory }
    }
}

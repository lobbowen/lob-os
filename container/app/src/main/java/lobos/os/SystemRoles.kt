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

    private fun all(ctx: Context) = lobos.native.NativeAssetRegistry.ALL

    private fun byRole(ctx: Context, role: String) = all(ctx).filter { it.role == role }

    fun shellBin(ctx: Context): File? {
        val e = byRole(ctx, SHELL).firstOrNull() ?: return null
        return File(SystemDirs.bin(ctx), e.installedAs).takeIf { it.isFile }
    }

    fun multiCommandBin(ctx: Context): File? {
        val e = byRole(ctx, MULTI_COMMAND).firstOrNull() ?: return null
        return File(SystemDirs.bin(ctx), e.installedAs).takeIf { it.isFile }
    }

    fun appletsOf(ctx: Context): List<String> =
        byRole(ctx, MULTI_COMMAND).flatMap { it.applets }.sorted()

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
    fun isEntry(e: lobos.native.NativeExecutable): Boolean =
        e.role == SHELL || e.role == EXEC || e.role == MULTI_COMMAND

    fun isEntryBin(e: lobos.native.NativeExecutable): Boolean = isEntry(e)

    /**
     * 这个 id 是不是一件（系统文件），它落在哪 —— 不是则返回 null。
     *
     * 「是不是件」由它自己的 role 决定（注册表声明），不靠包外清单的 kind字符串：
     * 换一份清单不该改变「它落哪」。
     */
    fun pieceDirFor(ctx: Context, id: String): File? {
        val e = lobos.native.NativeAssetRegistry.of(id) ?: return null
        val dir = if (isEntry(e)) SystemDirs.bin(ctx).parentFile else SystemDirs.pieceDir(ctx, id)
        return dir.takeIf { it.isDirectory }
    }
}

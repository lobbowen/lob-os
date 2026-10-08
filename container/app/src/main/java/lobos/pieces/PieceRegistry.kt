package lobos.pieces

/**
 * 12 件内置件 —— 只有**推导不出来**的那些属性。
 *
 * id / version / entry / role(命令还是库) / requiredDeps / sha256
 * 全部由落位形状与 [lobos.os.ElfFacts] 推导，不需要在这里声明。
 *
 * 这份数据对应的构建期来源是 scripts/component-sources.json（版本与源码校验值）
 * 与 scripts/component-verify.json（是否必需）。改了那些要重新生成这里，
 * 不该手改。
 */
object PieceRegistry {

    val LIBCXX = Piece(
        libName = "libc++_shared.so",
        required = true,
        role = "library",
        provides = listOf("cxx-runtime"),
    )

    val CAPABILITY: List<Piece> get() = listOf(
        Piece(
            libName = "libc++_shared.so",
            required = true,
            provides = listOf("cxx-runtime"),
        ),
        Piece(
            libName = "libbash.so",
            version = "5.2.15",
            installName = "bash",
            required = false,
            role = "shell",
            provides = listOf("shell", "exec"),
        ),
        Piece(
            libName = "liblobosrg.so",
            version = "14.1.1",
            installName = "rg",
            required = false,
            role = "exec",
            provides = listOf("glob", "grep"),
        ),
        Piece(
            libName = "liblobosflock.so",
            required = false,
            provides = listOf("file-lock"),
        ),
        Piece(
            libName = "liblobosposix.so",
            required = false,
            provides = listOf("posix-shim"),
        ),
        Piece(
            libName = "librivospty.so",
            installName = "pty-session",
            required = false,
            role = "exec",
            provides = listOf("pty-session"),
        ),
        Piece(
            libName = "liblobosptyprobe.so",
            required = false,
            role = "exec",
            provides = listOf("pty-probe"),
        ),
        Piece(
            libName = "libbusybox.so",
            version = "1.36.1",
            installName = "busybox",
            required = false,
            role = "multi-command",
            provides = listOf("coreutils"),
        ),
        Piece(
            libName = "libz.so",
            version = "1.3.2",
            required = true,
            provides = listOf("compress"),
        ),
        Piece(
            libName = "libssl.so",
            version = "3.6.3",
            required = true,
            provides = listOf("tls"),
        ),
        Piece(
            libName = "libcrypto.so",
            required = true,
            provides = listOf("crypto"),
        ),
        Piece(
            libName = "libcurl.so",
            version = "8.22.0",
            required = true,
            provides = listOf("http"),
        ),
    )

    val LIBS: List<Piece> get() = CAPABILITY.filter { it.role == lobos.os.SystemRoles.LIBRARY }

    val BINS: List<Piece> get() = CAPABILITY.filter {
        lobos.os.SystemRoles.isEntry(it) || it.role == lobos.os.SystemRoles.MULTI_COMMAND
    }

    val ALL: List<Piece> get() = listOf(LIBCXX) + CAPABILITY

    fun of(id: String): Piece? = ALL.firstOrNull { it.id == id }

    /** 落位后叫什么（`rg` 而非 `ripgrep`） */
    fun installedAs(e: Piece): String = e.installedAs

    fun resolve(ctx: android.content.Context, e: Piece): java.io.File =
        lobos.os.SystemDirs.bin(ctx).let { java.io.File(it, e.installedAs) }
}

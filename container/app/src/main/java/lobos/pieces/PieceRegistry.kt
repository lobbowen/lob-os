package lobos.pieces

/**
 * 预装的件 —— 12 件，全部一样，没有类别。
 *
 * **没有「内置件」这种东西**。件就是件，来源只是「预装」还是「后装」——
 * 这个区别属于分发，不属于件本身。所以这里是一份平铺的清单，
 * 没有 LIBCXX / CAPABILITY 那种把预装的单拎出来的分类。
 * （此前 LIBCXX 与 CAPABILITY[0] 都声明了 libc++_shared.so，
 *   ALL = LIBCXX + CAPABILITY 会产生两条重复的 —— 那正是「分出两类」的病根。）
 *
 * 这里只留**推导不出来**的：.so 文件名、入口改名、版本、是否必需、
 * multi-command 形态、对外能力。
 * id / entry / 依赖 / sha256 由落位形状与 [lobos.os.ElfFacts] 推导。
 *
 * 对应 Linux：这些内容本该在每个包自己的 control 里（deb-control(5)：
 * "Each Debian binary package contains a control file in its control member"），
 * 内核一个都不知道。这里之所以还在，是因为内置链的落位还没把那份说明带下来。
 */
object PieceRegistry {

    /** 预装件清单 —— 平铺，无分类 */
    val PIECES: List<Piece> get() = listOf(
        Piece(
            libName = "libc++_shared.so"
            required = true,
            provides = listOf("cxx-runtime"),
        ),
        Piece(
            libName = "libbash.so"
            version = "5.2.15"
            installName = "bash"
            required = false,
            role = "shell",
            provides = listOf("shell", "exec"),
        ),
        Piece(
            libName = "liblobosrg.so"
            version = "14.1.1"
            installName = "rg"
            required = false,
            role = "exec",
            provides = listOf("glob", "grep"),
        ),
        Piece(
            libName = "liblobosflock.so"
            required = false,
            provides = listOf("file-lock"),
        ),
        Piece(
            libName = "liblobosposix.so"
            required = false,
            provides = listOf("posix-shim"),
        ),
        Piece(
            libName = "librivospty.so"
            installName = "pty-session"
            required = false,
            role = "exec",
            provides = listOf("pty-session"),
        ),
        Piece(
            libName = "liblobosptyprobe.so"
            required = false,
            role = "exec",
            provides = listOf("pty-probe"),
        ),
        Piece(
            libName = "libbusybox.so"
            version = "1.36.1"
            installName = "busybox"
            required = false,
            role = "multi-command",
            provides = listOf("coreutils"),
        ),
        Piece(
            libName = "libz.so"
            version = "1.3.2"
            required = true,
            provides = listOf("compress"),
        ),
        Piece(
            libName = "libssl.so"
            version = "3.6.3"
            required = true,
            provides = listOf("tls"),
        ),
        Piece(
            libName = "libcrypto.so"
            required = true,
            provides = listOf("crypto"),
        ),
        Piece(
            libName = "libcurl.so"
            version = "8.22.0"
            required = true,
            provides = listOf("http"),
        ),
    )

    fun of(id: String): Piece? = PIECES.firstOrNull { it.id == id }

    fun installedAs(e: Piece): String = e.installedAs
}

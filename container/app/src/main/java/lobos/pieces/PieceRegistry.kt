package lobos.pieces

import android.content.Context
import lobos.runtime.InstanceHost
import java.io.File

object PieceRegistry {

    val LIBCXX = Piece(
        id = "libcxx",
        libName = "libc++_shared.so",
        humanName = "C++ 运行期",
        requiredDeps = emptyList(),
        required = true,
        note = "必须随 APK：APK 内 C/C++ 编译件的运行期依赖（node-pty 等）",
        role = "library"
    )

    val CAPABILITY: List<Piece> get() = listOf(
        Piece(
            id = "bash", libName = "libbash.so", humanName = "bash 执行器",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "bash",
            versionArgs = listOf("--version"),
            role = "shell",
            provides = listOf("shell", "exec"),
            note = "jniLibs 路径；P2 起 bash 改由前缀目录提供",
            role = "library"
        ),
        Piece(
            id = "ripgrep", libName = "liblobosrg.so", humanName = "ripgrep（glob/grep）",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "rg",
            versionArgs = listOf("--version"),
            role = "exec",
            provides = listOf("glob", "grep"),
            note = "缺件时 glob/grep 报 SEARCH_FAILED"
        ),
        Piece(
            id = "flock", libName = "liblobosflock.so", humanName = "flock(2) 系统调用",
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "dlopen 依赖；缺件回退 vendor 实现",
            role = "exec"
        ),
        Piece(
            id = "posix", libName = "liblobosposix.so", humanName = "link/linkat 用户态替代",
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "经 LD_PRELOAD 注入；缺件会让会话落盘失败",
            role = "exec"
        ),
        Piece(
            id = "ptyprobe", libName = "liblobosptyprobe.so", humanName = "PTY 探针",
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "落位即事实；用不用得了由使用者自己知道",
            role = "exec"
        ),
        Piece(
            id = "ptysession", libName = "librivospty.so", humanName = "PTY 会话宿主",
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            installName = "pty-session",
            note = "常驻可执行件（非库）：分配 PTY + setsid + TIOCSCTTY 后 execve。" +
                "ProcessBuilder 不给 PTY，所以要它；走「可执行件 + 帧协议」而不是 JNI —— " +
                "仓内已有两种编译件范式（LD_PRELOAD 注入 / 可执行件），本件属后者，" +
                "避开 System.loadLibrary 的装载路径与被误当共享库加载的问题。" +
                "不是可执行件 → 走「数据资产」分支（它不是拿来执行的，" +
                "真正的可用性判据是 PtySession 自己探 isatty/窗口大小）",
            role = "exec",
            provides = listOf("terminal")
        ),
        Piece(
            id = "busybox", libName = "libbusybox.so", humanName = "busybox 基础命令集",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "busybox",
            versionArgs = listOf("--help"),
            role = "multi-command",
            applets = listOf(
                "tar", "gzip", "gunzip", "grep", "sed", "awk", "ls", "cp", "mv",
                "cat", "mkdir", "rm", "ln", "vi", "df", "ps", "true", "false"
            ),
            note = "多调用二进制：用户敲 tar/grep/ls（软链由 PrefixProvisioner 建），" +
                "不是 busybox tar ——" +
                "证明 applet 真编进去了（配置项名写错时 busybox 会静默少编）。" +
                "静态编、不链底座 libz：底座件之间不互相依赖到「少一件就起不来」。" +
                "**versionArgs=--help 是待实测项**：busybox 没有 --version，" +
                "版本在 --help 首行（形如 BusyBox v1.36.1 ...）。仓里没有编好的 busybox 可验，" +
                "所以这一格要真机确认；不成立时 versionOf 取不到会回退到装件记录的版本（不算错，只是拿不到实测值）"
        ),

        Piece(
            id = "zlib", libName = "libz.so", humanName = "zlib 压缩库",
            requiredDeps = emptyList(), required = true, buildTier = "upstream",
            note = "busybox 的 gzip/tar 与 curl 都要它；原先两个商店脚本各静态编一遍",
            role = "library"
        ),
        Piece(
            id = "openssl", libName = "libssl.so", humanName = "OpenSSL 传输层",
            requiredDeps = listOf("libcrypto.so"), required = true, buildTier = "upstream",
            note = "libcurl 的 DT_NEEDED 含它 —— 同目录，解析靠链接期 -Wl,-rpath,\$ORIGIN",
            role = "library"
        ),
        Piece(
            id = "crypto", libName = "libcrypto.so", humanName = "OpenSSL 加密原语",
            requiredDeps = emptyList(), required = true, buildTier = "upstream",
            note = "与 libssl 一并编出；不带版本号 soname —— bionic 按 DT_NEEDED 的文件名找库",
            role = "library"
        ),
        Piece(
            id = "curl", libName = "libcurl.so", humanName = "curl 传输库",
            requiredDeps = listOf("libssl.so", "libcrypto.so", "libz.so"),
            required = true, buildTier = "upstream",
            note = "git 链它；商店件另有 curl 可执行二进制（那是商店件，不是底座库）",
            role = "library"
        )
    )

    val LIBS: List<Piece>
        get() = ALL.filter { it.role == "library" }

    

    val BINS: List<Piece>
        get() = ALL.filter { it.role == "shell" || it.role == "exec" || it.role == "multi-command" }


    val ALL: List<Piece> get() = listOf(LIBCXX) + CAPABILITY

    fun of(id: String): Piece? = ALL.firstOrNull { it.id == id }

    fun libNameOf(id: String): String =
        of(id)?.libName
            ?: error("PieceRegistry 里没有 id=" + id + " 的资产 —— 拼错的 id 必须当场炸。")

    fun resolve(ctx: Context, e: Piece): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

}

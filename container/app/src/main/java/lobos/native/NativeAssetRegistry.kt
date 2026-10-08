package lobos.native

import android.content.Context
import lobos.runtime.InstanceHost
import java.io.File

object NativeAssetRegistry {

    val LIBCXX = NativeExecutable(
        id = "libcxx",
        libName = "libc++_shared.so",
        humanName = "C++ 运行期",
        probeArgs = emptyList(),
        probeExpect = null,
        requiredDeps = emptyList(),
        required = true,
        note = "必须随 APK：APK 内 C++ 原生件（node-pty 等）的运行期依赖",
    )

    val CAPABILITY: List<NativeExecutable> get() = listOf(
        NativeExecutable(
            id = "bash", libName = "libbash.so", humanName = "bash 执行器",
            probeArgs = listOf("-c", "exit 0"), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "bash",
            version = "5.2.15",
            note = "jniLibs 路径；P2 起 bash 改由前缀目录提供",
        ),
        NativeExecutable(
            id = "ripgrep", libName = "liblobosrg.so", humanName = "ripgrep（glob/grep）",
            probeArgs = listOf("--version"), probeExpect = "ripgrep",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "rg",
            note = "缺件时 glob/grep 报 SEARCH_FAILED",
        ),
        NativeExecutable(
            id = "flock", libName = "liblobosflock.so", humanName = "flock(2) 原生桥",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "dlopen 依赖；缺件回退 vendor 实现",
        ),
        NativeExecutable(
            id = "posix", libName = "liblobosposix.so", humanName = "link/linkat 用户态替代",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "经 LD_PRELOAD 注入；缺件会让会话落盘失败",
        ),
        NativeExecutable(
            id = "ptyprobe", libName = "liblobosptyprobe.so", humanName = "PTY 探针",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            note = "真实 exec 由 InstanceHost.runPtyProbe() 执行",
        ),
        NativeExecutable(
            id = "ptysession", libName = "librivospty.so", humanName = "PTY 会话宿主",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "self-c",
            installName = "pty-session",
            note = "常驻可执行件（非库）：分配 PTY + setsid + TIOCSCTTY 后 execve。" +
                "ProcessBuilder 不给 PTY，所以要它；走「可执行件 + 帧协议」而不是 JNI —— " +
                "仓内已有两种原生范式（LD_PRELOAD 注入 / 可执行件探针），本件属后者，" +
                "避开 System.loadLibrary 的装载路径与被误当共享库加载的问题。" +
                "probeArgs 空 → 走「数据资产」分支不做 exec-probe（它起不来就没意义，" +
                "真正的可用性判据是 PtySession 自己探 isatty/窗口大小）",
        ),
        NativeExecutable(
            id = "busybox", libName = "libbusybox.so", humanName = "busybox 基础命令集",
            probeArgs = listOf("--list"), probeExpect = "tar",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            installName = "busybox",
            version = "1.36.1",
            note = "多调用二进制：用户敲 tar/grep/ls（软链由 PrefixProvisioner 建），" +
                "不是 busybox tar。探针用 --list 并期待 tar —— 比看二进制在不在强，" +
                "证明 applet 真编进去了（配置项名写错时 busybox 会静默少编）。" +
                "静态编、不链底座 libz：底座件之间不互相依赖到「少一件就起不来」",
        ),

        NativeExecutable(
            id = "zlib", libName = "libz.so", humanName = "zlib 压缩库",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = true, buildTier = "upstream",
            version = "1.3.2",
            note = "busybox 的 gzip/tar 与 curl 都要它；原先两个商店脚本各静态编一遍",
        ),
        NativeExecutable(
            id = "openssl", libName = "libssl.so", humanName = "OpenSSL 传输层",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = listOf("libcrypto.so"), required = true, buildTier = "upstream",
            version = "3.6.3",
            note = "libcurl 的 DT_NEEDED 含它 —— 同目录，解析靠链接期 -Wl,-rpath,\$ORIGIN",
        ),
        NativeExecutable(
            id = "crypto", libName = "libcrypto.so", humanName = "OpenSSL 加密原语",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = true, buildTier = "upstream",
            note = "与 libssl 一并编出；不带版本号 soname —— bionic 按 DT_NEEDED 的文件名找库",
        ),
        NativeExecutable(
            id = "curl", libName = "libcurl.so", humanName = "curl 传输库",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = listOf("libssl.so", "libcrypto.so", "libz.so"),
            required = true, buildTier = "upstream",
            version = "8.22.0",
            note = "git 链它；商店件另有 curl 可执行二进制（那是商店件，不是底座库）",
        ),
    )

    val LIBS: List<NativeExecutable>
        get() = (ALL.filter { it.id in LIB_IDS } + CAPABILITY.filter { it.id in LIB_IDS })

    private val LIB_IDS = setOf("libcxx", "zlib", "openssl", "crypto", "curl")

    val BINS: List<NativeExecutable>
        get() = CAPABILITY.filter { it.id in BIN_IDS }

    val BIN_IDS: Set<String> = setOf("bash", "ripgrep", "ptysession", "busybox")

    val ALL: List<NativeExecutable> get() = listOf(LIBCXX)
    fun libNameOf(id: String): String =
        (ALL + CAPABILITY).firstOrNull { it.id == id }?.libName
            ?: error("NativeAssetRegistry 里没有 id=" + id + " 的资产 —— 拼错的 id 必须当场炸。")

    fun resolve(ctx: Context, e: NativeExecutable): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

}

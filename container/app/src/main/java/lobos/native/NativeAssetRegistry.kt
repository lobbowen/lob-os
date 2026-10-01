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
        required = false,
        note = "不是可执行文件，但必须在 nativeLibraryDir —— libnode.so 的 DT_NEEDED 依赖它",
    )

    val NODE = NativeExecutable(
        id = "node",
        libName = "libnode.so",
        humanName = "Node 运行时",
        probeArgs = listOf("-v"),
        probeExpect = "v",
        requiredDeps = listOf("libc++_shared.so"),
        required = false,
        note = "实为可执行文件，改名 lib*.so 借 jniLibs 通道落到 exec_type 目录",
    )

    val CAPABILITY: List<NativeExecutable> get() = listOf(
        NativeExecutable(
            id = "bash", libName = "libbash.so", humanName = "bash 执行器",
            probeArgs = listOf("-c", "exit 0"), probeExpect = null,
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
            note = "jniLibs 路径；P2 起 bash 改由前缀目录提供",
        ),
        NativeExecutable(
            id = "ripgrep", libName = "liblobosrg.so", humanName = "ripgrep（glob/grep）",
            probeArgs = listOf("--version"), probeExpect = "ripgrep",
            requiredDeps = emptyList(), required = false, buildTier = "upstream",
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
    )

    val ALL: List<NativeExecutable> get() = listOf(LIBCXX, NODE)

    val REQUIRED: List<NativeExecutable> get() = ALL.filter { it.required }

    fun libNameOf(id: String): String =
        (ALL + CAPABILITY).firstOrNull { it.id == id }?.libName
            ?: error("NativeAssetRegistry 里没有 id=" + id + " 的资产 —— 拼错的 id 必须当场炸。")

    fun resolve(ctx: Context, e: NativeExecutable): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

}

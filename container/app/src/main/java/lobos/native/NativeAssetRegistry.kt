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

    val NODE = NativeExecutable(
        id = "node",
        libName = "libnode.so",
        humanName = "Node 运行时",
        probeArgs = listOf("-v"),
        probeExpect = "v",
        requiredDeps = listOf("libc++_shared.so"),
        required = false,
        note = "APK 内不该有这份（build-apk.yml 有断言）。正常形态是商店件装到 " +
            "files/usr/lib/toolchain/node/；这一条只留作兜底认 APK 里已存在的 libnode.so，required=false",
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

    val ALL: List<NativeExecutable> get() = listOf(LIBCXX)
    fun libNameOf(id: String): String =
        (ALL + CAPABILITY).firstOrNull { it.id == id }?.libName
            ?: error("NativeAssetRegistry 里没有 id=" + id + " 的资产 —— 拼错的 id 必须当场炸。")

    fun resolve(ctx: Context, e: NativeExecutable): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

}

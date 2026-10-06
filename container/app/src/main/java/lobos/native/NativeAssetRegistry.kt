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

        // ── 底座运行层共享库（scripts/build-base-libs.sh 编一次，全系统共用）──
        //
        // 四条共同点：
        //   · probeArgs 空 + probeExpect null → NativePreparer 走「数据资产」分支，
        //     不做 exec-probe。**库不是可执行件**，exec 它必然失败（真库没有 PT_INTERP）。
        //   · required=true：底座必备，缺一件 $PREFIX 就不完整。
        //   · buildTier=upstream：上游源码配方，编不出来即环境问题 ⇒ 缺件硬红。
        //
        // 这几件原先在 build-userland-curl.sh 与 build-userland-git.sh 里**各编一遍静态**，
        // 现在编一份共享放 $PREFIX/lib，第 2 阶段 curl/git 改动态链时才有东西可链。
        // 不进 ALL：ALL 是「APK 内必需资产」，会进 .github/native-assets.txt 的
        // 依赖库段而被 verify-runtime-elf.sh 判「必须是可动态链接的共享库」。
        NativeExecutable(
            id = "zlib", libName = "libz.so", humanName = "zlib 压缩库",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = emptyList(), required = true, buildTier = "upstream",
            note = "busybox 的 gzip/tar 与 curl 都要它；原先两个商店脚本各静态编一遍",
        ),
        NativeExecutable(
            id = "openssl", libName = "libssl.so", humanName = "OpenSSL 传输层",
            probeArgs = emptyList(), probeExpect = null,
            requiredDeps = listOf("libcrypto.so"), required = true, buildTier = "upstream",
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
            note = "git 链它；商店件另有 curl 可执行二进制（那是商店件，不是底座库）",
        ),
    )

    /**
     * 底座库（不是可执行件）：落 $PREFIX/lib，不落 $PREFIX/bin。
     *
     * 与 BINS 的分工：BINS 是要被 exec 的件（bash/rg），要 ExecBits；
     * 这里全是共享库，给执行位无意义，且 bionic 加载库不查执行位。
     *
     * libcxx（libc++_shared.so）在 ALL 里而不在 CAPABILITY 里 —— 它是「随包必需资产」
     * 不是「能力件」。两处都要，别只取 CAPABILITY。
     */
    val LIBS: List<NativeExecutable>
        get() = (ALL.filter { it.id in LIB_IDS } + CAPABILITY.filter { it.id in LIB_IDS })

    private val LIB_IDS = setOf("libcxx", "zlib", "openssl", "crypto", "curl")

    val ALL: List<NativeExecutable> get() = listOf(LIBCXX)
    fun libNameOf(id: String): String =
        (ALL + CAPABILITY).firstOrNull { it.id == id }?.libName
            ?: error("NativeAssetRegistry 里没有 id=" + id + " 的资产 —— 拼错的 id 必须当场炸。")

    fun resolve(ctx: Context, e: NativeExecutable): File =
        File(ctx.applicationInfo.nativeLibraryDir, e.libName)

}

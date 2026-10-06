package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.native.NativeAssetRegistry
import java.io.File
import lobos.os.ProgramManager

object PrefixProvisioner {

    // 底座必备件。
//
// node 不在其中：它是「程序运行时」，按需安装（对齐 Linux —— 发行版不默认装 node，
// 用户自己装）。node 装完后由 ProgramInstallPipeline 建 usr/bin/node 软链，
// 不由这里管。
//
// 「APK 里的文件名 → 底座里的名字」这份映射**只在注册表里**（NativeExecutable
// 的 libName/installName），这里不再重抄 —— 抄一份必然漂移，而漂移的后果是
// 装出来一个用户敲不出来的命令名。
//
// ptysession 在其中但**不叫** ptysession：它在 APK 里叫 librivospty.so
// （jniLibs 只打包 .so），底座里叫 pty-session —— 对齐 Linux 的 /usr/bin/<工具名>，
// 别让用户在 PATH 里看到一个「.so」当命令敲。
private val BINS: List<Pair<String, String>>
    get() = NativeAssetRegistry.BINS.map { it.libName to it.installedAs }

/**
 * busybox 的 applet 软链（照 Linux 惯例：多调用二进制 + 一堆名字）。
 *
 * 名单来自 scripts/build-native-busybox.sh 的 APPLETS —— 那是编译时
 * 真正编进去的清单，这里必须一致：软链一个没编进去的名字，会得到
 * 「敲了没反应」而不是「没有这个命令」。
 */
private val BUSYBOX_APPLETS = listOf(
    "tar", "gzip", "gunzip", "grep", "sed", "awk", "ls", "cp", "mv",
    "cat", "mkdir", "rm", "ln", "vi", "df", "ps", "true", "false",
)

// 底座库：无条件必需，落 $PREFIX/lib（对齐 Linux 的「库进 /lib」）。
// 原先这一份被铺到 binDir —— 库因此落在 usr/bin，libDir() 指向空目录，
// 「库在 usr/lib」这条判据在代码里没有兑现。现在它真的落 usr/lib，
// 而 RuntimeEnvironment.libSearchPath() 第二项就是这个目录。
//
// 清单来自 NativeAssetRegistry.LIBS，不在这里重抄一份（抄一份必然漂移）。
private val DEPS: List<Pair<String, String>>
    get() = NativeAssetRegistry.LIBS.map { it.libName to it.libName }

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"

/**
 * sysroot 件在商店里的名字（scripts/build-userland-sysroot.sh 与
 * userland-verify.json 的 criteria.sysroot.entry 对应）。
 *
 * 硬编码在这儿是因为 $PREFIX/include 的软链**必须**知道它 ——
 * 与其把「当前版本在哪」抽象成一层间接，不如直接读那一个事实源。
 * 改名时要同步改：构建脚本、userland-verify.json 的 entry、以及本行。
 */
private const val SYSROOT_ID = "sysroot"

    fun root(ctx: Context): File = File(ctx.filesDir, "usr")
    fun binDir(ctx: Context): File = File(root(ctx), "bin")
    fun libDir(ctx: Context): File = File(root(ctx), "lib")

    /** $PREFIX/include —— 头文件的「位置约定」入口（Linux 里是 /usr/include）。 */
    fun includeDir(ctx: Context): File = File(root(ctx), "include")

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        // (件表, 落位目录, 是否可执行)。可执行位只给 BINS —— 库给执行位无意义，
        // 而 bionic 加载库不查执行位。这里显式传标志，不用 `items === BINS`
        // 那种引用比较（DEPS 是 getter，每次新 list，引用比较迟早失效）。
        val plan = listOf(
            Triple(BINS, binDir(ctx), true),
            Triple(DEPS, libDir(ctx), false),
        )
        for ((items, dir, executable) in plan) {
            dir.mkdirs()
            for ((libName, name) in items) {
                val src = File(nativeDir, libName)
                val dst = File(dir, name)
                // OTA 已接管这一件（usr/bin/<name> 是指向 toolchain/<id>/<ver>/ 的软链）
                // → **不许覆盖**。本方法在每次进程启动都会跑，无条件覆盖会把
                // OTA 更新下来的件冲回 APK 原件，而且没有任何日志提示。
                if (isManagedByUpdate(ctx, dst)) {
                    ready += name
                    continue
                }
                if (!src.isFile) { dst.delete(); continue }
                if (!dst.isFile || dst.length() != src.length()) {
                    try {
                        src.copyTo(dst, overwrite = true)
                        if (executable) ExecBits.apply(dst)
                    } catch (_: Exception) { dst.delete(); continue }
                }
                ready += name
            }
        }
        linkBusyboxApplets(ctx)?.let { ready += it }
        linkSysrootInclude(ctx)?.let { ready += it }
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input -> caDst.outputStream().use { out -> input.copyTo(out) } }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        return ready
    }

    /**
     * 这一件是不是被 OTA 更新接管了。
     *
     * 判据形态：**软链且指向 toolchain/ 下**（即不是指向 APK 原件那份）。
     * 为什么这样判：原件在 APK 里、永远不动；OTA 件落在
     * `usr/lib/toolchain/<id>/<version>/`，`usr/bin/<name>` 是指向它的软链。
     * 「软链指向 toolchain/」就是「这一件已经不是原件了」的唯一可靠标志 ——
     * 比查清单版本可靠（清单可能还没刷新，而软链已经切过去了）。
     */
    private fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) return@try false
        val target = dst.toPath().toRealPath()
        target.startsWith(libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

    fun bashBin(ctx: Context): File? = File(binDir(ctx), "bash").takeIf { it.isFile }

    /**
     * 建 busybox 的 applet 软链（tar → busybox，grep → busybox，…）。
     *
     * 照 Linux 惯例：用户敲 `tar` 而不是 `busybox tar`。判据用
     * `busybox tar --help` 而不是 `tar --help` —— 后者只证明软链在，
     * 前者才证明那个 applet 真编进去了。
     *
     * busybox 不在位时返回空列表并**不判红**：它是 upstream 档，
     * 缺件由 verify-runtime-elf.sh 在构建期判；运行期缺了只是少一批命令，
     * 不该让整个底座装配失败（那会让 bash/rg 也跟着不可用）。
     */
    /**
     * 把 $PREFIX/include 软链到 sysroot 件的当前版本头文件目录。
     *
     * ── 为什么需要它 ──
     * 编译链的三种找头文件方式里，只有这一种不要求调用方知道路径：
     *   ① -I/-L           —— node-gyp 走这条（它自己带 flags）
     *   ② --sysroot=<路径> —— 要显式传，configure 不会替你猜
     *   ③ /usr/include     —— **位置约定**，configure 会自己找
     * 少了 ③，一个跑 `./configure` 的包在设备上会报
     * `fatal error: stdio.h: No such file or directory` ——
     * 而那是「装好了、跑起来了、直到编译才崩」的最坏形态。
     *
     * ── 为什么指向「当前版本」而不是固定路径 ──
     * sysroot 件按商店通道装，落在 `programs/sysroot/<版本>/sysroot`，
     * 版本随升级变。所以链必须在**每次 provision 时重新指向当前版本** ——
     * 否则升级 sysroot 后，链还指着旧版本的头文件，而那才是真正的静默出错。
     *
     * sysroot 不在位时返回空列表并**不判红**：开发环境是可选的
     * （用户没装 clang 时不需要头文件）。缺它时报的是 configure 那句
     * file not found，指向明确，不需要在这里多报一次。
     */
    private fun linkSysrootInclude(ctx: Context): List<String> {
        val sysrootRoot = sysrootIncludeDir(ctx) ?: return emptyList()
        val link = includeDir(ctx)
        return try {
            link.parentFile?.mkdirs()
            // 已经是链且指向对的地方 → 不动（避免每次启动都重建）
            val cur = runCatching { link.toPath().toRealPath() }.getOrNull()
            if (cur != null && cur == runCatching { sysrootRoot.toPath().toRealPath() }.getOrNull()) {
                return listOf("include")
            }
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                // 有人放了真文件在那儿 —— 不覆盖（那可能是用户自己放的）
                return emptyList()
            }
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            java.nio.file.Files.createSymbolicLink(link.toPath(), sysrootRoot.toPath())
            listOf("include")
        } catch (_: Exception) {
            runCatching { link.delete() }
            emptyList()
        }
    }

    /**
     * sysroot 件当前版本的头文件目录；件不在位或没有头文件时返回 null。
     *
     * 走 `ProgramDir.currentVersion()` 而不是去列目录取「最新」——
     * 那会把「装了两个版本」误当成「最新的那个」，而 CURRENT 指针才是事实。
     */
    fun sysrootIncludeDir(ctx: Context): File? {
        return try {
            val dir = lobos.os.ProgramDir(ctx, SYSROOT_ID).currentVersion()?.let {
                File(lobos.os.ProgramManager.stateDirOf(ctx, SYSROOT_ID), it)
            } ?: return null
            val inc = File(File(dir, "sysroot"), "include")
            if (inc.isDirectory) inc else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun linkBusyboxApplets(ctx: Context): List<String> {
        val bb = File(binDir(ctx), "busybox")
        if (!bb.isFile) return emptyList()
        val made = mutableListOf<String>()
        for (applet in BUSYBOX_APPLETS) {
            val link = File(binDir(ctx), applet)
            try {
                // 已存在的真文件（别的件提供同名命令）不覆盖 —— 底座不能抢位置
                if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) continue
                java.nio.file.Files.deleteIfExists(link.toPath())
                java.nio.file.Files.createSymbolicLink(link.toPath(), bb.toPath())
                made += applet
            } catch (_: Exception) {
                // 建不了这一条就跳过这一条，不影响其它
            }
        }
        return made
    }

    /** busybox 是否就位（控制面板与判据用）。 */
    fun busyboxBin(ctx: Context): File? = File(binDir(ctx), "busybox").takeIf { it.isFile }

    /**
     * 底座必备件清单。node 不在里面 —— 它是程序运行时，按需安装。
     * 判据：缺任何一件都属于底座不完整（系统不成立）；node 缺席只是「还没装」。
     */
    fun expected(ctx: Context): List<String> =
        BINS.map { it.second } + DEPS.map { it.second } + CA_BUNDLE_NAME
}

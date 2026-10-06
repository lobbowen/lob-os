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
private val BINS = listOf(
    NativeAssetRegistry.libNameOf("bash") to "bash",
    NativeAssetRegistry.libNameOf("ripgrep") to "rg",
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

    fun root(ctx: Context): File = File(ctx.filesDir, "usr")
    fun binDir(ctx: Context): File = File(root(ctx), "bin")
    fun libDir(ctx: Context): File = File(root(ctx), "lib")
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
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input -> caDst.outputStream().use { out -> input.copyTo(out) } }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        return ready
    }

    fun bashBin(ctx: Context): File? = File(binDir(ctx), "bash").takeIf { it.isFile }

    /**
     * 底座必备件清单。node 不在里面 —— 它是程序运行时，按需安装。
     * 判据：缺任何一件都属于底座不完整（系统不成立）；node 缺席只是「还没装」。
     */
    fun expected(ctx: Context): List<String> =
        BINS.map { it.second } + DEPS.map { it.second } + CA_BUNDLE_NAME
}

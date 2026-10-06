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

// C++ 运行库：无条件必需。
// 原先它被放在「node 在场才算」的条件里 —— 那是错的：任何 C++ 件都要它，
// 与 node 无关。node 缺席时它仍必须存在。
private val DEPS = listOf(
    NativeAssetRegistry.LIBCXX.libName to NativeAssetRegistry.LIBCXX.libName,
)

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
        for ((items, dir) in listOf(BINS to binDir(ctx), DEPS to binDir(ctx))) {
            dir.mkdirs()
            val executable = items === BINS
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

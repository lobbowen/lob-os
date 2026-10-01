package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.native.NativeAssetRegistry
import java.io.File

object PrefixProvisioner {

    private val BINS = listOf(
        NativeAssetRegistry.libNameOf("bash") to "bash",
        NativeAssetRegistry.libNameOf("ripgrep") to "rg",
    )
    private val DEPS = listOf(
        NativeAssetRegistry.LIBCXX.libName to NativeAssetRegistry.LIBCXX.libName,
    )
    private val LIBS = listOf("liblobospty.so" to "pty.node")

    const val NODE_BIN_NAME = "node"

    const val CA_BUNDLE_NAME = "ca-bundle.pem"
    private const val CA_BUNDLE_ASSET = "ca-bundle.pem"

    fun root(ctx: Context): File = File(ctx.filesDir, "usr")
    fun binDir(ctx: Context): File = File(root(ctx), "bin")
    fun libDir(ctx: Context): File = File(root(ctx), "lib")
    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    fun provision(ctx: Context, nodeBin: File): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        for ((items, dir) in listOf(BINS to binDir(ctx), DEPS to binDir(ctx), LIBS to libDir(ctx))) {
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
        val preferred = lobos.os.FacilityManager.nodeBin(ctx) ?: nodeBin.takeIf { it.isFile }
        if (preferred != null && linkNode(ctx, preferred) != null) ready += NODE_BIN_NAME
        return ready
    }

    private fun linkNode(ctx: Context, nodeBin: File): File? {
        binDir(ctx).mkdirs()
        val link = File(binDir(ctx), NODE_BIN_NAME)
        val target = nodeBin.absolutePath
        val current = try { Os.readlink(link.absolutePath) } catch (_: Exception) { null }
        if (current == target) return link
        try {
            link.delete()
            Os.symlink(target, link.absolutePath)
            return link
        } catch (_: Exception) {
            return null
        }
    }

    fun bashBin(ctx: Context): File? = File(binDir(ctx), "bash").takeIf { it.isFile }

    fun expected(ctx: Context): List<String> {
        val base = BINS.map { it.second } + LIBS.map { it.second } + CA_BUNDLE_NAME
        val nodePresent = lobos.os.FacilityManager.nodeBin(ctx) != null
        return if (nodePresent) base + DEPS.map { it.second } + NODE_BIN_NAME else base
    }
}

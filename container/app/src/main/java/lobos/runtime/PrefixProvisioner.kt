package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.native.NativeAssetRegistry
import java.io.File
import lobos.os.ProgramManager

object PrefixProvisioner {

private val BINS: List<Pair<String, String>>
    get() = NativeAssetRegistry.BINS.map { it.libName to it.installedAs }

private val BUSYBOX_APPLETS = listOf(
    "tar", "gzip", "gunzip", "grep", "sed", "awk", "ls", "cp", "mv",
    "cat", "mkdir", "rm", "ln", "vi", "df", "ps", "true", "false",
)

private val DEPS: List<Pair<String, String>>
    get() = NativeAssetRegistry.LIBS.map { it.libName to it.libName }

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"

private const val SYSROOT_ID = "sysroot"

    fun root(ctx: Context): File = lobos.os.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.os.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.os.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.os.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val plan = listOf(
            Triple(BINS, binDir(ctx), true),
            Triple(DEPS, libDir(ctx), false),
        )
        for ((items, dir, executable) in plan) {
            dir.mkdirs()
            for ((libName, name) in items) {
                val src = File(nativeDir, libName)
                val dst = File(dir, name)
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

    private fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) return@try false
        val target = dst.toPath().toRealPath()
        target.startsWith(libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

    fun bashBin(ctx: Context): File? = File(binDir(ctx), "bash").takeIf { it.isFile }

    private fun linkSysrootInclude(ctx: Context): List<String> {
        val sysrootRoot = sysrootIncludeDir(ctx) ?: return emptyList()
        val link = includeDir(ctx)
        return try {
            link.parentFile?.mkdirs()
            val cur = runCatching { link.toPath().toRealPath() }.getOrNull()
            if (cur != null && cur == runCatching { sysrootRoot.toPath().toRealPath() }.getOrNull()) {
                return listOf("include")
            }
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
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
                if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) continue
                java.nio.file.Files.deleteIfExists(link.toPath())
                java.nio.file.Files.createSymbolicLink(link.toPath(), bb.toPath())
                made += applet
            } catch (_: Exception) {
            }
        }
        return made
    }

    fun busyboxBin(ctx: Context): File? = File(binDir(ctx), "busybox").takeIf { it.isFile }

    fun expected(ctx: Context): List<String> =
        BINS.map { it.second } + DEPS.map { it.second } + CA_BUNDLE_NAME
}

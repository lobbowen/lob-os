package lobos.kernel.layout

import lobos.kernel.KernelHooks

import android.content.Context
import android.system.Os
import java.io.File
import lobos.kernel.elf.ExecBits

object PrefixProvisioner {

    const val META_NAME = "component-meta.json"

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"


    fun root(ctx: Context): File = lobos.kernel.layout.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))




    private fun linkHeadersInclude(ctx: Context): List<String> {
        val headersRoot = headersIncludeDir(ctx) ?: return emptyList()
        val link = includeDir(ctx)
        return try {
            link.parentFile?.mkdirs()
            val cur = runCatching { link.toPath().toRealPath() }.getOrNull()
            if (cur != null && cur == runCatching { headersRoot.toPath().toRealPath() }.getOrNull()) {
                return listOf("include")
            }
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return emptyList()
            }
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            java.nio.file.Files.createSymbolicLink(link.toPath(), headersRoot.toPath())
            listOf("include")
        } catch (_: Exception) {
            runCatching { link.delete() }
            emptyList()
        }
    }

    fun headersIncludeDir(ctx: Context): File? = try {
        libDir(ctx).listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.firstOrNull { version ->
                File(version, "include").isDirectory
            }
    } catch (_: Throwable) {
        null
    }
}

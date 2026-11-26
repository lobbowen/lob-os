package lobos.kernel.layout

import lobos.kernel.KernelHooks

import java.io.File
import lobos.kernel.elf.ExecBits

object PrefixProvisioner {

    const val META_NAME = "component-meta.json"

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"

    fun root(): File = lobos.kernel.layout.SystemDirs.usr()
    fun binDir(): File = lobos.kernel.layout.SystemDirs.bin()
    fun libDir(): File = lobos.kernel.layout.SystemDirs.lib()

    fun includeDir(): File = lobos.kernel.layout.SystemDirs.include()

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(): File = caBundleAt(root())
    fun provision(): List<String> {
        val ready = mutableListOf<String>()
        linkHeadersInclude()?.let { ready += it }
        val caDst = caBundle()
        try {
            caDst.parentFile?.mkdirs()
            val caText = lobos.kernel.spi.KernelServices.paths().assetText(CA_BUNDLE_ASSET)
                ?: throw java.io.IOException("asset not found: $CA_BUNDLE_ASSET")
            caDst.writeText(caText)
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        return ready
    }

    private fun linkHeadersInclude(): List<String> {
        val headersRoot = headersIncludeDir() ?: return emptyList()
        val link = includeDir()
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

    fun headersIncludeDir(): File? = try {
        libDir().listFiles()?.asSequence()
            ?.filter { it.isDirectory }
            ?.firstOrNull { version ->
                File(version, "include").isDirectory
            }
    } catch (_: Throwable) {
        null
    }
}

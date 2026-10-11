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

    private const val META_ASSET_DIR = "supply/meta"

    fun root(ctx: Context): File = lobos.kernel.layout.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    private fun landOne(
        ctx: Context,
        id: String,
        version: String,
        soName: String,
        src: File,
        isEntry: Boolean,
        meta: org.json.JSONObject?,
    ): String? {
        val verDir = lobos.kernel.layout.SystemDirs.pieceDir(ctx, id, version)
        verDir.mkdirs()
        val dst = File(verDir, if (isEntry) "bin/$soName" else "lib/$soName")
        if (!src.isFile) return null
        if (!dst.isFile || dst.length() != src.length()) {
            dst.parentFile?.mkdirs()
            try {
                src.copyTo(dst, overwrite = true)
                if (isEntry) ExecBits.apply(dst)
            } catch (_: Exception) {
                dst.delete()
                return null
            }
        }
        if (meta != null) {
            runCatching {
                lobos.kernel.fs.StateFiles.writeAtomic(
                    File(verDir, META_NAME), meta.toString(1) + "\n",
                )
            }
        }
        val link = if (isEntry) {
            lobos.kernel.layout.SystemDirs.bin(ctx).let { File(it, soName) }
        } else {
            lobos.kernel.layout.SystemDirs.lib(ctx).let { File(it, soName) }
        }
        link.parentFile?.mkdirs()
        if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
        java.nio.file.Files.deleteIfExists(link.toPath())
        java.nio.file.Files.createSymbolicLink(
            link.toPath(),
            dst.toPath().toAbsolutePath().normalize(),
        )
        return dst.absolutePath
    }

    private fun scanMeta(ctx: Context): List<org.json.JSONObject> {
        val out = mutableListOf<org.json.JSONObject>()
        val names = try {
            ctx.assets.list(META_ASSET_DIR)?.toList() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        for (name in names) {
            if (!name.endsWith(META_NAME)) continue
            runCatching {
                val m = org.json.JSONObject(
                    ctx.assets.open("$META_ASSET_DIR/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                )
                m.put("file", name)
                out += m
            }
        }
        return out.sortedBy { it.optString("id", "") }
    }

    private fun fileNameOf(meta: org.json.JSONObject): String =
        meta.optString("file", "").removeSuffix(META_NAME)

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val metas = scanMeta(ctx)
        KernelHooks.report(ctx, "provision", null, "nativeDir=${nativeDir.absolutePath} exists=${nativeDir.isDirectory}")
        KernelHooks.report(ctx, "provision", null, "metaCount=${metas.size} names=${metas.joinToString(",") { it.optString("id", "") }}")
        for (m in metas) {
            val id = m.optString("id", "")
            val version = m.optString("version", "")
            if (id.isBlank() || version.isBlank()) { KernelHooks.report(ctx, "provision", null, "跳过：id 或 version 空 —— file=" + m.optString("file", "?")); continue }
            val soName = fileNameOf(m)
            val src = File(nativeDir, soName).takeIf { it.isFile }
                ?: nativeDir.let { d ->
                    metas
                        .asSequence()
                        .filter { it.optString("id", "") == id }
                        .map { fileNameOf(it) }
                        .firstOrNull { n -> n.endsWith(".so") && File(d, n).isFile }
                        ?.let { File(d, it) }
                }
                ?: continue
                KernelHooks.report(ctx, "provision", null, "跳过 id=$id ver=$version so=$soName —— nativeDir 里找不到它")
            val isEntry = ExecBits.isRunnable(src)
            val landed = landOne(ctx, id, version, soName, src, isEntry, m)
        if (landed == null) {
            KernelHooks.report(ctx, "provision", null, "落位失败 id=$id so=$soName isEntry=$isEntry size=${src.length()}")
            continue
        }
            ready += landed
        }
        linkHeadersInclude(ctx)?.let { ready += it }
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input ->
                caDst.outputStream().use { out -> input.copyTo(out) }
            }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        return ready
    }

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

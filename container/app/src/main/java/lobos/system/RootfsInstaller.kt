package lobos.system

import android.content.Context
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Installs the baked-in Ubuntu rootfs (assets/system/systemdisk.bin) into
 * app_data on first boot. The rootfs IS the system disk — not an optional
 * download — so this runs on a background thread at startup and is a hard
 * requirement (see OsApplication.onCreate).
 *
 * Source rootfs: Ubuntu 24.04 minimal cloudimg, release-20261124,
 * SHA256 e1d9307663b7f4409f71574450a5694178694e51971413e7bab52b45e1173a7c.
 */
object RootfsInstaller {

    private const val ASSET = "system/systemdisk.bin"
    private const val MARKER = ".rootfs-ready"

    fun root(ctx: Context): File = File(ctx.filesDir, "ubuntu")
    private fun marker(ctx: Context): File = File(ctx.filesDir, MARKER)

    /** True when the system disk is unpacked and marked ready. */
    fun isInstalled(ctx: Context): Boolean = marker(ctx).exists() && File(root(ctx), "bin/bash").exists()

    /**
     * Install from the bundled asset. Call off the main thread (first boot only).
     * Returns the root dir, or throws if the bundled image is missing/corrupt.
     */
    fun install(ctx: Context): File {
        if (isInstalled(ctx)) return root(ctx)
        val dest = root(ctx)
        val tmp = File(ctx.filesDir, ".ubuntu-unpacking")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        try {
            ctx.assets.open(ASSET).buffered().use { raw ->
                GzipCompressorInputStream(raw).use { gz ->
                    TarArchiveInputStream(gz).use { tar ->
                        val rootPath = tmp.canonicalFile.path
                        while (true) {
                            val e = tar.nextEntry ?: break
                            val name = e.name.removePrefix("./")
                            if (name.isEmpty()) continue
                            val out = File(tmp, name).canonicalFile
                            if (out.path != rootPath && !out.path.startsWith(rootPath + File.separator)) {
                                throw IllegalArgumentException("rootfs 条目路径越界: $name")
                            }
                            if (e.isDirectory) {
                                out.mkdirs()
                            } else if (e.isSymbolicLink) {
                                // Ubuntu uses symlinks for /bin, /sbin, /lib -> usr/*.
                                // Preserve them: turning them into empty files breaks the FHS.
                                out.parentFile?.mkdirs()
                                val linkTarget = e.linkName ?: continue
                                val resolved = File(out.parentFile, linkTarget).canonicalFile
                                val rootPath = tmp.canonicalFile.path
                                if (resolved.path != rootPath && !resolved.path.startsWith(rootPath + File.separator)) continue
                                try { java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkTarget)) } catch (_: Exception) {}
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { os -> tar.copyTo(os) }
                                if (e.mode and 0b111_000_000 != 0) out.setExecutable(true, false)
                            }
                        }
                    }
                }
            }
            if (dest.exists()) dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IllegalStateException("rootfs 落位失败")
            marker(ctx).writeText("Ubuntu 24.04 minimal release-20261124\n")
            return dest
        } catch (t: Throwable) {
            tmp.deleteRecursively()
            throw t
        }
    }
}

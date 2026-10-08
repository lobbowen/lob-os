package lobos.os

import android.content.Context
import java.io.File

object AssetInstaller {

    fun install(ctx: Context, assetPath: String, dest: File): File {
        val bytes = ctx.assets.open(assetPath).use { it.readBytes() }
        if (dest.isFile && dest.length() == bytes.size.toLong() && sameContent(dest, bytes)) {
            return dest
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw IllegalStateException("无法把 $assetPath 落地到 ${dest.absolutePath}")
        }
        return dest
    }

    fun installOrNull(ctx: Context, assetPath: String, dest: File): File? =
        runCatching { install(ctx, assetPath, dest) }.getOrNull()

    private fun sameContent(dest: File, bytes: ByteArray): Boolean =
        runCatching { dest.readBytes().contentEquals(bytes) }.getOrElse { false }
}
package lobos.kernel.compat

import android.content.Context
import java.io.File

object Compat {

    const val LIB_POSIX = "liblobosposix.so"
    const val LIB_PTYPROBE = "liblobosptyprobe.so"
    const val LIB_PTYSESSION = "librivospty.so"
    const val LIB_FLOCK = "liblobosflock.so"

    val PRELOAD_ORDER: List<String> = listOf(LIB_POSIX)

    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib(ctx)

    private fun apkLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun lib(ctx: Context, name: String): File? {
        val p = libDir(ctx).resolve(name)
        if (p.isFile) return p
        return apkLibDir(ctx).resolve(name).takeIf { it.isFile }
    }

    fun linkInto(ctx: Context): Int {
        val dst = libDir(ctx)
        if (!dst.isDirectory && !dst.mkdirs()) return 0
        var n = 0
        for (name in listOf(LIB_POSIX, LIB_PTYSESSION, LIB_FLOCK)) {
            val src = apkLibDir(ctx).resolve(name)
            if (!src.isFile) continue
            val link = dst.resolve(name)
            runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    java.nio.file.Files.deleteIfExists(link.toPath())
                }
                java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
                n++
            }
        }
        return n
    }
    fun preload(ctx: Context): String =
        PRELOAD_ORDER.mapNotNull { name -> lib(ctx, name)?.name }.joinToString(":")

    fun ptyHost(ctx: Context): File? = lib(ctx, LIB_PTYSESSION)

    fun flockLib(ctx: Context): File? = lib(ctx, LIB_FLOCK)

    fun allPresent(ctx: Context): Map<String, Boolean> =
        listOf(LIB_POSIX, LIB_PTYPROBE, LIB_PTYSESSION, LIB_FLOCK)
            .associateWith { lib(ctx, it) != null }
}

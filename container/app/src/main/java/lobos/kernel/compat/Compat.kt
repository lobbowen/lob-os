package lobos.kernel.compat

import android.content.Context
import java.io.File

object Compat {

    const val LIB_POSIX = "liblobosposix.so"
    const val LIB_PTYPROBE = "liblobosptyprobe.so"
    const val LIB_PTYSESSION = "librivospty.so"
    const val LIB_FLOCK = "liblobosflock.so"

    val PRELOAD_ORDER: List<String> = listOf(LIB_POSIX)

    fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun lib(ctx: Context, name: String): File? =
        libDir(ctx).resolve(name).takeIf { it.isFile }

    fun preload(ctx: Context): String =
        PRELOAD_ORDER.mapNotNull { name -> lib(ctx, name)?.name }.joinToString(":")

    fun ptyHost(ctx: Context): File? = lib(ctx, LIB_PTYSESSION)

    fun flockLib(ctx: Context): File? = lib(ctx, LIB_FLOCK)

    fun allPresent(ctx: Context): Map<String, Boolean> =
        listOf(LIB_POSIX, LIB_PTYPROBE, LIB_PTYSESSION, LIB_FLOCK)
            .associateWith { lib(ctx, it) != null }
}

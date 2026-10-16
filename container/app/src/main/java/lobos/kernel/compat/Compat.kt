package lobos.kernel.compat

import android.content.Context
import java.io.File

object Compat {

    const val LIB_POSIX = "liblobosposix.so"
    const val LIB_PTYPROBE = "liblobosptyprobe.so"
    const val LIB_PTYSESSION = "librivospty.so"
    const val LIB_FLOCK = "liblobosflock.so"

    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib(ctx)

    private fun apkLibDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun lib(ctx: Context, name: String): File? {
        val p = libDir(ctx).resolve(name)
        if (p.isFile) return p
        return apkLibDir(ctx).resolve(name).takeIf { it.isFile }
    }

    fun installOta(ctx: Context, name: String, bytes: ByteArray, expectedSha256: String): Boolean {
        val dst = libDir(ctx)
        if (!dst.isDirectory && !dst.mkdirs()) return false
        val tmp = dst.resolve(name + ".new")
        val got = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { b -> "%02x".format(b) }
        if (!expectedSha256.isBlank() && got != expectedSha256) {
            runCatching { tmp.delete() }
            return false
        }
        if (!runCatching {
                java.nio.file.Files.write(tmp.toPath(), bytes)
                java.nio.file.Files.move(
                    tmp.toPath(), dst.resolve(name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            }.isSuccess) {
            runCatching { tmp.delete() }
            return false
        }
        return true
    }

    fun managedComponents(): List<String> = listOf(LIB_POSIX, LIB_PTYSESSION, LIB_FLOCK)

    fun otaInstalled(ctx: Context, name: String): Boolean {
        val f = libDir(ctx).resolve(name)
        return f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())
    }

    fun linkInto(ctx: Context): Int {
        val dst = libDir(ctx)
        if (!dst.isDirectory && !dst.mkdirs()) return 0
        var n = 0
        for (name in managedComponents()) {
            val src = apkLibDir(ctx).resolve(name)
            if (!src.isFile) continue
            val link = dst.resolve(name)
            runCatching {
                if (!link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
                    n++
                } else if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                    java.nio.file.Files.deleteIfExists(link.toPath())
                    java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
                    n++
                }
            }
        }
        return n
    }

    fun ptyHost(ctx: Context): File? = lib(ctx, LIB_PTYSESSION)

    fun flockLib(ctx: Context): File? = lib(ctx, LIB_FLOCK)

    fun allPresent(ctx: Context): Map<String, Boolean> =
        listOf(LIB_POSIX, LIB_PTYPROBE, LIB_PTYSESSION, LIB_FLOCK)
            .associateWith { lib(ctx, it) != null }
}

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

    private fun permOf(ctx: Context, name: String, mode: String?): String {
        if (mode != null && mode.isNotBlank()) return mode
        val cur = libDir(ctx).resolve(name)
        val curPerm = runCatching {
            java.nio.file.Files.getPosixFilePermissions(cur.toPath())
        }.getOrNull()
        if (curPerm != null) {
            val s = java.nio.file.attribute.PosixFilePermissions.toString(curPerm)
            return if (s.contains("x")) s else s + "rwxr-xr-x".take(3)
        }
        val ap = apkLibDir(ctx).resolve(name)
        val apPerm = runCatching {
            java.nio.file.Files.getPosixFilePermissions(ap.toPath())
        }.getOrNull()
        if (apPerm != null) return java.nio.file.attribute.PosixFilePermissions.toString(apPerm)
        return "rw-r--r--"
    }

    fun installOta(ctx: Context, name: String, bytes: ByteArray, expectedSha256: String, mode: String? = null): Boolean {
        val dst = libDir(ctx)
        val expectedPerm = permOf(ctx, name, mode)
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
                java.nio.file.Files.setPosixFilePermissions(
                    tmp.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString(expectedPerm),
                )
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
            val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
            if (!isLink && link.isFile) continue
            val bytes = runCatching { src.readBytes() }.getOrNull() ?: continue
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { b -> "%02x".format(b) }
            if (isLink || !link.exists()) {
                runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
                if (installOta(ctx, name, bytes, sha, null)) n++
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

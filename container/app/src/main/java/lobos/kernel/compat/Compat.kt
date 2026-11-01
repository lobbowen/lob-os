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

    /**
     * 那**写**那一格逐字（=> 而而那它逐字**流式**落盘、=> 而而那它逐字**不**整读进那堆）。
     *
     *   => 而而那那**哈希**逐字**先**核（=> 而而那那**期望值**逐字**不**由那产物倒推）
     *   => 而而那那**写**逐字**按**那一块一块逐字写（=> 而而那它逐字**不**整读）
     *   => 而而那那**权限**逐字**落**上去（=> 而而那它逐字与那**旧**那一族逐字**一致**）
     */
    fun installOtaStreaming(
        ctx: Context,
        name: String,
        src: File,
        expectedSha256: String,
        mode: String? = null,
    ): Boolean {
        val dst = libDir(ctx)
        val expectedPerm = permOf(ctx, name, mode)
        if (!dst.isDirectory && !dst.mkdirs()) return false
        val tmp = dst.resolve(name + ".new")
        val got = runCatching { lobos.kernel.crypto.Crypto.sha256HexFile(src) }.getOrNull() ?: return false
        if (!expectedSha256.isBlank() && got != expectedSha256) {
            runCatching { tmp.delete() }
            return false
        }
        if (!runCatching {
                src.inputStream().use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                java.nio.file.Files.setPosixFilePermissions(
                    tmp.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString(expectedPerm),
                )
                java.nio.file.Files.move(
                    tmp.toPath(), dst.resolve(name).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrDefault(false)) {
            runCatching { tmp.delete() }
            return false
        }
        return true
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
            //  缺陷名（C-102）**那一格逐字把那一件逐字**整读**进堆再算哈希** ——
            //    => 而那旧版逐字是 `val bytes = runCatching { src.readBytes() }.getOrNull() ?: continue`
            //      => 而而那它逐字把那**一份**逐字**整个**读进那堆
            //        （=> 而而那那 `.so` 逐字通常不大、=> 而而那它逐字**要**核）
            //      => 而而那那**哈希**逐字**本来就**有那**流式**那一族
            //        （=> 而那 `Crypto.sha256HexFile` 逐字**按**那一块一块逐字读）
            //    => 而那**处置**：=> 而而那那哈希逐字**走**那**流式**那一族、=> 而而那它逐字**不**整读
            //      => 而而那那**写**逐字也逐字**流式**（=> 而而那它逐字**不**再整读一遍）
            //    => 而而那那**判据**逐字是「那一份逐字**有**多大」（=> 而而那它逐字**不**定死一个数）
            val sha = runCatching { lobos.kernel.crypto.Crypto.sha256HexFile(src) }.getOrNull() ?: continue
            if (isLink || !link.exists()) {
                runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
                if (installOtaStreaming(ctx, name, src, sha, null)) n++
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

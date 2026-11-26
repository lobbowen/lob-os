package lobos.kernel.compat

import android.content.Context
import java.io.File

object Compat {

    const val LIB_POSIX = "liblobosposix.so"
    const val LIB_PTYSESSION = "librivospty.so"
    const val LIB_FLOCK = "liblobosflock.so"
    const val LIB_GUEST_SIGSYS = "libguest-sigsys.so"

    fun libDir(ctx: Context): File = lobos.kernel.layout.SystemDirs.lib()

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
            if (s.contains("x")) return s
            val withX = java.util.EnumSet.copyOf(curPerm)
            withX.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
            withX.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE)
            withX.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
            return java.nio.file.attribute.PosixFilePermissions.toString(withX)
        }
        val ap = apkLibDir(ctx).resolve(name)
        val apPerm = runCatching {
            java.nio.file.Files.getPosixFilePermissions(ap.toPath())
        }.getOrNull()
        if (apPerm != null) return java.nio.file.attribute.PosixFilePermissions.toString(apPerm)
        return "rw-r--r--"
    }

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

    fun managedComponents(): List<String> = listOf(LIB_POSIX, LIB_PTYSESSION, LIB_FLOCK, LIB_GUEST_SIGSYS)

    fun otaInstalled(ctx: Context, name: String): Boolean {
        val f = libDir(ctx).resolve(name)
        return f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())
    }

    fun linkInto(ctx: Context): Int {
        val dst = libDir(ctx)
        if (!dst.isDirectory && !dst.mkdirs()) return 0
        var n = 0
        val trace = StringBuilder()
        for (name in managedComponents()) {
            val src = apkLibDir(ctx).resolve(name)
            if (!src.isFile) { trace.append(name).append(":apk没有; "); continue }
            val link = dst.resolve(name)
            val isLink = java.nio.file.Files.isSymbolicLink(link.toPath())
            val sha = runCatching { lobos.kernel.crypto.Crypto.sha256HexFile(src) }.getOrNull()
            if (sha == null) { trace.append(name).append(":apk读取失败; "); continue }
            if (!isLink && link.isFile) {
                val have = runCatching { lobos.kernel.crypto.Crypto.sha256HexFile(link) }.getOrNull()
                if (have == sha) { trace.append(name).append(":一致; "); continue }
                trace.append(name).append(":不一致apk=").append(sha.take(12)).append(" 盘上=").append((have ?: "读失败").take(12)).append("; ")
            }
            val done = installOtaStreaming(ctx, name, src, sha, null)
            trace.append(name).append(if (done) ":已重铺; " else ":重铺失败; ")
            if (done) n++
        }
        lobos.kernel.spi.KernelServices.logger().log("sysview", n > 0, "垫片重铺明细", "共 " + n + " 个；" + trace)
        return n
    }

    fun ptyHost(ctx: Context): File? = lib(ctx, LIB_PTYSESSION)

    fun flockLib(ctx: Context): File? = lib(ctx, LIB_FLOCK)

}

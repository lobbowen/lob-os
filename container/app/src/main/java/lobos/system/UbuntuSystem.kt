package lobos.system

import android.content.Context
import java.io.File

/**
 * V2 system root: an Ubuntu rootfs placed inside app_data, executed through
 * fastroot's syscall-level engine (libfr-launch.so + libfr-shim.so from
 * nativeLibraryDir). This replaces the V1 LD_PRELOAD/VFS/ELF mechanism kernel.
 *
 * Design: docs/spec/SYSTEM-ARCHITECTURE-V2.md
 * Verification: docs/findings/FASTROOT-INTEGRATION-STATUS.md
 */
object UbuntuSystem {

    /** Where the Ubuntu rootfs lives (downloaded on demand, not in APK). */
    fun root(ctx: Context): File = RootfsInstaller.root(ctx)

    /** Native engine libs: Android can only exec from nativeLibraryDir. */
    private fun nativeLib(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun launcher(ctx: Context): File = File(nativeLib(ctx), "libfr-launch.so")
    fun shim(ctx: Context): File = File(nativeLib(ctx), "libfr-shim.so")

    /** glibc loader inside the Ubuntu rootfs. */
    fun loader(root: File): File = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")

    /** Library search path handed to the loader (real paths inside the root). */
    fun libraryPath(root: File): String = listOf(
        File(root, "usr/lib/aarch64-linux-gnu").absolutePath,
        File(root, "usr/lib").absolutePath,
    ).joinToString(":")

    /** Whether the system is ready to run programs. */
    fun isReady(ctx: Context): Boolean {
        val r = root(ctx)
        return RootfsInstaller.isInstalled(ctx) &&
            r.isDirectory &&
            File(r, "bin/bash").exists() &&
            loader(r).exists() &&
            launcher(ctx).exists() &&
            shim(ctx).exists()
    }

    /** Ensure the bundled system disk is installed (first boot). */
    fun ensureReady(ctx: Context): Boolean = runCatching {
        RootfsInstaller.install(ctx)
        isReady(ctx)
    }.getOrDefault(false)

    /**
     * Build the environment for a fastroot execution. The caller puts these
     * into the ProcessBuilder / exec environment before launching [launcher]
     * with the argv built by [launchArgv].
     */
    fun environment(ctx: Context): Map<String, String> {
        val r = root(ctx)
        return mapOf(
            // Verified path: the old (non-kexec) engine. kexec needs a complete
            // FHS layout and different loader mapping; not validated yet.
            "FR_KEXEC" to "0",
            "FR_ROOT" to r.absolutePath,
            "FR_SHIM" to shim(ctx).absolutePath,
            "FR_LIBPATH" to libraryPath(r),
            // Pass through the phone's pseudo filesystems; fastroot maps the
            // rest of / from the rootfs. fastroot parses FR_BINDS with ';'
            // (patch.c: strchr(bi, ';')), NOT ':'.
            "FR_BINDS" to "/proc=/proc;/dev=/dev;/sys=/sys",
            // Standard Linux PATH so installed tools (claude/codex/node) are
            // directly runnable from the Ubuntu shell by name.
            "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "HOME" to "/root",
        )
    }

    /**
     * argv for the launcher to run a program inside Ubuntu by its VIRTUAL path
     * (e.g. "/usr/bin/apt-get", "/bin/bash"). Kept as a List so callers can
     * append program arguments without string quoting issues.
     */
    fun launchArgv(ctx: Context, program: String, programArgs: List<String>): List<String> {
        val r = root(ctx)
        val ld = loader(r)
        val libs = libraryPath(r)
        val s = shim(ctx)
        return listOf(
            launcher(ctx).absolutePath,
            ld.absolutePath,
            "--library-path", libs,
            "--preload", s.absolutePath,
        ) + listOf(File(r, program.trimStart('/')).absolutePath) + programArgs
    }

    /** FR_EXE for a program (its virtual path, used for /proc cmdline/exe). */
    fun frExe(program: String): String = if (program.startsWith("/")) program else "/$program"
}

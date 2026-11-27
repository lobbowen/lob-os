package lobos.system

import android.content.Context
import java.io.File

/**
 * Contract for the LobOS system disk.
 *
 * The system disk is the rootfs Linux programs run in. This contract owns the
 * distinction between:
 *  - the physical host path (where the bytes live),
 *  - the canonical host path (what the kernel reports via /proc/self/cwd),
 *  - the virtual path seen by guest programs ("/usr", "/root", ...).
 *
 * All FR_* environment values, virtual->host path mapping and cwd semantics
 * come from ONE implementation of this contract, so path logic is never
 * scattered across callers. Future backends (upper overlay, filesystem image)
 * implement this interface without changing the execution chain.
 */
interface SystemRoot {
    /** Physical root as Java sees it (/data/user/0/<pkg>/files/ubuntu). */
    fun hostRoot(ctx: Context): File

    /** Canonical root as the kernel reports it (/data/data/<pkg>/...). */
    fun canonicalRoot(ctx: Context): String

    /** Host path that guest programs start in (virtual "/root"). */
    fun cwdHost(ctx: Context): File

    /** Native launcher/shim (APK nativeLibraryDir, the only exec-able place). */
    fun launcher(ctx: Context): File
    fun shim(ctx: Context): File

    /** glibc loader inside the rootfs. */
    fun loader(ctx: Context): File

    /** Library search path for the loader (real paths inside root). */
    fun libraryPath(ctx: Context): String

    /** Whether the disk is installed and runnable. */
    fun isReady(ctx: Context): Boolean

    /** Install/ensure the disk (first boot). */
    fun ensureReady(ctx: Context): Boolean

    /** FR_* environment owned by the system disk. */
    fun environment(ctx: Context): Map<String, String>

    /** argv that launches [program] (virtual path) inside this disk. */
    fun launchArgv(ctx: Context, program: String, programArgs: List<String>): List<String>

    /** All FR_* keys owned by this disk (for env filtering). */
    val reservedKeys: List<String>

    companion object {
        const val VIRTUAL_CWD = "/root"
        val DEFAULT_RESERVED_KEYS: List<String> = listOf(
            "FR_ROOT", "FR_SHIM", "FR_LIBPATH", "FR_LAUNCH", "FR_KEXEC",
            "FR_EXE", "FR_BINDS", "FR_CWD",
        )
    }
}

/**
 * Current backend: the Ubuntu rootfs is an unpacked directory in app_data,
 * executed through fastroot's syscall-level path translation. No mount, no
 * chroot — the kernel root stays Android's, so FR_ROOT must be canonical.
 */
object DirectorySystemRoot : SystemRoot {
    override val reservedKeys: List<String> = SystemRoot.DEFAULT_RESERVED_KEYS

    override fun hostRoot(ctx: Context): File = RootfsInstaller.root(ctx)

    override fun canonicalRoot(ctx: Context): String =
        hostRoot(ctx).absolutePath.replace("/data/user/0/", "/data/data/")

    override fun cwdHost(ctx: Context): File = File(hostRoot(ctx), "root")

    private fun nativeLib(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)
    override fun launcher(ctx: Context): File = File(nativeLib(ctx), "libfr-launch.so")
    override fun shim(ctx: Context): File = File(nativeLib(ctx), "libfr-shim.so")

    override fun loader(ctx: Context): File =
        File(hostRoot(ctx), "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")

    override fun libraryPath(ctx: Context): String = listOf(
        File(hostRoot(ctx), "usr/lib/aarch64-linux-gnu").absolutePath,
        File(hostRoot(ctx), "usr/lib").absolutePath,
    ).joinToString(":")

    override fun isReady(ctx: Context): Boolean {
        val r = hostRoot(ctx)
        return RootfsInstaller.isInstalled(ctx) && r.isDirectory &&
            File(r, "bin/bash").exists() && loader(ctx).exists() &&
            launcher(ctx).exists() && shim(ctx).exists()
    }

    override fun ensureReady(ctx: Context): Boolean = runCatching {
        RootfsInstaller.install(ctx)
        isReady(ctx)
    }.getOrDefault(false)

    override fun environment(ctx: Context): Map<String, String> = mapOf(
        "FR_KEXEC" to "0",
        "FR_ROOT" to canonicalRoot(ctx),
        "FR_SHIM" to shim(ctx).absolutePath,
        "FR_LAUNCH" to launcher(ctx).absolutePath,
        "FR_LIBPATH" to libraryPath(ctx),
        "FR_BINDS" to "/proc=/proc;/dev=/dev;/sys=/sys",
        "FR_CWD" to cwdHost(ctx).absolutePath,
        "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "HOME" to SystemRoot.VIRTUAL_CWD,
    )

    override fun launchArgv(ctx: Context, program: String, programArgs: List<String>): List<String> =
        listOf(
            launcher(ctx).absolutePath,
            loader(ctx).absolutePath,
            "--library-path", libraryPath(ctx),
            "--preload", shim(ctx).absolutePath,
        ) + listOf(File(hostRoot(ctx), program.trimStart('/')).absolutePath) + programArgs
}

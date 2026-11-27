package lobos.android.systemdisk

import android.content.Context
import java.io.File

/**
 * Public facade for the LobOS system disk.
 *
 * All path/cwd/env semantics live in [SystemRoot] (currently [DirectorySystemRoot]);
 * this object only forwards, so callers never scatter FR_* / canonical-path logic.
 * Swapping to a future upper-overlay or image backend means changing the
 * SystemRoot implementation, not the execution chain.
 */
object UbuntuSystem {

    private val rootImpl: SystemRoot get() = DirectorySystemRoot

    val RESERVED_ENV_KEYS: List<String> get() = rootImpl.reservedKeys

    /** Physical root (filesDir/ubuntu). For file ops and path mapping. */
    fun root(ctx: Context): File = rootImpl.hostRoot(ctx)

    /** Canonical root (/data/data/...) for FR_ROOT literal-prefix matching. */
    fun fastRoot(ctx: Context): String = rootImpl.canonicalRoot(ctx)

    /** Host path of virtual "/root" (PTY guest cwd). */
    fun cwdHost(ctx: Context): File = rootImpl.cwdHost(ctx)

    fun launcher(ctx: Context): File = rootImpl.launcher(ctx)
    fun shim(ctx: Context): File = rootImpl.shim(ctx)
    fun loader(root: File): File = File(root, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
    fun libraryPath(root: File): String = listOf(
        File(root, "usr/lib/aarch64-linux-gnu").absolutePath,
        File(root, "usr/lib").absolutePath,
    ).joinToString(":")

    fun isReady(ctx: Context): Boolean = rootImpl.isReady(ctx)
    fun ensureReady(ctx: Context): Boolean = rootImpl.ensureReady(ctx)
    fun environment(ctx: Context): Map<String, String> = rootImpl.environment(ctx)

    fun launchArgv(ctx: Context, program: String, programArgs: List<String>): List<String> =
        rootImpl.launchArgv(ctx, program, programArgs)

    /** FR_EXE: virtual path used for /proc cmdline/exe. */
    fun frExe(program: String): String = if (program.startsWith("/")) program else "/$program"
}

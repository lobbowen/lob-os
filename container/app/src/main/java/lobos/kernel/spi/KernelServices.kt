package lobos.kernel.spi

import java.io.File

/**
 * Kernel-layer Service Provider Interfaces.
 *
 * These define the capabilities the kernel needs from the host / upper layers,
 * without depending on lobos.services.*. The host registers concrete adapters
 * at bootstrap; kernel code talks only to these interfaces.
 *
 * V1 ProgramQuery / ShellLookup were removed: programs come from the Ubuntu
 * rootfs via apt/npm and the terminal uses UbuntuSystem directly.
 */

/** Build the environment view for a child process. */
interface EnvProvider {
    val reservedKeys: List<String>
    fun childEnv(inheritedPath: String? = null, inheritedLd: String? = null): Map<String, String>
}

/** System paths provided by the host. */
interface PathProvider {
    val rootDir: File
    val cacheDir: File
    val nativeLibDir: File
    fun assetText(path: String): String?
}

/** Structured logging sink. */
fun interface KernelLogger {
    fun log(stage: String, ok: Boolean?, message: String, detail: String?)
}

/** Diagnostics helpers used by layout/elf checks. */
interface SystemDiagnostics {
    data class Item(val name: String, val ok: Boolean?, val title: String, val detail: String = "")
    fun selfCheck(items: List<Item>): String
    fun selfCheckVerdict(items: List<Item>): String
    fun sha256(file: File): String
}

object KernelServices {
    @Volatile private var logger: KernelLogger? = null
    @Volatile private var diagnostics: SystemDiagnostics? = null
    @Volatile private var env: EnvProvider? = null
    @Volatile private var paths: PathProvider? = null

    fun register(
        logger: KernelLogger? = null,
        diagnostics: SystemDiagnostics? = null,
        env: EnvProvider? = null,
        paths: PathProvider? = null,
    ) {
        if (logger != null) this.logger = logger
        if (diagnostics != null) this.diagnostics = diagnostics
        if (env != null) this.env = env
        if (paths != null) this.paths = paths
    }

    fun clear() {
        logger = null; diagnostics = null; env = null; paths = null
    }

    fun env(): EnvProvider = env ?: EMPTY_ENV
    fun paths(): PathProvider = paths ?: throw IllegalStateException("PathProvider not registered")
    fun logger(): KernelLogger = logger ?: NOOP_LOGGER
    fun diagnostics(): SystemDiagnostics = diagnostics ?: NOOP_DIAGNOSTICS

    private val NOOP_LOGGER = KernelLogger { _, _, _, _ -> }
    private val EMPTY_ENV = object : EnvProvider {
        override val reservedKeys = emptyList<String>()
        override fun childEnv(inheritedPath: String?, inheritedLd: String?) = emptyMap<String, String>()
    }
    private val NOOP_DIAGNOSTICS = object : SystemDiagnostics {
        override fun selfCheck(items: List<SystemDiagnostics.Item>) = ""
        override fun selfCheckVerdict(items: List<SystemDiagnostics.Item>) = ""
        override fun sha256(file: File) = ""
    }
}

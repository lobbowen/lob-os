package lobos.kernel.spi

import java.io.File

/**
 * Kernel-layer Service Provider Interfaces.
 *
 * These define the capabilities the kernel needs from the host / upper layers,
 * without depending on lobos.services.*. The host registers concrete adapters
 * at bootstrap; kernel code talks only to these interfaces.
 *
 * This is the single seam that breaks the kernel -> services reverse dependency.
 */

/** Build the environment view for a child process (replaces RuntimeEnvironment). */
interface EnvProvider {
    val reservedKeys: List<String>
    /** Full child-process environment map; caller ctx is opaque to the kernel. */
    fun childEnv(inheritedPath: String? = null, inheritedLd: String? = null): Map<String, String>
}

/** Structured logging sink (replaces kernel -> services.log.RuntimeDiagnostics). */
fun interface KernelLogger {
    fun log(stage: String, ok: Boolean?, message: String, detail: String?)
}

/** Read-only view of installed programs (replaces ProgramIndex / ProgramRegistry queries). */
interface ProgramQuery {
    data class Unit(
        val programId: String,
        val role: String,
        val version: String,
        val stateDir: String,
        val assetEntry: String,
    )
    fun all(): List<Unit>
    fun spec(programId: String): ProgramSpec?
    data class ProgramSpec(val entry: String, val version: String, val entryFile: java.io.File)
    /** Declared bin paths for a program derived from its installed shape. */
    fun declaredBins(programId: String): List<String>
}

/** Locate the active shell binary (replaces services.supply.ShellEntry). */
fun interface ShellLookup {
    fun resolve(): File?
}

/** Diagnostics helpers used by layout/elf checks (replaces SelfCheckReport / SupplySha). */
interface SystemDiagnostics {
    data class Item(val name: String, val ok: Boolean?, val title: String, val detail: String = "")
    fun selfCheck(items: List<Item>): String
    fun selfCheckVerdict(items: List<Item>): String
    fun sha256(file: File): String
}

/**
 * Aggregate service locator. Registered once by the host via KernelHooks.
 * Each field has a safe no-op default so kernel code never NPEs if a host
 * has not registered a provider yet.
 */
object KernelServices {
    @Volatile private var logger: KernelLogger? = null
    @Volatile private var programs: ProgramQuery? = null
    @Volatile private var shell: ShellLookup? = null
    @Volatile private var diagnostics: SystemDiagnostics? = null
    @Volatile private var env: EnvProvider? = null

    fun register(
        logger: KernelLogger? = null,
        programs: ProgramQuery? = null,
        shell: ShellLookup? = null,
        diagnostics: SystemDiagnostics? = null,
        env: EnvProvider? = null,
    ) {
        if (logger != null) this.logger = logger
        if (programs != null) this.programs = programs
        if (shell != null) this.shell = shell
        if (diagnostics != null) this.diagnostics = diagnostics
        if (env != null) this.env = env
    }

    fun clear() {
        logger = null; programs = null; shell = null; diagnostics = null; env = null
    }

    fun env(): EnvProvider = env ?: EMPTY_ENV

    fun logger(): KernelLogger = logger ?: NOOP_LOGGER
    fun programs(): ProgramQuery = programs ?: EMPTY_PROGRAMS
    fun shell(): ShellLookup = shell ?: EMPTY_SHELL
    fun diagnostics(): SystemDiagnostics = diagnostics ?: NOOP_DIAGNOSTICS

    private val NOOP_LOGGER = KernelLogger { _, _, _, _ -> }
    private val EMPTY_PROGRAMS = object : ProgramQuery {
        override fun all() = emptyList<ProgramQuery.Unit>()
        override fun spec(programId: String) = null
        override fun declaredBins(programId: String) = emptyList<String>()
    }
    private val EMPTY_SHELL = ShellLookup { null }
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

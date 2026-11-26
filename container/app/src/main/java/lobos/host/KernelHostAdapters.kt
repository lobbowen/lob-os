package lobos.host

import android.content.Context
import java.io.File
import lobos.kernel.spi.KernelServices
import lobos.kernel.spi.EnvProvider
import lobos.kernel.spi.ProgramQuery
import lobos.kernel.spi.ShellLookup
import lobos.kernel.spi.SystemDiagnostics
import lobos.services.log.RuntimeDiagnostics
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramRegistry
import lobos.services.reg.RuntimeEnvironment
import lobos.services.reg.UnitEntry
import lobos.services.supply.ProgramInstallPipeline
import lobos.services.supply.ShellEntry
import lobos.services.supply.SupplySha

/**
 * Host-side adapters implementing the kernel SPI. Registered once from
 * OsApplication.onCreate. This is the ONLY place where kernel SPI meets
 * services — kernel code itself never imports services.
 */
object KernelHostAdapters {

    fun register(app: Context) {
        val ctx = app.applicationContext
        KernelServices.register(
            logger = lobos.kernel.spi.KernelLogger { stage, ok, msg, detail ->
                RuntimeDiagnostics.append(ctx, stage, ok, msg, detail ?: "")
            },
            programs = object : ProgramQuery {
                override fun all(): List<ProgramQuery.Unit> = ProgramIndex.all(ctx).map { it.toUnit() }
                override fun spec(programId: String): ProgramQuery.ProgramSpec? =
                    ProgramRegistry.spec(ctx, programId)?.let {
                        ProgramQuery.ProgramSpec(it.entry, it.version, it.entryFile)
                    }
                override fun declaredBins(programId: String): List<String> {
                    val base = File(lobos.kernel.layout.SystemDirs.opt(ctx), programId)
                    val verDir = base.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
                        ?: return emptyList()
                    val shape = ProgramInstallPipeline.PieceShape.read(verDir) ?: return emptyList()
                    val bins = shape.derivedLinks.filter { it.startsWith("bin/") }
                    if (bins.isNotEmpty()) return bins
                    return shape.files.filter { it.role == ProgramInstallPipeline.ROLE_PROGRAM }
                        .map { it.path }
                }
            },
            shell = ShellLookup { ShellEntry.require(ctx) },
            diagnostics = object : SystemDiagnostics {
                override fun selfCheck(items: List<SystemDiagnostics.Item>): String =
                    lobos.services.supply.SelfCheckReport.format(items.map {
                        lobos.services.supply.SelfCheckReport.Item(it.name, it.ok, it.title, it.detail)
                    })
                override fun selfCheckVerdict(items: List<SystemDiagnostics.Item>): String =
                    lobos.services.supply.SelfCheckReport.verdict(items.map {
                        lobos.services.supply.SelfCheckReport.Item(it.name, it.ok, it.title, it.detail)
                    })
                override fun sha256(file: File): String = SupplySha.sha256(file)
            },
            env = object : EnvProvider {
                override val reservedKeys: List<String> = RuntimeEnvironment.RESERVED_ENV.toList()
                override fun childEnv(inheritedPath: String?, inheritedLd: String?): Map<String, String> =
                    RuntimeEnvironment.treeRootEnv(
                        ctx, RuntimeEnvironment.treeRootFor(ctx), inheritedPath, inheritedLd
                    )
            },
        )
    }

    private fun UnitEntry.toUnit() = ProgramQuery.Unit(
        programId = id, role = role, version = version,
        stateDir = stateDir, assetEntry = assetEntry,
    )
}

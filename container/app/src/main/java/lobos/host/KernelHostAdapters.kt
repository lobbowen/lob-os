package lobos.host

import android.content.Context
import java.io.File
import java.security.MessageDigest
import lobos.kernel.spi.KernelServices
import lobos.kernel.spi.EnvProvider
import lobos.kernel.spi.SystemDiagnostics
import lobos.services.log.RuntimeDiagnostics

/**
 * Host-side kernel SPI adapters. Registered once from OsApplication.onCreate.
 * V1 program/shell providers removed: programs come from Ubuntu rootfs via
 * apt/npm, and the terminal uses UbuntuSystem directly.
 */
object KernelHostAdapters {

    fun register(app: Context) {
        val ctx = app.applicationContext
        KernelServices.register(
            paths = object : lobos.kernel.spi.PathProvider {
                override val rootDir: File = ctx.filesDir
                override val cacheDir: File = ctx.cacheDir
                override val nativeLibDir: File = File(ctx.applicationInfo.nativeLibraryDir)
                override fun assetText(path: String): String? = runCatching {
                    ctx.assets.open(path).bufferedReader().use { it.readText() }
                }.getOrNull()
            },
            logger = lobos.kernel.spi.KernelLogger { stage, ok, msg, detail ->
                RuntimeDiagnostics.append(ctx, stage, ok, msg, detail ?: "")
            },
            diagnostics = object : SystemDiagnostics {
                override fun selfCheck(items: List<SystemDiagnostics.Item>): String =
                    items.joinToString("\n") { "${it.name}: ${it.ok} ${it.title} ${it.detail}" }
                override fun selfCheckVerdict(items: List<SystemDiagnostics.Item>): String =
                    if (items.all { it.ok != false }) "OK" else "FAIL"
                override fun sha256(file: File): String = runCatching {
                    val md = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }.getOrDefault("")
            },
            env = object : EnvProvider {
                override val reservedKeys: List<String> = listOf("FR_ROOT", "FR_SHIM", "FR_LIBPATH", "FR_KEXEC", "FR_EXE")
                override fun childEnv(inheritedPath: String?, inheritedLd: String?): Map<String, String> =
                    lobos.system.UbuntuSystem.environment(ctx)
            },
        )
    }
}

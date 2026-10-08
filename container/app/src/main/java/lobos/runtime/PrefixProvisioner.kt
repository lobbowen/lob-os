package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.pieces.PieceRegistry
import java.io.File
import lobos.os.ProgramManager

object PrefixProvisioner {

private val BINS: List<Pair<String, String>>
    get() = PieceRegistry.BINS.map { it.libName to it.installedAs }


private val DEPS: List<Pair<String, String>>
    get() = PieceRegistry.LIBS.map { it.libName to it.libName }

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"


    fun root(ctx: Context): File = lobos.os.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.os.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.os.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.os.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val plan = listOf(
            Triple(BINS, binDir(ctx), true),
            Triple(DEPS, libDir(ctx), false),
        )
        for ((items, dir, executable) in plan) {
            dir.mkdirs()
            for ((libName, name) in items) {
                val src = File(nativeDir, libName)
                val dst = File(dir, name)
                if (isManagedByUpdate(ctx, dst)) {
                    ready += name
                    continue
                }
                if (!src.isFile) { dst.delete(); continue }
                if (!dst.isFile || dst.length() != src.length()) {
                    try {
                        src.copyTo(dst, overwrite = true)
                        if (executable) ExecBits.apply(dst)
                    } catch (_: Exception) { dst.delete(); continue }
                }
                ready += name
            }
        }
        linkAppletCommands(ctx)?.let { ready += it }
        linkHeadersInclude(ctx)?.let { ready += it }
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input -> caDst.outputStream().use { out -> input.copyTo(out) } }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        registerProvisioned(ctx, plan)
        return ready
    }

    /**
     * 铺完之后登记 —— 与应用走同一条路（装完 upsert），只是源不同。
     *
     * 此前这些件只被「拷到 $PREFIX」就结束了，不进注册表。后果是系统答不出
     * 「装了什么、什么版本、哪些文件是它铺的」—— 内核只能到处硬编码它们的名字。
     *
     * **登记的位置就是落位的位置**：`usr/bin/<name>` 或 `usr/lib/<name>`，
     * 不另设 stateDir —— 注册表说的与磁盘上的必须是同一处。
     */
    private fun registerProvisioned(
        ctx: Context,
        plan: List<Triple<List<Pair<String, String>>, File, Boolean>>,
    ) {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val byLibName = (PieceRegistry.BINS + PieceRegistry.LIBS).associateBy { it.libName }
        for ((items, dir, _) in plan) {
            for ((libName, name) in items) {
                val dst = File(dir, name)
                if (!dst.isFile) continue
                val e = byLibName[libName] ?: continue
                val sha = runCatching { SupplyProvisioner.sha256HexFile(dst) }.getOrNull().orEmpty()
                val version = runCatching { InstalledRuntime.versionOf(ctx, e.id) }.getOrNull().orEmpty()
                val prev = lobos.os.ProgramIndex.get(ctx, e.id)
                if (prev != null && prev.sha256 == sha && prev.version == version) continue
                lobos.os.ProgramIndex.upsert(
                    ctx,
                    (prev ?: lobos.os.ProgramIndex.empty(
                        e.id,
                        if (lobos.os.SystemRoles.isEntry(e)) lobos.os.Level.PIECE else lobos.os.Level.PIECE,
                    )).copy(
                        version = version,
                        sha256 = sha,
                        libName = libName,
                        assetEntry = name,
                        enabled = true,
                        tier = e.buildTier,
                        stateDir = dst.parentFile?.absolutePath.orEmpty(),
                        desired = prev?.desired ?: lobos.os.Desired.RUNNING,
                    ),
                )
            }
        }
    }

    private fun isManagedByUpdate(ctx: Context, dst: File): Boolean = try {
        if (!java.nio.file.Files.isSymbolicLink(dst.toPath())) return@try false
        val target = dst.toPath().toRealPath()
        target.startsWith(libDir(ctx).toPath().toAbsolutePath())
    } catch (_: Throwable) {
        false
    }

    fun shellBin(ctx: Context): File? = lobos.os.SystemRoles.shellBin(ctx)

    private fun linkHeadersInclude(ctx: Context): List<String> {
        val headersRoot = headersIncludeDir(ctx) ?: return emptyList()
        val link = includeDir(ctx)
        return try {
            link.parentFile?.mkdirs()
            val cur = runCatching { link.toPath().toRealPath() }.getOrNull()
            if (cur != null && cur == runCatching { headersRoot.toPath().toRealPath() }.getOrNull()) {
                return listOf("include")
            }
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return emptyList()
            }
            runCatching { java.nio.file.Files.deleteIfExists(link.toPath()) }
            java.nio.file.Files.createSymbolicLink(link.toPath(), headersRoot.toPath())
            listOf("include")
        } catch (_: Exception) {
            runCatching { link.delete() }
            emptyList()
        }
    }

    fun headersIncludeDir(ctx: Context): File? {
        return try {
            val dir = SystemRoles.headersPieceDir(ctx) ?: return null
            val inc = File(dir, "include")
            if (inc.isDirectory) inc else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun linkAppletCommands(ctx: Context): List<String> {
        val bb = lobos.os.SystemRoles.multiCommandBin(ctx) ?: return emptyList()
        if (!bb.isFile) return emptyList()
        val made = mutableListOf<String>()
        for (applet in lobos.os.SystemRoles.appletsOf(ctx)) {
            val link = File(binDir(ctx), applet)
            try {
                if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) continue
                java.nio.file.Files.deleteIfExists(link.toPath())
                java.nio.file.Files.createSymbolicLink(link.toPath(), bb.toPath())
                made += applet
            } catch (_: Exception) {
            }
        }
        return made
    }

    fun multiCommandBin(ctx: Context): File? = lobos.os.SystemRoles.multiCommandBin(ctx)

    fun expected(ctx: Context): List<String> =
        BINS.map { it.second } + DEPS.map { it.second } + CA_BUNDLE_NAME
}

package lobos.runtime

import android.content.Context
import android.system.Os
import lobos.pieces.PieceRegistry
import java.io.File
import lobos.os.ProgramManager

object PrefixProvisioner {

const val CA_BUNDLE_NAME = "ca-bundle.pem"
private const val CA_BUNDLE_ASSET = "ca-bundle.pem"


    fun root(ctx: Context): File = lobos.os.SystemDirs.usr(ctx)
    fun binDir(ctx: Context): File = lobos.os.SystemDirs.bin(ctx)
    fun libDir(ctx: Context): File = lobos.os.SystemDirs.lib(ctx)

    fun includeDir(ctx: Context): File = lobos.os.SystemDirs.include(ctx)

    fun caBundleAt(root: File): File = File(root, CA_BUNDLE_NAME)

    fun caBundle(ctx: Context): File = caBundleAt(root(ctx))

    /**
     * 铺一件 —— **落位形状照抄 Linux**。
     *
     *   usr/lib/<id>/<版本>/bin/<名字>   命令本体（同一件的多个版本各占一个目录）
     *   usr/lib/<id>/<版本>/lib<名字>.so  库本体
     *   usr/bin/<名字>                 → 软链到上面那个（PATH 里的全局入口）
     *
     * 为什么要多一层 `<版本>/`：同一件可以并存多个版本，切换只改/usr/bin 那个软链 ——
     * 这正是 `ldconfig` 对 `libfoo.so → .so.1 → .so.1.12` 做的事。
     *
     * 落位形状自带身份：`PieceScan` 扫目录就知道有什么件、什么版本、入口在哪、
     * 什么形态（有 bin/ 是命令 · 只有 .so 是库）—— 不需要任何一张表。
     */
    private fun landOne(
        ctx: Context,
        id: String,
        version: String,
        libName: String,
        installedAs: String,
        isEntry: Boolean,
        nativeDir: File,
    ): String? {
        val verDir = lobos.os.SystemDirs.pieceDir(ctx, id, version)
        verDir.mkdirs()
        val fileName = if (isEntry) "bin/$installedAs" else "lib/$libName"
        val dst = File(verDir, fileName)
        val src = File(nativeDir, libName)
        if (!src.isFile) return null
        if (!dst.isFile || dst.length() != src.length()) {
            dst.parentFile?.mkdirs()
            try {
                src.copyTo(dst, overwrite = true)
                if (isEntry) ExecBits.apply(dst)
            } catch (_: Exception) {
                dst.delete()
                return null
            }
        }
        // 全局入口：命令在 usr/bin 建软链；库不用（usr/lib 已在库搜索路径里）
        if (isEntry) {
            val link = lobos.os.SystemDirs.bin(ctx).let { File(it, installedAs) }
            link.parentFile?.mkdirs()
            if (link.exists() && !java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            java.nio.file.Files.deleteIfExists(link.toPath())
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                dst.toPath().toAbsolutePath().normalize(),
            )
        }
        return dst.absolutePath
    }

    fun provision(ctx: Context): List<String> {
        val ready = mutableListOf<String>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        for (p in PieceRegistry.ALL) {
            // 版本以它自己声明的为准；没声明的用 jniLibs 文件的内容指纹兜底 ——
            // 落位目录名就是版本，PieceScan 靠它识别
            val v = p.version.ifBlank { Fingerprint.of(File(nativeDir, p.libName)) }
            if (v.isBlank()) continue
            val landed = landOne(
                ctx, p.id, v, p.libName, p.installedAs,
                lobos.os.SystemRoles.isEntry(p), File(nativeDir),
            ) ?: continue
            ready += landed
        }
        linkHeadersInclude(ctx)?.let { ready += it }
        val caDst = caBundle(ctx)
        try {
            caDst.parentFile?.mkdirs()
            ctx.assets.open(CA_BUNDLE_ASSET).use { input -> caDst.outputStream().use { out -> input.copyTo(out) } }
            ready += CA_BUNDLE_NAME
        } catch (_: Exception) { caDst.delete() }
        registerProvisioned(ctx)
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
    /**
     * 铺完之后登记 —— 数据来自**扫落位**（[lobos.os.PieceScan]），不来自任何表。
     *
     * 铺位的形态就是身份：有bin/ 是命令 · 只有 .so 是库 · 有 include/ 是头文件集；
     * 目录名是版本。扫一遍就得到注册表要的全部字段。
     */
    private fun registerProvisioned(ctx: Context) {
        for (f in lobos.os.PieceScan.scan(ctx)) {
            val prev = lobos.os.ProgramIndex.get(ctx, f.id)
            if (prev != null && prev.version == f.version && prev.stateDir == f.dir.absolutePath) {
                continue
            }
            lobos.os.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.os.ProgramIndex.empty(f.id, lobos.os.Level.PIECE)).copy(
                    version = f.version,
                    stateDir = f.dir.absolutePath,
                    assetEntry = f.entry,
                    role = f.role,
                    sha256 = f.sha256,
                ),
            )
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


    fun multiCommandBin(ctx: Context): File? = lobos.os.SystemRoles.multiCommandBin(ctx)

    fun expected(ctx: Context): List<String> =
        BINS.map { it.second } + DEPS.map { it.second } + CA_BUNDLE_NAME
}

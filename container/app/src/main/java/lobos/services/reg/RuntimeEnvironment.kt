package lobos.services.reg

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.app.DriverRegistry
import lobos.services.app.PieceProvisioner
import lobos.kernel.layout.PrefixProvisioner
import lobos.services.supply.CatalogClient
import lobos.services.supply.PackageInstaller
import lobos.services.supply.PieceScan
import lobos.services.supply.ShellEntry
import lobos.kernel.mm.LibPath

object RuntimeEnvironment {

    data class Snapshot(
        val prefixReady: List<String>,
        val prefixMissing: List<String>,
    ) {
        val complete: Boolean get() = prefixMissing.isEmpty()
    }

    @Volatile private var cached: Snapshot? = null

    data class TreeRoot(
        val home: File,
        val tmpDir: File,
        val nativeLibDir: String,
        val prefixRoot: File,
        val prefixBin: File,
    val prefixLib: File,
    )

    val RESERVED_ENV: Set<String> = setOf(
        "HOME", "TMPDIR", "PATH", "LANG", "SHELL", "LD_LIBRARY_PATH", "LD_PRELOAD",
        "SSL_CERT_DIR", "SSL_CERT_FILE", "PREFIX",
        "CURL_CA_BUNDLE", "GIT_SSL_CAINFO", "LOBOS_COMPAT_LOG", "LOBOS_BRIDGE_SOCKET",
        "LOBOS_SESSION_TOKEN", "LOBOS_PROGRAM_ID", "LOBOS_PROGRAM_GENERATION",
        "LOBOS_ANDROID", "LOBOS_PLATFORM", "LOBOS_SUPERVISOR_HOME", "LOBOS_UI_DIR",
        "LOBOS_PERMISSION_MODE", "LOBOS_FLOCK_SO", "LOBOS_OWN_SESSION",
    )

    fun withoutReserved(declared: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val dropped = declared.keys.filter { RESERVED_ENV.contains(it) }.sorted()
        return declared.filterKeys { !RESERVED_ENV.contains(it) } to dropped
    }

    fun treeRootEnv(ctx: Context, root: TreeRoot, inheritedPath: String?, inheritedLd: String? = null): Map<String, String> = buildMap {
        put("HOME", root.home.absolutePath)
        put("TMPDIR", root.tmpDir.absolutePath)
        put("LANG", "C.UTF-8")
        //  缺陷名（C-102）**那 PREFIX 那一族逐字没有给** ——
        //    => 而而它治**的**是「那**运行时**逐字**告诉**那**目标**它**自己**在**哪**一层**前缀**下面**」。
        //    => 而那**成因**：=> 而那 cmake **那一件**逐字**读**的**是**那 $ENV{PREFIX}/include/android/api-level.h
        //       （=> 而那 CMakeDetermineSystem.cmake :40 **那一行**）、=> 而而那**它**逐字**要**那 PREFIX **那一个**变量**。
        //    => 而那**后果**逐字**是**：=> 而那 cmake configure 逐字**报**那「file failed to open for reading」
        //       （=> 而那真机读数：=> 而那 CMake Error at …/Modules/CMakeDetermineSystem.cmake:40 (file)）。
        //    => 而那**处置**：=> 而那 PREFIX 逐字**指向**那**前缀**那一层（=> 而那 root.prefixRoot）。
        //    => 而按 C-104：=> 而那**运行时**给**孩子**的**那** env **快照**逐字**只有**一条路（=> 而这一支**就是**它**）。
        put("PREFIX", root.prefixRoot.absolutePath)
        //  缺陷名 缺陷名（C-102）那**库**的**搜索次序**逐字**没**算**那件自己的**那**一层**
        //    => 而而它治**的**是「那**运行时**逐字**加载**的**是**哪**一个** libcrypto / libc++_shared.so」。
        //    => 而而它**不**是「那加载器坏了」、=> 而而它**不**是「那 DT_RUNPATH 要改」。
        //
        //    而那**两**个**读数**逐字**是**那**同一个**形状**（=> 而那**两者**逐字**不是**那**同一个**符号**）：
        //      一：那 openssl —— 而那**运行**时逐字**先**找到**那宿主**那**/system/lib64/libcrypto.so，
        //         而而那**宿主**那一份逐字**没有**那 **OSSL_CMP_ITAV_dup**，
        //         而而那**我们**的**那两份** libcrypto 逐字**都**有那**符号**。
        //      二：那 node —— 而那 libnode.so 的 RUNPATH 逐字 = **$ORIGIN**，
        //         而那 $ORIGIN 逐字**是**那 **bin** **那一**层，
        //         而而那 libc++_shared.so 逐字**在**那 **lib** **那一**层。
        //    => 而而那**两**个**成因**逐字**同**一个：
        //       **那加载器**逐字**只**在**那几条路径**上找、=> 而而那**件自己的**那**一层**逐字**不**在**那几条路径**上**。
        //    => 而而那**差别**逐字**是**那**库**在哪**那一层**（=> 而那 openssl 的**那一份**在**那 usr/lib**、
        //       而那 node 的**那一份**在**那件自己的**那 **lib/** **那一层**）。
        //
        //    => 而那**处置**逐字**是**：那**树根的库目录**逐字**排**在**那一条路径**的**前面**，
        //       而且**每一件**自己**那** lib/ **那一层**也**排**在**那一条路径**上（=> 而那**落位事实**逐字**是**这样**的**）。
        //    => 而那**程序自己声明**的**那一项**逐字**仍然不许生效**（=> 而它归那 RESERVED_ENV **那一族**管、
        //       => 而而**不**能靠「让程序声明自己那一项」来**修**）。
        //    => 而按 C-92：而那**具体**的**名字**（LD_LIBRARY_PATH）**删掉**了、
        //       而这一行逐字**还**剩「那树根的库目录排在那一条路径上」这个**形状**。
        put("LD_LIBRARY_PATH", joinPath(root.prefixLib.absolutePath, root.nativeLibDir, pieceLibDirs(ctx), inheritedLd))
        put(
            "PATH",
            joinPath(
                root.prefixBin.absolutePath,
                InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.parentFile?.absolutePath,
                inheritedPath,
            )
        )
        val posix = lobos.kernel.compat.Compat.lib(ctx, lobos.kernel.compat.Compat.LIB_POSIX)
        if (posix != null) {
            put("LD_PRELOAD", posix.absolutePath)
            put("LOBOS_SYSTEM_LINKER", "/system/bin/linker64")
            put("LOBOS_COMPAT_LOG", lobos.services.app.DriverRegistry.degradeLog(ctx).absolutePath)
        }
        val caDirs = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts",
            "/data/misc/keychain/cacerts-added",
        ).filter { File(it).isDirectory }
        if (caDirs.isNotEmpty()) put("SSL_CERT_DIR", caDirs.joinToString(":"))
        val caBundle = PrefixProvisioner.caBundleAt(root.prefixRoot)
        if (caBundle.isFile) {
            put("SSL_CERT_FILE", caBundle.absolutePath)
            put("CURL_CA_BUNDLE", caBundle.absolutePath)
            put("GIT_SSL_CAINFO", caBundle.absolutePath)
        }
        ShellEntry.resolve(ctx)?.let { put("SHELL", it.absolutePath) }
    }

    fun joinPath(vararg parts: String?): String =
        parts.filterNotNull()
            .flatMap { it.split(File.pathSeparator) }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(File.pathSeparator)

    fun treeRootFor(ctx: Context): TreeRoot = TreeRoot(
        home = ctx.filesDir,
        tmpDir = ctx.cacheDir,
        nativeLibDir = libSearchPath(ctx),
        prefixRoot = PrefixProvisioner.root(ctx),
        prefixBin = PrefixProvisioner.binDir(ctx),
        prefixLib = PrefixProvisioner.libDir(ctx),
    )

    fun libSearchPath(ctx: Context): String {
        val dirs = LinkedHashSet<String>()
        dirs.add(LibPath.searchPath(ctx))
        return dirs.filter { File(it).isDirectory }.joinToString(":")
      }
    /** 那一件自己**那** lib/ **那一层**（=> 而那**落位事实**：每件的库**在**它**自己的**那**一层**里**）。
     *
     *  => 而那**它**逐字**是**那**库**的**搜索次序**那一族**的**另一半**：
     *     而那 usr/lib **那一层**逐字**收**的是**那件**的**库**的**软链**，
     *     而而那**链接器**逐字**只**找**那**软链**指**的**那**一份**、=> 而那**软链**逐字**指**到**那件自己的**那**一层**。
     *     => 而那 node 那一件逐字**没有**那**软链**（=> 而那 libc++_shared.so 逐字**不**在**那 usr/lib** **底下**），
     *        所以而它逐字**要**自己**那**一层**逐字**在**那一条路径**上**。
     *  => 而那**它**逐字**不**管「那件的依赖满不满足」（=> 而那是**那落位侧**的**活**、=> 而那 SYSTEM_LIB_DIRS **不动**）。
     */
    fun pieceLibDirs(ctx: Context): String {
        val opt = File(ctx.filesDir, "opt")
        val dirs = LinkedHashSet<String>()
        for (idDir in opt.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!idDir.isDirectory) continue
            for (verDir in idDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (!verDir.isDirectory) continue
                val lib = File(verDir, "lib")
                if (lib.isDirectory) dirs.add(lib.absolutePath)
            }
        }
        return dirs.joinToString(":")
    }

    fun ensure(ctx: Context): Snapshot {
        cached?.takeIf { it.complete }?.let { s ->
            RuntimeDiagnostics.append(
                ctx, "prefix", true, "\$PREFIX 件全部就位（本进程已装配）",
                PrefixProvisioner.root(ctx).absolutePath + " 已有=" + s.prefixReady.joinToString()
            )
            return s
        }
        return synchronized(this) {
            cached?.takeIf { it.complete }?.let { return it }
            assemble(ctx).also { if (it.complete) cached = it }
        }
    }

    private fun isLink(f: File): Boolean = runCatching {
        val mode = android.system.Os.lstat(f.path).st_mode
        (mode and S_IFMT) == S_IFLNK
    }.getOrDefault(false)

    private const val S_IFMT = 0xF000
    private const val S_IFLNK = 0xA000

    private fun assemble(ctx: Context): Snapshot {
        val provisioned = PrefixProvisioner.provision(ctx)
        val found = PieceScan.scan(ctx).filter { it.role != PieceScan.HEADERS }
        val landedIds = found.map { f -> f.id }.toSet()
        val packagedIds = PieceScan.packagedIds(ctx)
        val absent = packagedIds - landedIds
        val exeFiles = found
            .filter { File(it.dir, "bin").isDirectory }
            .flatMap { File(it.dir, "bin").listFiles()?.filter { f -> f.isFile } ?: emptyList() }
            .toSet()
        val missing = exeFiles.filter { f -> !f.canExecute() }.map { it.name }
        RuntimeDiagnostics.append(
            ctx, "prefix", absent.isEmpty() && missing.isEmpty(),
            if (absent.isEmpty() && missing.isEmpty())
                "\$PREFIX 件全部就位（" + packagedIds.size + " 件）"
            else "\$PREFIX 缺件：" + (absent + missing).joinToString(","),
            "落位根=" + lobos.kernel.layout.SystemDirs.opt(ctx).absolutePath +
                "  已落位=" + landedIds.size + "（" + landedIds.joinToString(",") + "）" +
                "  包里有=" + packagedIds.size + "（" + packagedIds.joinToString(",") + "）" +
                "  provision=" + provisioned.joinToString(",")
        )
        val prev = cached
        return Snapshot(prev?.prefixReady ?: landedIds.toList(), prev?.prefixMissing ?: emptyList())
    }
}

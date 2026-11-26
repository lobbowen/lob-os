package lobos.services.reg

import lobos.kernel.layout.SystemDirs
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

        put("TERM", "xterm")

        put("PREFIX", root.prefixRoot.absolutePath)
        // 用户态虚拟根：程序看到标准 Linux 路径(/usr /lib /etc /tmp)，
        // libc 路径 hook 按 LOBOS_VROOT 一次性映射到沙箱真实位置。
        put("LOBOS_VROOT", ctx.filesDir.absolutePath)

        val pythonVer = runCatching {
            lobos.services.supply.ProgramDir(ctx, "python").currentVersion()
        }.getOrNull()
        if (!pythonVer.isNullOrBlank()) {
            val home = java.io.File(java.io.File(SystemDirs.opt(), "python"), pythonVer)
            if (home.isDirectory) put("PYTHONHOME", home.absolutePath)
        }

        put("LD_LIBRARY_PATH", joinPath(pieceLibDirs(ctx), root.prefixLib.absolutePath, root.nativeLibDir, inheritedLd))

        // glibc rseq tunable：Android untrusted_app seccomp 用 SIGSYS 拦 rseq(293)，
        // glibc 2.35+ 启动必调 rseq 而被杀。GLIBC_TUNABLES 让 glibc 跳过 rseq 注册
        // （AT_SECURE=0 时有效），比 SIGSYS handler 更干净。
        put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")

        // exec-path hook 路径：hook 每次把 bionic 程序改写成 linker64 启动时，
        // 用它在 envp 里补 LD_PRELOAD，让兼容层语义链式传到子进程（含交互 shell）。
        val hookSo = java.io.File(ctx.filesDir, "usr/lib/liblobosposix.so")
        if (hookSo.isFile) {
            put("LOBOS_HOOK_SO", hookSo.absolutePath)
            val nativeBash = java.io.File(ctx.applicationInfo.nativeLibraryDir, "liblobosbash.so")
            if (nativeBash.isFile) put("LOBOS_PTY_SHELL", nativeBash.absolutePath)
        }

        // glibc guest 运行时：把当前版本的 loader/lib 递给 ExecEntry，
        // 让 glibc ELF 经 guest loader 启动（单用户系统，无需 fake root）。
        val grVer = runCatching {
            lobos.services.supply.ProgramDir(ctx, "glibc-runtime").currentVersion()
        }.getOrNull()
        if (!grVer.isNullOrBlank()) {
            val gr = java.io.File(java.io.File(SystemDirs.opt(), "glibc-runtime"), grVer)
            // loader 必须在 app 域可 exec 的位置：app_data_file 上的 ELF 被 SELinux
            // 拒 exec（EACCES），APK 的 nativeLibraryDir 可 exec。loader 作为
            // prebuilt native lib（libglibc-loader.so）随 APK 分发。
            // nativeLib 可 exec（系统 ELF 位置）；内容是官方原版 loader。
            // 组件里的副本只作备份（app_data_file 不能直接 exec）。
            val nativeLoader = java.io.File(ctx.applicationInfo.nativeLibraryDir, "libglibc-loader.so")
            val componentLoader = java.io.File(java.io.File(gr, "lib"), "ld-linux-aarch64.so.1")
            val loader = if (nativeLoader.isFile) nativeLoader else componentLoader
            if (loader.isFile) {
                put("LOBOS_GLIBC_LOADER", loader.absolutePath)
                put("LOBOS_GLIBC_LIBDIR", java.io.File(gr, "lib").absolutePath)
                // glibc guest 的 SIGSYS handler（freestanding so）：Android seccomp
                // 用 SIGSYS 拦 rseq(293) 等 syscall，没它 glibc 程序启动即 SIGSYS(31)。
                // SIGSYS handler so：优先用组件安装到 files/opt 的版本（app_data_file
                // 可读可 dlopen），fallback 到 nativeLibraryDir。glibc loader 在
                // untrusted_app 域 dlopen nativeLibraryDir 的 so 可能被 SELinux 拒。
                val sigsysSo = java.io.File(ctx.filesDir, "usr/lib/libguest-sigsys.so")
                    .takeIf { it.isFile }
                    ?: java.io.File(ctx.applicationInfo.nativeLibraryDir, "libguest-sigsys.so")
                if (sigsysSo.isFile) put("LOBOS_GUEST_SIGSYS", sigsysSo.absolutePath)
                /* glibc-flavoured VFS hook: bionic liblobosposix.so cannot be
                   loaded by glibc guests, so guest path syscalls need their own. */
                val vfsSo = java.io.File(ctx.applicationInfo.nativeLibraryDir, "liblobos-vfs-glibc.so")
                if (vfsSo.isFile) put("LOBOS_VFS_SO", vfsSo.absolutePath)
                lobos.kernel.proc.ExecEntry.configureGlibcGuest(
                    loader.absolutePath, java.io.File(gr, "lib").absolutePath
                )
            }
        }

        put(
            "PATH",
            joinPath(
                root.prefixBin.absolutePath,
                InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.parentFile?.absolutePath,
                inheritedPath,
            )
        )
        val posix = lobos.kernel.compat.Compat.lib(lobos.kernel.compat.Compat.LIB_POSIX)
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
        prefixRoot = PrefixProvisioner.root(),
        prefixBin = PrefixProvisioner.binDir(),
        prefixLib = PrefixProvisioner.libDir(),
    )

    fun libSearchPath(ctx: Context): String {
        val dirs = LinkedHashSet<String>()
        dirs.add(LibPath.searchPath())
        return dirs.filter { File(it).isDirectory }.joinToString(":")
      }

    fun pieceLibDirs(ctx: Context): String {
        val opt = File(ctx.filesDir, "opt")
        val dirs = LinkedHashSet<String>()
        for (idDir in opt.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!idDir.isDirectory) continue
            // glibc-runtime 的 lib/ 只给 guest loader 经 --library-path 用；
            // 进全局 LD_LIBRARY_PATH 会让 bionic 程序误链 glibc 的 libdl/libc 而崩。
            if (idDir.name == "glibc-runtime") continue
            for (verDir in idDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (!verDir.isDirectory) continue
                val lib = File(verDir, "lib")
                if (lib.isDirectory) dirs.add(lib.absolutePath)
                val bin = File(verDir, "bin")
                if (bin.isDirectory) dirs.add(bin.absolutePath)
            }
        }
        return dirs.joinToString(":")
    }

    fun ensure(ctx: Context): Snapshot {
        cached?.takeIf { it.complete }?.let { s ->
            RuntimeDiagnostics.append(
                ctx, "prefix", true, "\$PREFIX 件全部就位（本进程已装配）",
                PrefixProvisioner.root().absolutePath + " 已有=" + s.prefixReady.joinToString()
            )
            return s
        }
        return synchronized(this) {
            cached?.takeIf { it.complete }?.let { return it }
            assemble(ctx).also { if (it.complete) cached = it }
        }
    }

    private fun assemble(ctx: Context): Snapshot {
        val provisioned = PrefixProvisioner.provision()

        val withContentFingerprint = ScanCost.withContentFingerprint(ctx)
        val found = PieceScan.scan(ctx, withContentFingerprint).filter { it.role != PieceScan.HEADERS }

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
            "落位根=" + lobos.kernel.layout.SystemDirs.opt().absolutePath +
                "  已落位=" + landedIds.size + "（" + landedIds.joinToString(",") + "）" +
                "  包里有=" + packagedIds.size + "（" + packagedIds.joinToString(",") + "）" +
                "  provision=" + provisioned.joinToString(",")
        )
        val prev = cached
        return Snapshot(prev?.prefixReady ?: landedIds.toList(), prev?.prefixMissing ?: emptyList())
    }
}

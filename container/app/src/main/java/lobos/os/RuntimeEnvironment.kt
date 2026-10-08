package lobos.os

import android.content.Context
import lobos.RuntimeDiagnostics
import lobos.pieces.PieceProvisioner
import lobos.runtime.InstalledRuntime
import lobos.runtime.PrefixProvisioner
import java.io.File

object RuntimeEnvironment {

    data class Snapshot(
        val prefixReady: List<String>,
        val prefixMissing: List<String>,
    ) {
        val complete: Boolean get() = prefixMissing.isEmpty()
    }

    @Volatile private var cached: Snapshot? = null
    @Volatile private var lastSupplyAt = 0L
    private val supplyThrottleMs = 10 * 60 * 1000L

    /**
     * 进程环境 —— **只含系统面，不含任何具体的件**。
     *
     * 此前这里有 nodeBin / shellBin / posixShim 三个字段，那是把「某一件」
     * 写进了环境数据结构：换一件命令解释器或运行时就得改这个 data class。
     * 现在它们由 [PieceScan] 按角色提供 —— 要用就问它，别在环境里存一份。
     */
    data class TreeRoot(
        val home: File,
        val tmpDir: File,
        val nativeLibDir: String,
        val prefixRoot: File,
        val prefixBin: File,
    )

    val RESERVED_ENV: Set<String> = setOf(
        "HOME", "TMPDIR", "PATH", "LANG", "SHELL", "LD_LIBRARY_PATH", "LD_PRELOAD",
        "NODE_BIN", "NODE_PATH", "NODE_OPTIONS", "SSL_CERT_DIR", "SSL_CERT_FILE",
        "CURL_CA_BUNDLE", "GIT_SSL_CAINFO", "LOBOS_COMPAT_LOG", "LOBOS_BRIDGE_SOCKET",
        "LOBOS_SESSION_TOKEN", "LOBOS_PROGRAM_ID", "LOBOS_PROGRAM_GENERATION",
        "LOBOS_ANDROID", "LOBOS_PLATFORM", "LOBOS_SUPERVISOR_HOME", "LOBOS_UI_DIR",
        "LOBOS_PERMISSION_MODE", "LOBOS_FLOCK_SO", "LOBOS_OWN_SESSION",
    )

    fun withoutReserved(declared: Map<String, String>): Pair<Map<String, String>, List<String>> {
        val dropped = declared.keys.filter { RESERVED_ENV.contains(it) }.sorted()
        return declared.filterKeys { !RESERVED_ENV.contains(it) } to dropped
    }

    fun treeRootEnv(root: TreeRoot, inheritedPath: String?): Map<String, String> = buildMap {
        put("HOME", root.home.absolutePath)
        put("TMPDIR", root.tmpDir.absolutePath)
        put("LANG", "C.UTF-8")
        put("LD_LIBRARY_PATH", root.nativeLibDir)
        InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.let { put("NODE_BIN", it.absolutePath) }
        put(
            "PATH",
            joinPath(
                root.prefixBin.absolutePath,
                InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)?.parentFile?.absolutePath,
                inheritedPath,
            )
        )
        lobos.os.PieceScan.pieceFile(ctx, "posix")?.let {
            put("LD_PRELOAD", it.absolutePath)
            put("LOBOS_COMPAT_LOG", lobos.pieces.DriverRegistry.degradeLog(ctx).absolutePath)
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
        put("SHELL", lobos.os.PieceScan.shellBin(ctx)?.absolutePath ?: "/system/bin/sh")
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
    )

      /**
       * 库的搜索路径 —— 全局共享的一份，**不给每个程序单独配**。
       *
       * · usr/lib                    件的全局软链都在那儿（每件一条）
       * · 各程序的 opt/<id>/<版本>/lib  程序自带的依赖
       *
       * ★ 不含 nativeLibraryDir：实测那目录不可写（属主 system，我们是应用 uid），
       *   「把系统建在 APK 目录下」这条路走不通 —— 已堵死，别再走。
       * ★ 更好的做法是链接期给 DT_RUNPATH=\\$ORIGIN：那样库跟着文件走，
       *   连这个环境变量都不必依赖。这里的路径是兜底（给没有 RUNPATH 的件）。
       */
      fun libSearchPath(ctx: Context): String {
          val dirs = LinkedHashSet<String>()
          dirs.add(PieceProvisioner.libSearchPath(ctx))
          runCatching {
              ProgramRegistry.listIds(ctx).forEach { id ->
                  val root = ProgramManager.stateDirOf(ctx, id)
                  dirs.add(File(root, "lib").absolutePath)
              }
          }
          return dirs.filter { File(it).isDirectory }.joinToString(":")
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
            cached?.takeIf { it.complete } ?: assemble(ctx).also { cached = it }
        }
    }

    private fun assemble(ctx: Context): Snapshot {
        val ready = PrefixProvisioner.provision(ctx)
        // 该有的全局入口 = 落位里有 bin/ 的那些（扫落位，不问内核预置的清单）
        val expected = lobos.os.PieceScan.scan(ctx)
            .filter { File(it.dir, "bin").isDirectory }
            .map { File(it.dir, "bin").listFiles()?.filter { f -> f.isFile || f.isSymbolicLink }?.map { f -> f.name }.orEmpty() }
            .flatten()
            .toSet()
        val missing = expected - ready.toSet()
        RuntimeDiagnostics.append(
            ctx, "prefix", missing.isEmpty(),
            if (missing.isEmpty()) "\$PREFIX 件全部就位" else "\$PREFIX 缺件：${missing.joinToString()}",
            PrefixProvisioner.root(ctx).absolutePath + " 已有=" + ready.joinToString()
        )

        RuntimeDiagnostics.append(
        )

        val nowSupply = System.currentTimeMillis()
        if (nowSupply - lastSupplyAt > supplyThrottleMs) {
            lastSupplyAt = nowSupply
            RuntimeDiagnostics.append(
                ctx, "supply", null, "商店供给不再由 App 启动自动安装",
                "启动只刷商店目录（CatalogClient.refresh）。" +
                    "运行时与工具件由「装程序时按该程序 requires 决定」触发，走 os/PackageInstaller。",
            )
        }

    }
}

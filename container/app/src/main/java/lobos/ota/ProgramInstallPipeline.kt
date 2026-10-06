package lobos.ota

import android.content.Context
import lobos.os.ProgramDir
import org.json.JSONObject
import java.io.File

/**
 * 唯一的安装实现。
 *
 * 两条来源的路（应用商店 / 内置件 OTA）都调这里，差别只在 [Spec.from] 与包从哪来，
 * 不在安装行为本身。原先两边各写一套安装器，能力互相缺失：
 *
 * | 能力                | 原 ota/ProgramInstaller | 原 os/PackageInstaller |
 * |---------------------|-------------------------|------------------------|
 * | 包内清单签名校验     | 有（ProgramVerifier）    | 无（只校验 zip sha256） |
 * | 12 道落位后判据      | 有                       | 无                     |
 * | 后端平铺 + 入口改写   | 有                       | 无                     |
 * | 写注册表 ProgramIndex | 无                       | 有                     |
 * | 留审计 Journal       | 无                       | 有                     |
 * | 快应用配对           | 有                       | 无（装得上但打不开）     |
 *
 * 合并时以「两侧能力并集」为准，任一能力都不得丢。
 */
object ProgramInstallPipeline {

    /** 包从哪来。只影响日志与注册表 origin，不影响安装行为。 */
    enum class From(val label: String, val origin: String) {
        STORE("应用商店", "store"),
        BUILTIN("内置件 OTA", "ota"),
    }

    /**
     * 装什么。
     *
     * @param zip 已经下载到本地的候选包。下载归两条路各自负责（商店侧走目录项 url，
     *   内置件侧走清单 url），这里只管"拿到包之后怎么装"。
     * @param programId 安装目标。两条路都必须显式给出，内核不猜。
     * @param manifestText 包外的清单原文（若有）。有则参与签名校验与版本取用。
     * @param manifestFile 已落盘的清单文件（若有）。复用调用方暂存的那份，
     *   避免同一份清单在两处各写一次。
     * @param expectedVersion 期望版本；与包内声明不一致则拒装。
     * @param storeRoot 覆盖落位根目录（测试用）；null 则走注册表里的标准位置。
     * @param shape 包型。应用程序带 program-manifest.json、有前后端之分、要平铺与
     *   配对；系统组件（运行时/工具）是一整个可执行目录，没有这些。
     *   两者落位规则本就不同，这里显式区分，不硬套。
     */
    data class Spec(
        val from: From,
        val programId: String,
        val zip: File,
        val shape: Shape = Shape.APPLICATION,
        val manifestText: String? = null,
        val manifestFile: File? = null,
        val expectedVersion: String? = null,
        val expectedEntryRel: String? = null,
        val storeRoot: File? = null,
    )

    enum class Shape { APPLICATION, COMPONENT }

    fun install(context: Context, spec: Spec): Result {
        if (spec.programId.isBlank()) {
            return Result(
                false, null, "manifest-id-missing",
                "包清单未声明 id/name：内核不猜安装目标",
            )
        }
        if (!spec.zip.isFile) {
            return Result(false, null, "zip-missing", "候选包不存在: ${spec.zip.absolutePath}")
        }
        if (spec.zip.length() == 0L) {
            return Result(false, null, "zip-empty", "候选包是空文件: ${spec.zip.absolutePath}")
        }

        val manifest: JSONObject? = spec.manifestText?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        // 两种包型落位规则本就不同：应用程序带 program-manifest.json、有前后端之分，
        // 要平铺后端并配对快应用；系统组件是一整个可执行目录，落位后软链 entry 就完事。
        // 硬套会让工具件栽在「包内无 program-manifest.json」上。
        if (spec.shape == Shape.COMPONENT) {
            return installComponent(context, spec)
        }

        // 落位与快应用配对由 ota/ProgramInstaller 承担（它有 12 道判据与后端平铺），
        // 写注册表与留审计由本层补上（原 ota 侧缺这两项）。
        val base = ProgramInstaller.install(
            context = context,
            zip = spec.zip,
            manifest = manifest,
            source = ProgramInstaller.Source.entries.firstOrNull { it.label == spec.from.label }
                ?: ProgramInstaller.Source.OTA,
            manifestFile = spec.manifestFile?.takeIf { it.isFile }
                ?: spec.manifestText?.let { writeManifestFile(context, spec.programId, it) },
            programId = spec.programId,
            storeRoot = spec.storeRoot,
        )
        if (!base.ok) return Result(false, base.version, base.reason, base.detail, base.nodeVerifyOutput)

        val version = base.version ?: return Result(
            false, null, "no-version", "安装流程未产出版本号", base.nodeVerifyOutput,
        )

        // 写注册表：两条路都要在册，否则 SupervisorPool 不会为它建实例。
        //
        // 字段不是照抄，而是按"装完这个程序该处于什么状态"推出来的：
        //  · desired  —— 首次登记必须是 RUNNING，否则新装程序停在 STOPPED、后端永不启动
        //    （真机装成功后 desired=STOPPED、程序在跑 0/1）
        //  · deps     —— 清单声明的依赖，照实登记，供后续按需补装
        //  · resident —— 由程序自己声明（ProgramRegistry），决定要不要常驻
        val dir = lobos.os.ProgramManager.stateDirOf(context, spec.programId)
        val reg = lobos.os.ProgramIndex.get(context, spec.programId)
        val kind = kindOf(context, spec.programId)
        val baseEntry = reg ?: lobos.os.ProgramIndex.empty(
            spec.programId, lobos.os.ProgramManager.levelOfKind(kind),
        )
        val declared = runCatching {
            lobos.os.ProgramRegistry.spec(context, spec.programId)
        }.getOrNull()
        val writeIndex = runCatching {
            lobos.os.ProgramIndex.upsert(
                context,
                baseEntry.copy(
                    version = version,
                    enabled = true,
                    deps = depsOf(context, spec.programId).ifEmpty { baseEntry.deps },
                    origin = spec.from.origin,
                    tier = runCatching {
                        lobos.os.CatalogClient.entryFor(context, spec.programId)?.optString("tier", "").orEmpty()
                    }.getOrDefault("").ifBlank { baseEntry.tier },
                    stateDir = if (baseEntry.level == lobos.os.Level.INFRA) ""
                    else baseEntry.stateDir.ifBlank { lobos.os.ProgramManager.relStateDir(spec.programId, kind) },
                    role = declared?.role?.takeIf { it.isNotBlank() } ?: baseEntry.role,
                    resident = declared?.resident ?: baseEntry.resident,
                    desired = when {
                        baseEntry.desired == lobos.os.Desired.STOPPED && declared?.resident == true ->
                            lobos.os.Desired.RUNNING
                        reg == null -> lobos.os.Desired.RUNNING
                        else -> baseEntry.desired
                    },
                ),
            )
        }
        if (writeIndex.isFailure) {
            return Result(
                false, version, "index-write-failed",
                "包已落位但注册表写入失败：${writeIndex.exceptionOrNull()?.message}",
                base.nodeVerifyOutput,
            )
        }

        // 快应用配对已由 ota/ProgramInstaller 在落位时完成（它有 12 道判据，失败会带原因返回）。
        // 这里不再重复调：PortBroker.claim 虽幂等，但重复注入 config.json 与重复装入 dimina
        // 是白做功，且会让日志出现两次"前端已装入 dimina"。

        lobos.log.Journal.note(
            context, "package", true, "包已安装",
            "id=" + spec.programId + " version=" + version +
                " 来源=" + spec.from.label + " origin=" + spec.from.origin +
                " dir=" + dir.absolutePath,
        )
        return Result(true, version, null, "已登记 " + spec.programId + "@" + version, base.nodeVerifyOutput)
    }

    /**
     * 系统组件（运行时 / 工具）落位。
     *
     * 判据沿用 os/PackageInstaller 原有的：解包到暂存目录、落位越界检查、
     * entry 软链、注册表 upsert、CURRENT 提交。原实现里这些都在 os/PackageInstaller，
     * 现在搬到这里，让两条路共用一个安装点。
     */
    private fun installComponent(context: Context, spec: Spec): Result {
        val dir = lobos.os.ProgramManager.stateDirOf(context, spec.programId)
        val staging = File(dir, "." + spec.zip.nameWithoutExtension + ".staging")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            lobos.runtime.SupplyProvisioner.unzipFromFile(spec.zip, staging)
        }.onFailure {
            staging.deleteRecursively()
            runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
            return Result(false, null, "unzip-failed", "解包失败：" + it.message)
        }
        val version = spec.expectedVersion?.takeIf { it.isNotBlank() } ?: "0"
        // 落位路径由版本号拼出，版本号必须先过白名单，否则 ".." 之类能越界写出程序目录。
        val safeVer = lobos.os.ProgramIndex.safeSegment(version)
            ?: return Result(
                false, null, "version-illegal",
                "版本号非法（只接受字母数字与 . _ -，且非 . 或 ..）：" + version.take(40),
            )
        val dest = File(dir, safeVer)
        val rootCanon = runCatching { dir.canonicalFile.path }.getOrDefault(dir.absolutePath)
        if (dest.canonicalFile.path != rootCanon + File.separator + safeVer) {
            staging.deleteRecursively()
            return Result(false, null, "landing-out-of-bound", "落位越界，已拒绝：" + dest.canonicalFile.path)
        }
        runCatching { dest.deleteRecursively() }
        if (!staging.renameTo(dest)) {
            val copied = runCatching {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }
            if (copied.isFailure) {
                return Result(false, null, "landing-failed", "落位失败：" + copied.exceptionOrNull()?.message)
            }
        }
        dir.mkdirs()

        // 按二进制自己的 ELF 段铺依赖（等价 Linux 的 ld.so 处理 DT_RUNPATH）。
        // 不问包"你要什么" —— DT_NEEDED 与 DT_RUNPATH 就在二进制里，读出来照做。
        // 这样任何带 $ORIGIN 的件装完都能直接跑，不用为每种件写专用补丁。
        val depReport = satisfyElfDeps(context, dest)
        if (!depReport.ok) {
            return Result(false, safeVer, "elf-deps-unresolved", depReport.detail)
        }

        // 组件靠真名被调用（usr/bin/<name> 软链到落位目录里的 entry）。
        // 不建链等于装了个没人调得动的件 —— 所以建链失败**要让安装失败**。
        //
        // 原先这里忽略 linkEntry 的返回值：链没建成（SELinux 拒绝 / 落位目录
        // 不可写 / 目标不存在）时安装照样报成功，程序以为装好了，直到第一次
        // spawn 才报 command not found。而上面那句注释恰恰说明这是要判的 ——
        // 只是没判。
        val entryRel = spec.expectedEntryRel
            ?: runCatching {
                lobos.os.CatalogClient.entryFor(context, spec.programId)?.optString("entry", "").orEmpty()
            }.getOrDefault("").ifBlank { "bin/" + spec.programId }
        if (!linkEntry(context, dest, entryRel, spec.programId)) {
            // 不假装成功。件已落在盘上（便于排查），但明确报失败。
            return Result(
                false, safeVer, "entry-link-failed",
                "件已落位但入口软链建不起来（" +
                    lobos.runtime.PrefixProvisioner.binDir(context).absolutePath + "/" +
                    entryRel.substringAfterLast("/") + "）—— 装了个没人调得动的件；" +
                    "看 SELinux 是否允许该目录建链，或落位目录是否可写",
            )
        }
        val reg = lobos.os.ProgramIndex.get(context, spec.programId)
        val base = reg ?: lobos.os.ProgramIndex.empty(
            spec.programId, lobos.os.ProgramManager.levelOfKind(kindOf(context, spec.programId)),
        )
        val upserted = runCatching {
            lobos.os.ProgramIndex.upsert(
                context,
                base.copy(
                    version = version,
                    enabled = true,
                    origin = spec.from.origin,
                    stateDir = if (base.level == lobos.os.Level.INFRA) ""
                    else base.stateDir.ifBlank {
                        lobos.os.ProgramManager.relStateDir(spec.programId, kindOf(context, spec.programId))
                    },
                ),
            )
        }
        if (upserted.isFailure) {
            return Result(
                false, safeVer, "index-write-failed",
                "组件已落位但注册表写入失败：" + upserted.exceptionOrNull()?.message,
            )
        }
        val pd = lobos.ProgramDir(context, spec.programId, dir)
        runCatching { pd.setCurrentVersion(safeVer) }
        if (pd.currentVersion() != safeVer) {
            return Result(false, safeVer, "current-not-committed", "CURRENT 落位失败（安装未提交）")
        }
        lobos.log.Journal.note(
            context, "package", true, "系统组件已安装",
            "id=" + spec.programId + " version=" + safeVer +
                " 来源=" + spec.from.label + " dir=" + dest.absolutePath,
        )
        return Result(true, safeVer, null, "")
    }

    data class Result(
        val ok: Boolean,
        val version: String?,
        val reason: String?,
        val detail: String,
        val nodeVerifyOutput: String = "",
    ) {
        fun toDiagnosticLine(): String = when {
            ok -> "安装成功 v=$version"
            else -> "安装未生效 原因=$reason；$detail"
        }
    }

    private fun depsOf(context: Context, programId: String): List<String> {
        val arr = runCatching {
            lobos.os.CatalogClient.entryFor(context, programId)?.optJSONArray("deps")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotBlank() && v !in out) out += v
        }
        return out
    }

    /**
     * 按 ELF 段把依赖铺到能被找到的位置（等价 Linux 的 ld.so / ldconfig）。
     *
     * 判据来源：Linux 不要求包声明依赖。`DT_NEEDED` 写着要哪些 `.so`，
     * `DT_RUNPATH` 写着去哪找 —— 都在二进制里，系统读出来照做即可。
     * node 的 `DT_RUNPATH=$ORIGIN` 意味着它的 `libc++_shared.so`
     * 必须与它同目录，否则 linker 报 "cannot locate symbol"。
     *
     * 这里做两件事：
     *  1. 读落位目录里每个 ELF 的段，收集 DT_NEEDED
     *  2. 缺的 `.so` 依次从「系统已有件」与「APK 原生库目录」找，复制到 RUNPATH 指向处
     *
     * 找不到时不静默放过 —— 返回失败并说明缺哪个。装上一个跑不起来的件
     * 比装不上更坏：问题会推迟到运行时才暴露，且现场更难查。
     */
    private data class DepReport(val ok: Boolean, val detail: String, val placed: Int, val missing: List<String>)

    private fun satisfyElfDeps(context: Context, dest: File): DepReport {
        val elfs = dest.walkTopDown().filter { it.isFile && isElf(it) }.toList()
        if (elfs.isEmpty()) return DepReport(true, "落位目录内没有 ELF 文件，无需铺依赖", 0, emptyList())

        val needed = linkedSetOf<String>()
        // 依赖该放到哪：按每个二进制自己的 RUNPATH 解析，逐条展开。
        //
        // 之前只判「RUNPATH 里有没有 」（布尔），把路径本身丢了。
        // 那样 RUNPATH=/../lib 或 :/../lib 的件会被当成
        // 「非 」，依赖放错目录，链接期才炸 —— 和当初 placeNodeDeps
        // 一样的坑，只是换了形态。
        val targets = linkedSetOf<File>()
        for (f in elfs) {
            val d = lobos.os.ElfFacts.read(f) ?: continue
            needed += d.needed
            val dirs = resolveRunPath(d.runPath, f.parentFile)
            if (dirs.isEmpty()) targets += f.parentFile else targets += dirs
        }
        if (targets.isEmpty()) targets += dest
        // 系统那几个 .so（libc/libm/liblog/libdl）由 linker 自己找，不用管
        val systemProvided = setOf(
            "libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
            "libstdc++.so", "libgnustl_shared.so", "libc++_shared.so",
        )
        var missing = needed.filter { !systemProvided.contains(it) && File(dest, it).isFile }
        if (missing.isEmpty()) {
            return DepReport(true, "ELF 段要求的依赖已齐（或由系统提供）", 0, emptyList())
        }

        val sources = dependencySources(context)
        var placed = 0
        val stillMissing = mutableListOf<String>()
        for (name in missing) {
            val src = sources[name]
            var done = false
            for (dir in targets) {
                if (src == null) break
                val ok = runCatching {
                    dir.mkdirs()
                    src.copyTo(File(dir, name), overwrite = true)
                    File(dir, name).setExecutable(true, true)
                }.isSuccess
                if (ok) { done = true; placed += 1; break }
            }
            if (!done) stillMissing += name
        }
        if (stillMissing.isNotEmpty()) {
            return DepReport(
                false,
                "ELF DT_NEEDED 要求的库找不到：" + stillMissing.joinToString() +
                    "（落位目录=" + dest.absolutePath + "）",
                placed, stillMissing,
            )
        }
        return DepReport(true, "按 ELF 段铺齐 $placed 个依赖库", placed, emptyList())
    }

    /**
     * 展开 DT_RUNPATH / DT_RPATH 的路径列表。
     *
     * 规则：$ORIGIN = 该二进制自身所在目录；冒号分隔多段；相对路径按二进制目录解析。
     * 遇到 $LIB / $PLATFORM 这类本仓不会产生的占位符就跳过 —— 不猜，
     * 猜错等于把依赖放错目录，链接期才炸。
     */
    internal fun resolveRunPath(runPath: String?, originDir: File): List<File> {
        if (runPath.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, File>()
        for (seg in runPath.split(':')) {
            val s = seg.trim()
            if (s.isEmpty()) continue
            if (s.contains("$LIB") || s.contains("$PLATFORM")) continue
            val dir = when {
                s == "$ORIGIN" -> originDir
                s.startsWith("$ORIGIN/") -> File(originDir, s.removePrefix("$ORIGIN").trimStart('/'))
                s.startsWith("/") -> File(s)
                else -> File(originDir, s)
            }
            val key = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            out.putIfAbsent(key, dir)
        }
        return out.values.toList()
    }

    private fun isElf(f: File): Boolean = runCatching {
        f.inputStream().use { ins ->
            val magic = ByteArray(4)
            if (ins.read(magic) != 4) return@runCatching false
            magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)

    /** 可作为依赖来源的地方：APK 原生库目录、$PREFIX/lib、已装件目录。 */
    private fun dependencySources(context: Context): Map<String, File> {
        val out = HashMap<String, File>()
        val dirs = buildList {
            add(File(context.applicationInfo.nativeLibraryDir))
            add(File(lobos.runtime.PrefixProvisioner.libDir(context)))
            runCatching {
                lobos.os.ProgramRegistry.listIds(context).forEach { id ->
                    runCatching { add(File(lobos.os.ProgramManager.stateDirOf(context, id))) }
                }
            }
        }
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.walkTopDown().filter { it.isFile }.forEach { f ->
                out.putIfAbsent(f.name, f)
            }
        }
        return out
    }

    /**
     * 给一件建入口链（+ 别名链）。
     *
     * @param programId 件名。用来排除 bin 里「键 == 件名」的那一条 ——
     *   发布侧 aliasesOf 也跳过它（它就是本名），不排除就会建一条清单里
     *   不存在的链，卸载时删不掉，剩个指向已删目录的死链。
     */
    private fun linkEntry(context: Context, dest: File, entryRel: String, programId: String): Boolean {
        val target = File(dest, entryRel)
        if (!target.isFile) return false
        runCatching { lobos.runtime.ExecBits.apply(target) }
        val bin = lobos.runtime.PrefixProvisioner.binDir(context)
        val primary = entryRel.substringAfterLast("/")
        var ok = link(bin, primary, target)
        // 别名也必须建链 —— 只建入口那一个的话，一件里只有「本名」的命令能用。
        //
        // 阶段1c 的 clang 就是这个形态：件里有 clang / clang++ / ld.lld / llvm-ar /
        // llvm-nm / llvm-strip / llvm-objdump / llvm-readobj 八个，而 entry 只能声明
        // 一个。不建别名链 = 装上了但只调得动其中一个，而「装上了却调不动」
        // 比「没装」更难查。
        //
        // 卸载侧（PackageInstaller.uninstall）按清单的 aliases 删链 ——
        // 所以这里建的必须与清单里那份一致，见 aliasNames 的说明。
        for (a in aliasNames(dest, programId, primary)) {
            ok = link(bin, a, target) && ok
        }
        return ok
    }

    /**
     * 件内 package.json 的 bin 字段声明的别名（npm 生态约定，清单侧已支持读取）。
     * bin 可以是字符串（此时键即名）或对象（键=名，值=件内相对路径）。
     */
    /**
     * 件内 package.json 的 bin 字段声明的别名。
     *
     * **必须与发布侧 `aliasesOf` 的规则一致**，否则装得下、卸不干净：
     * 发布侧跳过「键 == 件名」的那一条（它就是本名，不是别名），
     * 写进清单的 aliases 里没有它。若这里不跳过，就会建一条清单里不存在的链 ——
     * 卸载时按清单删链，那条链就成了指向已删目录的死链。
     *
     * 注意「本名」有两种可能：`bin/<件名>`（多数件），或 entry 的末段
     * （npm 那种 `bin/npm-cli.js`，件名 npm）。两者都要排除，否则总会多建一条。
     *
     * bin 可以是字符串（此时键即名）或对象（键=名，值=件内相对路径）。
     */
    private fun aliasNames(dest: File, programId: String, primaryName: String): List<String> {
        val out = mutableListOf<String>()
        val pkg = File(dest, "package.json")
        if (!pkg.isFile) return out
        runCatching {
            val bin = org.json.JSONObject(pkg.readText()).opt("bin") ?: return@runCatching
            when (bin) {
                is org.json.JSONObject -> for (k in bin.keys()) if (k.isNotBlank()) out += k
                is String -> out += File(bin).name
            }
        }
        return out
            .filter { it != programId && it != primaryName }
            .filter { lobos.os.ProgramIndex.safeSegment(it) != null }
            .distinct()
    }

    /** 建软链。已存在（真文件或链）时先删 —— 升级要能换掉旧的那条。 */
    private fun link(binDir: File, name: String, target: File): Boolean {
        val l = File(binDir, name)
        return runCatching {
            l.parentFile?.mkdirs()
            if (l.exists() || java.nio.file.Files.isSymbolicLink(l.toPath())) l.delete()
            android.system.Os.symlink(target.absolutePath, l.absolutePath)
            true
        }.getOrDefault(false)
    }

    // 注册表记的是 level（APPLICATION/INFRA/...），落位相对路径要的是 kind
    // （RUNTIME/TOOL/...）。两者不是同一套词，不能互喂。
    private fun kindOf(context: Context, programId: String): String =
        runCatching {
            lobos.os.CatalogClient.entryFor(context, programId)?.optString("kind", "")?.trim().orEmpty()
        }.getOrDefault("").ifBlank { "APPLICATION" }

    private fun writeManifestFile(context: Context, programId: String, text: String): File? {
        val f = File(context.cacheDir, "install-manifest-" + programId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        return runCatching { f.writeText(text) }.let { if (it.isSuccess) f else null }
    }
}

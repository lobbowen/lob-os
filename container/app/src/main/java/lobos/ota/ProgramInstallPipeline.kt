package lobos.ota

import android.content.Context
import lobos.os.ProgramDir
import org.json.JSONObject
import java.io.File

object ProgramInstallPipeline {

    /**
     * 装的是「程序」还是「件」。
     *
     * 此前这两值叫「应用（快应用机制）」与「系统件（内核机制）」——
     * 那是把「程序/件」说成「两种运行机制」：所有件都是独立的，
     * 走同一套机制，只有「要不要落进 opt/ 由程序起」这一条不同。
     * 判据落位就够，不需要另立机制名。
     */
    enum class From(val label: String) {
        APP("程序"),
        COMPONENT("件"),
    }

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
                "包清单未声明 id/name：宿主不猜安装目标",
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

        if (spec.shape == Shape.COMPONENT) {
            return installComponent(context, spec)
        }

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

        val dir = lobos.os.ProgramManager.stateDirOf(context, spec.programId)
        val reg = lobos.os.ProgramIndex.get(context, spec.programId)
        val baseEntry = reg ?: lobos.os.ProgramIndex.empty(
            spec.programId, lobos.os.ProgramManager.levelOf(context, spec.programId),
        )
        val declared = runCatching {
            lobos.os.ProgramRegistry.spec(context, spec.programId)
        }.getOrNull()
        val writeIndex = runCatching {
            lobos.os.ProgramIndex.upsert(
                context,
                // 程序：形态与需求从**包内**的 manifest 读，不问清单（role=app）
                lobos.os.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      stateDir = baseEntry.stateDir.ifBlank { lobos.os.ProgramManager.relStateDir(context, spec.programId) },
                      sha256 = baseEntry.sha256,
                      assetEntry = declared?.entry ?: baseEntry.assetEntry,
                      role = declared?.role?.takeIf { it.isNotBlank() } ?: baseEntry.role,
                      resident = declared?.resident ?: true,
                      restart = declared?.restart ?: lobos.os.Restart.ON_FAILURE,
                      maxRestarts = declared?.maxRestarts ?: 5,
                      backoffMs = declared?.backoffMs?.takeIf { it.isNotEmpty() }
                          ?: lobos.os.ProgramIndex.DEFAULT_BACKOFF,
                      capabilities = declared?.capabilities ?: emptyList(),
                      requires = declared?.requires ?: emptyList(),
                      env = declared?.env ?: emptyMap(),
                      httpPort = declared?.http?.port ?: 0,
                      httpHealth = declared?.http?.health ?: "",
                      desired = when {
                          baseEntry.desired == lobos.os.Desired.STOPPED && declared?.resident == true ->
                              lobos.os.Desired.RUNNING
                          reg == null -> lobos.os.Desired.RUNNING
                          else -> baseEntry.desired
                      },
                      uiPackage = "",
                      uiName = "",
                      uiIcon = "",
                      onUiClosed = "",
                      invalid = declared?.invalid ?: baseEntry.invalid,
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

        lobos.log.Journal.note(
            context, "package", true, "包已安装",
            "id=" + spec.programId + " version=" + version +
                " 来源=" + spec.from.label +
                " dir=" + dir.absolutePath,
        )
        return Result(true, version, null, "已登记 " + spec.programId + "@" + version, base.nodeVerifyOutput)
    }

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

        val depReport = satisfyElfDeps(context, dest)
        if (!depReport.ok) {
            return Result(false, safeVer, "elf-deps-unresolved", depReport.detail)
        }

        val entryRel = spec.expectedEntryRel
            ?: runCatching {
                lobos.os.CatalogClient.entryFor(context, spec.programId)?.optString("entry", "").orEmpty()
            }.getOrDefault("").ifBlank { "bin/" + spec.programId }
        if (!linkEntry(context, dest, entryRel, spec.programId)) {
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
            spec.programId, lobos.os.ProgramManager.levelOf(context, spec.programId),
        )
        val meta = pieceMetaOf(dir, safeVer)
        val upserted = runCatching {
            lobos.os.ProgramIndex.upsert(
                context,
                // 件：形态与需求从**包内**的 component-meta.json 读 —— 件自带信息，不问清单单
                lobos.os.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      stateDir = base.stateDir.ifBlank { lobos.os.ProgramManager.relStateDir(context, spec.programId) },
                      sha256 = base.sha256,
                      libName = meta.libNameOrEmpty(),
                      assetEntry = meta.entry(),
                      role = meta.roleOrEmpty(),
                      requires = meta.requires(),
                      required = meta.requiredOr(false),
                      invalid = base.invalid,
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

    /**
     * 件自带的元信息 —— **只从包内读**。
     *
     * 之前这个件的一切（它是什么、落哪、依赖谁）都从包外的发布清单取，
     * 于是「它是什么」由一份可被替换的外部文件决定。现在包里带
     * component-meta.json，装完只信包。
     *
     * 读不到不阻断 —— 返回空值让登记退回现有字段，而不是让安装失败。
     */
    private class PieceMeta(private val o: JSONObject?) {
        fun entry(): String = o?.optString("entry", "")?.trim().orEmpty()
        fun roleOrEmpty(): String = when (o?.optString("form", "")?.trim()) {
            "lib" -> "library"
            "exec" -> "exec"
            else -> ""
        }
        fun libNameOrEmpty(): String = entry().substringAfterLast("/", "")
        /** 件是不是系统必需的 —— 包里没说就按「非必需」 */
        fun requiredOr(def: Boolean): Boolean =
            o?.optBoolean("required", def) ?: def

        fun requires(): List<String> {
            val arr = o?.optJSONArray("requires") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) out += v
            }
            return out
        }
    }

    private fun pieceMetaOf(dir: File, version: String): PieceMeta {
        val f = File(File(dir, version), "component-meta.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
        if (o == null) {
            RuntimeDiagnostics.append(context, "ota", false, "件缺 component-meta.json", f.name + "（登记退回现有字段）")
        }
        return PieceMeta(o)
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

    private data class DepReport(val ok: Boolean, val detail: String, val placed: Int, val missing: List<String>)

    private fun satisfyElfDeps(context: Context, dest: File): DepReport {
        val elfs = dest.walkTopDown().filter { it.isFile && isElf(it) }.toList()
        if (elfs.isEmpty()) return DepReport(true, "落位目录内没有 ELF 文件，无需铺依赖", 0, emptyList())

        val needed = linkedSetOf<String>()
        val targets = linkedSetOf<File>()
        for (f in elfs) {
            val d = lobos.os.ElfFacts.read(f) ?: continue
            needed += d.needed
            val dirs = resolveRunPath(d.runPath, f.parentFile)
            if (dirs.isEmpty()) targets += f.parentFile else targets += dirs
        }
        if (targets.isEmpty()) targets += dest
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

    private fun linkEntry(context: Context, dest: File, entryRel: String, programId: String): Boolean {
        val target = File(dest, entryRel)
        if (!target.isFile) return false
        runCatching { lobos.runtime.ExecBits.apply(target) }
        val bin = lobos.runtime.PrefixProvisioner.binDir(context)
        val primary = entryRel.substringAfterLast("/")
        var ok = link(bin, primary, target)
        for (a in aliasNames(dest, programId, primary)) {
            ok = link(bin, a, target) && ok
        }
        return ok
    }

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

    private fun link(binDir: File, name: String, target: File): Boolean {
        val l = File(binDir, name)
        return runCatching {
            l.parentFile?.mkdirs()
            if (l.exists() || java.nio.file.Files.isSymbolicLink(l.toPath())) l.delete()
            android.system.Os.symlink(target.absolutePath, l.absolutePath)
            true
        }.getOrDefault(false)
    }

    private fun writeManifestFile(context: Context, programId: String, text: String): File? {
        val f = File(context.cacheDir, "install-manifest-" + programId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        return runCatching { f.writeText(text) }.let { if (it.isSuccess) f else null }
    }
}

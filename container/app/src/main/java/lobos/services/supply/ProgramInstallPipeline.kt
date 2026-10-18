package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.kernel.elf.ElfFacts
import lobos.kernel.layout.SystemDirs
import lobos.kernel.elf.ExecBits
import lobos.kernel.layout.PrefixProvisioner
import org.json.JSONObject

object ProgramInstallPipeline {

    private const val LIB_TOKEN = "\$LIB"
    private const val PLATFORM_TOKEN = "\$PLATFORM"

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

        val dir = lobos.services.reg.ProgramManager.stateDirOf(context, spec.programId)
        val reg = lobos.services.reg.ProgramIndex.get(context, spec.programId)
        val baseEntry = reg ?: lobos.services.reg.ProgramIndex.empty(
            spec.programId, lobos.services.reg.ProgramManager.levelOf(context, spec.programId),
        )
        val declared = runCatching {
            lobos.services.reg.ProgramRegistry.spec(context, spec.programId)
        }.getOrNull()
        val writeIndex = runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                context,
                lobos.services.reg.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      stateDir = baseEntry.stateDir.ifBlank { lobos.services.reg.ProgramManager.relStateDir(context, spec.programId) },
                      sha256 = baseEntry.sha256,
                      assetEntry = declared?.entry ?: baseEntry.assetEntry,
                      role = declared?.role?.takeIf { it.isNotBlank() } ?: baseEntry.role,
                      resident = declared?.resident ?: true,
                      restart = declared?.restart ?: lobos.services.reg.Restart.ON_FAILURE,
                      maxRestarts = declared?.maxRestarts ?: 5,
                      backoffMs = declared?.backoffMs?.takeIf { it.isNotEmpty() }
                          ?: lobos.services.reg.ProgramIndex.DEFAULT_BACKOFF,
                      capabilities = declared?.capabilities ?: emptyList(),
                      requires = declared?.requires ?: emptyList(),
                      env = declared?.env ?: emptyMap(),
                      httpPort = declared?.http?.port ?: 0,
                      httpHealth = declared?.http?.health ?: "",
                      desired = when {
                          baseEntry.desired == lobos.services.reg.Desired.STOPPED && declared?.resident == true ->
                              lobos.services.reg.Desired.RUNNING
                          reg == null -> lobos.services.reg.Desired.RUNNING
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

        lobos.services.log.Journal.note(
            context, "package", true, "包已安装",
            "id=" + spec.programId + " version=" + version +
                " 来源=" + spec.from.label +
                " dir=" + dir.absolutePath,
        )
        return Result(true, version, null, "已登记 " + spec.programId + "@" + version, base.nodeVerifyOutput)
    }

    private fun installComponent(context: Context, spec: Spec): Result {
        val dir = lobos.services.reg.ProgramManager.stateDirOf(context, spec.programId)
        val staging = File(dir, "." + spec.zip.nameWithoutExtension + ".staging")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            SupplyProvisioner.unzipFromFile(spec.zip, staging)
        }.onFailure {
            staging.deleteRecursively()
            runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
            return Result(false, null, "unzip-failed", "解包失败：" + it.message)
        }
        val version = spec.expectedVersion?.takeIf { it.isNotBlank() } ?: "0"
        val safeVer = lobos.services.reg.ProgramIndex.safeSegment(version)
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
        if (!staging.renameTo(dest)) {
            val incoming = File(dir, "." + safeVer + ".incoming")
            runCatching { incoming.deleteRecursively() }
            val copied = runCatching {
                staging.copyRecursively(incoming, overwrite = true)
            }
            if (copied.isFailure) {
                runCatching { incoming.deleteRecursively() }
                return Result(false, null, "landing-failed", "落位失败：" + copied.exceptionOrNull()?.message)
            }
            val old = File(dir, "." + safeVer + ".previous")
            runCatching { old.deleteRecursively() }
            val hadOld = dest.isDirectory
            if (hadOld) runCatching { dest.renameTo(old) }
            if (!incoming.renameTo(dest)) {
                runCatching { incoming.deleteRecursively() }
                if (hadOld) runCatching { old.renameTo(dest) }
                return Result(false, null, "landing-failed", "落位失败：新目录换不进去，已回退到原版本")
            }
            runCatching { old.deleteRecursively() }
            runCatching { staging.deleteRecursively() }
        }
        dir.mkdirs()

        val landedMeta = pieceMetaOf(context, dir, safeVer)
        runCatching {
            File(dest, lobos.services.supply.ProgramDir.MANIFEST_NAME)
                .writeText(landedMeta.raw())
        }
        val binDir = File(dest, "bin")
        binDir.listFiles()?.forEach { f ->
            if (f.isFile) runCatching { ExecBits.apply(f) }
        }

        val depReport = satisfyElfDeps(context, dest)
        if (!depReport.ok) {
            return Result(false, safeVer, "elf-deps-unresolved", depReport.detail)
        }

        val entryRel = spec.expectedEntryRel
            ?: runCatching {
                CatalogClient.entryFor(context, spec.programId)?.optString("entry", "").orEmpty()
            }.getOrDefault("").ifBlank { "bin/" + spec.programId }
        if (!linkEntry(context, dest, entryRel, spec.programId)) {
            return Result(
                false, safeVer, "entry-link-failed",
                "件已落位但入口软链建不起来（" +
                    lobos.kernel.layout.PrefixProvisioner.binDir(context).absolutePath + "/" +
                    entryRel.substringAfterLast("/") + "）—— 装了个没人调得动的件；" +
                    "看 SELinux 是否允许该目录建链，或落位目录是否可写",
            )
        }
        val reg = lobos.services.reg.ProgramIndex.get(context, spec.programId)
        val base = reg ?: lobos.services.reg.ProgramIndex.empty(
            spec.programId, lobos.services.reg.ProgramManager.levelOf(context, spec.programId),
        )
        val meta = pieceMetaOf(context, dir, safeVer)
        val upserted = runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                context,
                lobos.services.reg.UnitEntry(
                  id = spec.programId,
                      version = version,
                      enabled = true,
                      stateDir = base.stateDir.ifBlank { lobos.services.reg.ProgramManager.relStateDir(context, spec.programId) },
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
        val pd = ProgramDir(context, spec.programId, dir)
        runCatching { pd.setCurrentVersion(safeVer) }
        if (pd.currentVersion() != safeVer) {
            return Result(false, safeVer, "current-not-committed", "CURRENT 落位失败（安装未提交）")
        }
        lobos.services.log.Journal.note(
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

    private class PieceMeta(private val o: JSONObject?) {
        fun raw(): String = o?.toString() ?: "{}"
        fun entry(): String = o?.optString("entry", "")?.trim().orEmpty()
        fun roleOrEmpty(): String = when (o?.optString("form", "")?.trim()) {
            "lib" -> "library"
            "exec" -> "exec"
            else -> ""
        }
        fun libNameOrEmpty(): String = entry().substringAfterLast("/", "")
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

    private fun pieceMetaOf(context: Context, dir: File, version: String): PieceMeta {
        val f = File(File(dir, version), "component-meta.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
        if (o == null) {
            RuntimeDiagnostics.append(context, "ota", false, "件缺 component-meta.json", f.name + "（登记退回现有字段）")
        }
        return PieceMeta(o)
    }

    private fun depsOf(context: Context, programId: String): List<String> {
        val arr = runCatching {
            CatalogClient.entryFor(context, programId)?.optJSONArray("deps")
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotBlank() && v !in out) out += v
        }
        return out
    }

    private data class DepReport(val ok: Boolean, val detail: String, val placed: Int, val missing: List<String>)

    private fun linkerCanFind(context: Context, name: String): Boolean {
        for (d in SYSTEM_LIB_DIRS) {
            if (File(d, name).exists()) return true
        }
        val jni = context.applicationInfo.nativeLibraryDir
        if (jni != null && File(jni, name).exists()) return true
        val usrLib = SystemDirs.lib(context)
        if (usrLib != null && File(usrLib, name).exists()) return true
        return false
    }

    private fun satisfyElfDeps(context: Context, dest: File): DepReport {
        val elfs = dest.walkTopDown().filter { it.isFile && isElf(it) }.toList()
        if (elfs.isEmpty()) return DepReport(true, "落位目录内没有 ELF 文件，无需铺依赖", 0, emptyList())

        val needed = linkedSetOf<String>()
        val targets = linkedSetOf<File>()
        for (f in elfs) {
            val d = lobos.kernel.elf.ElfFacts.read(f) ?: continue
            needed += d.needed
            val dirs = resolveRunPath(d.runPath, f.parentFile)
            if (dirs.isEmpty()) targets += f.parentFile else targets += dirs
        }
        if (targets.isEmpty()) targets += dest
        val systemProvided = linkedSetOf<String>()
        for (name in needed) {
            if (File(dest, name).isFile) continue
            if (linkerCanFind(context, name)) systemProvided.add(name)
        }
        var missing = needed.filter {
            !systemProvided.contains(it) &&
                !File(dest, it).isFile
        }
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
            if (s.contains(LIB_TOKEN) || s.contains(PLATFORM_TOKEN)) continue
            val dir = when {
                s == "\$ORIGIN" -> originDir
                s.startsWith("\$ORIGIN/") -> File(originDir, s.removePrefix("\$ORIGIN").trimStart('/'))
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
            add(lobos.kernel.layout.PrefixProvisioner.libDir(context))
            runCatching {
                lobos.services.reg.ProgramRegistry.listIds(context).forEach { id ->
                    runCatching { add(lobos.services.reg.ProgramManager.stateDirOf(context, id)) }
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
        val purged = lobos.kernel.layout.EntryLinks.purge(context, programId, dest)
        if (purged > 0) {
            RuntimeDiagnostics.append(
                context, "linkEntry", true, programId + " 清掉旧入口软链 " + purged + " 条",
                "dest=" + dest.absolutePath,
            )
        }
        val rootCanon = runCatching { dest.canonicalFile.path }.getOrDefault(dest.absolutePath)
        val target = File(dest, entryRel)
        val tCanon = runCatching { target.canonicalFile.path }.getOrDefault(target.absolutePath)
        if (tCanon != rootCanon && !tCanon.startsWith(rootCanon + File.separator)) {
            RuntimeDiagnostics.append(
                context, "linkEntry", false, "entry 越出落位目录，已拒绝",
                "entry=" + entryRel + "  target=" + tCanon + "  root=" + rootCanon,
            )
            return false
        }
        if (!target.isFile) return false
        if (entryRel.startsWith("bin/")) {
            runCatching { lobos.kernel.elf.ExecBits.apply(target) }
        }
        val primary = entryRel.substringAfterLast("/")
        val dir = if (entryRel.startsWith("lib/")) {
            lobos.kernel.layout.PrefixProvisioner.libDir(context)
        } else {
            lobos.kernel.layout.PrefixProvisioner.binDir(context)
        }
        var ok = link(dir, primary, target)
        for (a in aliasNames(dest, programId, primary)) {
            ok = link(dir, a, target) && ok
        }
        RuntimeDiagnostics.append(
            context, "linkEntry", ok,
            (if (ok) "入口软链已建 " else "入口软链建不起来 ") + programId,
            "entry=" + entryRel + "  dest=" + dest.absolutePath +
                "  target=" + target.absolutePath + "  dir=" + dir.absolutePath,
        )
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
            .filter { lobos.services.reg.ProgramIndex.safeSegment(it) != null }
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
    private val SYSTEM_LIB_DIRS = listOf("/system/lib64", "/system/lib", "/apex/com.android.runtime/lib64/bionic")
}

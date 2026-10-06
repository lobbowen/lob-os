package lobos.ota

import android.content.Context
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
        // 组件靠真名被调用（usr/bin/<name> 软链到落位目录里的 entry）。
        // 不建链等于装了个没人调得动的件。
        val entryRel = spec.expectedEntryRel
            ?: runCatching {
                lobos.os.CatalogClient.entryFor(context, spec.programId)?.optString("entry", "").orEmpty()
            }.getOrDefault("").ifBlank { "bin/" + spec.programId }
        linkEntry(context, dest, entryRel)
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
        val pd = lobos.ota.ProgramDir(context, spec.programId, dir)
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

    private fun linkEntry(context: Context, dest: File, entryRel: String): Boolean {
        val target = File(dest, entryRel)
        if (!target.isFile) return false
        runCatching { lobos.runtime.ExecBits.apply(target) }
        val link = File(lobos.runtime.PrefixProvisioner.binDir(context), entryRel.substringAfterLast("/"))
        return runCatching {
            link.parentFile?.mkdirs()
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            android.system.Os.symlink(target.absolutePath, link.absolutePath)
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

package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.services.app.DesktopIcons
import lobos.kernel.elf.ExecBits
import lobos.kernel.layout.PrefixProvisioner
import org.json.JSONArray
import org.json.JSONObject
import lobos.services.reg.PortBroker
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramManager
import lobos.services.reg.Level

object PackageInstaller {

    @Synchronized
    fun install(ctx: Context, name: String, version: String?, depth: Int = 0): JSONObject {
        val entry = CatalogClient.entryFor(ctx, name)
            ?: return fail(ctx, name, "目录里没有这个包（先 os.catalog.refresh）")
        var url = entry.optString("url", "")
        var want = entry.optString("sha256", "")
        var entryRel = entry.optString("entry", "bin/" + name)

        if (!version.isNullOrBlank()) {
            val versions = entry.optJSONArray("versions")
            var picked: org.json.JSONObject? = null
            if (versions != null) for (i in 0 until versions.length()) {
                val v = versions.optJSONObject(i) ?: continue
                if (v.optString("version", "") == version) { picked = v; break }
            }
            if (picked == null) {
                return fail(ctx, name, "版本 $version 不在可用清单里")
            }
            url = picked.optString("url", "").ifBlank { url }
            want = picked.optString("sha256", "").ifBlank { want }
            entryRel = picked.optString("entry", "").ifBlank { entryRel }
        }
        if (url.isBlank() || want.isBlank()) return fail(ctx, name, "目录项缺 url/sha256")
        InstallProgress.stage(name, InstallProgress.Stage.QUEUED)
        val gate = runtimeGate(ctx, entry, depth)
        if (gate != null) {
            InstallProgress.failed(name, gate)
            return fail(ctx, name, gate)
        }
        val kind = entry.optString("kind", "").trim().uppercase()
        val zipTmp = File(ctx.cacheDir, name + ".pkg.zip.part")
        InstallProgress.stage(name, InstallProgress.Stage.DOWNLOAD)
        runCatching { SupplyProvisioner.httpGetToFile(url, zipTmp) }
            .onFailure {
                InstallProgress.failed(name, "下载失败")
                return fail(ctx, name, "下载失败：" + url + "（" + (it.message ?: it.javaClass.simpleName) + "）")
            }
        InstallProgress.stage(name, InstallProgress.Stage.VERIFY)
        val got = SupplyProvisioner.sha256HexFile(zipTmp)
        if (got != want) {
            InstallProgress.failed(name, "内容与清单不符")
            return fail(ctx, name, "sha256 不符：" + got.take(12) + " != " + want.take(12))
        }
        InstallProgress.stage(name, InstallProgress.Stage.LAND)

        val isApplication = kind == "APPLICATION" || kind == "APP"
        val r = ProgramInstallPipeline.install(
            ctx,
            ProgramInstallPipeline.Spec(
                from = ProgramInstallPipeline.From.APP,
                programId = name,
                zip = zipTmp,
                shape = if (isApplication) ProgramInstallPipeline.Shape.APPLICATION
                else ProgramInstallPipeline.Shape.COMPONENT,
                expectedVersion = version?.takeIf { it.isNotBlank() }
                    ?: entry.optString("version", "").takeIf { it.isNotBlank() }
                    ?: got.take(12),
                expectedEntryRel = entryRel,
            ),
        )
        runCatching { zipTmp.delete() }
        if (!r.ok) {
            InstallProgress.failed(name, r.detail.ifBlank { r.reason ?: "落位失败" })
            return fail(ctx, name, "原因=${r.reason}；${r.detail}")
        }
        InstallProgress.stage(name, InstallProgress.Stage.LINK)

        val ver = r.version?.takeIf { it.isNotBlank() }
        if (ver == null) return fail(ctx, name, "原因=version-missing；清单里没有 version —— 不用空版本落位，否则目录形状与正常的 <根>/<版本> 不同，之后会被读成「未安装」")
        val dest = File(ProgramManager.stateDirOf(ctx, name), ver)
        InstallProgress.done(name, ver)
        return JSONObject().apply {
            put("ok", true)
            put("name", name)
            put("version", r.version)
            put("sha256", got)
            put("dir", dest.absolutePath)
        }
    }

    fun installedVersion(ctx: Context, name: String): String? =
        runCatching { ProgramDir(ctx, name, ProgramManager.stateDirOf(ctx, name)).currentVersion() }
            .getOrNull()

    @Synchronized
    fun activate(ctx: Context, name: String, version: String): JSONObject {
        val pd = ProgramDir(ctx, name, ProgramManager.stateDirOf(ctx, name))
        if (version.isBlank()) return fail(ctx, name, "激活失败：版本为空")
        if (version !in pd.installedVersions()) {
            return fail(ctx, name, "激活失败：版本 $version 未安装（已装 " +
                pd.installedVersions().joinToString(",") + "）")
        }
        val dest = pd.programDir(version)
        val metaFile = File(dest, "component-meta.json")
        val meta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            ?: return fail(ctx, name, "激活失败：目标版本没有 component-meta.json")
        val entryRel = meta.optString("entry", "").takeIf { it.isNotBlank() }
            ?: return fail(ctx, name, "激活失败：目标版本没有 entry")

        val bin = PrefixProvisioner.binDir(ctx)
        val target = File(dest, entryRel)
        for (linkName in linkNames(entryRel, meta.optJSONArray("aliases"))) {
            val link = File(bin, linkName)
            runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }.onFailure {
                return fail(ctx, name, "激活失败：重建入口 $linkName 失败（${it.message ?: it.javaClass.simpleName}）")
            }
        }
        pd.setCurrentVersion(version)
        lobos.kernel.layout.LdConfig.run(ctx)
        Journal.note(ctx, "package", true, "已激活版本", "name=$name version=$version")
        return JSONObject().apply {
            put("ok", true)
            put("name", name)
            put("version", version)
        }
    }

    fun ownsPackageRollback(ctx: Context, name: String): Boolean {

        return ProgramManager.relStateDir(ctx, name).isNotBlank()
    }

    fun rollbackToBaseline(ctx: Context, name: String): Boolean {
        val reg = ProgramIndex.get(ctx, name)

        if (reg != null && !ownsPackageRollback(ctx, name)) {
            Journal.note(ctx, "package", false, "拒绝回滚：这一件不归包回滚这一族管", "name=" + name)
            return false
        }
        val dir = ProgramManager.stateDirOf(ctx, name)
        if (!dir.absolutePath.startsWith(ctx.filesDir.absolutePath)) {
            Journal.note(ctx, "package", false, "拒绝回滚越界路径", "name=" + name + " dir=" + dir.absolutePath)
            return false
        }
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (!removed || dir.exists()) {
            Journal.note(
                ctx, "package", false, "回退未完成：目录仍在（回滚后基线版仍可用，故索引保留）",
                "name=" + name + " dir=" + dir.absolutePath + " 仍在=" + dir.exists(),
            )
            return false
        }
        Journal.note(ctx, "package", true, "回退到 APK 基线", "name=" + name)
        return true
    }

    fun uninstall(ctx: Context, name: String): Boolean {
        val reg = ProgramIndex.get(ctx, name)
        if (reg != null && ProgramIndex.isPiece(ctx, reg)) {
            Journal.note(ctx, "package", false, "拒绝卸载基础设施", "name=" + name + "（随 APK 交付，不可卸载）")
            return false
        }
        val dir = ProgramManager.stateDirOf(ctx, name)
        if (!dir.absolutePath.startsWith(ctx.filesDir.absolutePath)) {
            Journal.note(ctx, "package", false, "拒绝卸载越界路径", "name=" + name + " dir=" + dir.absolutePath)
            return false
        }
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (!removed || dir.exists()) {
            Journal.note(
                ctx, "package", false, "卸载未完成：目录仍在，不改索引（否则「索引说没装、文件却还在」）",
                "name=" + name + " dir=" + dir.absolutePath + " 仍在=" + dir.exists(),
            )
            return false
        }
        val entry = CatalogClient.entryFor(ctx, name)
        val entryRel = entry?.optString("entry", "") ?: ""
        if (entryRel.isNotBlank()) {
            val bin = PrefixProvisioner.binDir(ctx)
            for (linkName in linkNames(entryRel, entry?.optJSONArray("aliases"))) {
                val link = File(bin, linkName)
                runCatching {
                    if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                }
            }
        }
        ProgramIndex.remove(ctx, name)
        lobos.services.app.DesktopIcons.withdrawNow(ctx, name)
        PortBroker.release(ctx, name)
        Journal.note(ctx, "package", removed, "包已卸载", "name=" + name)
        return removed
    }

    private fun linkNames(entryRel: String, aliases: JSONArray?): List<String> {
        val out = mutableListOf<String>()
        out += entryRel.substringAfterLast("/")
        if (aliases != null) {
            for (i in 0 until aliases.length()) {
                val a = aliases.optJSONObject(i) ?: continue
                val an = a.optString("name", "")
                if (an.isNotBlank()) out += an
            }
        }
        return out.filter { ProgramIndex.safeSegment(it) != null }.distinct()
    }

    private fun runtimeGate(ctx: Context, entry: JSONObject, depth: Int = 0): String? {
        val req = entry.optJSONObject("requires") ?: entry.optJSONObject("runtime") ?: return null
        val runtime = req.optString("name", "").trim()
        if (runtime.isBlank()) return null
        val range = req.optString("range", "").trim()
        val packageName = entry.optString("name", "").ifBlank { entry.optString("id", "") }

        val installed = RuntimeResolver.installedVersions(ctx, runtime)
        val compatible = installed.filter { range.isBlank() || VersionRange.satisfies(it, range) }
        if (compatible.isNotEmpty()) return null

        if (depth >= 2) {
            return "运行时 " + runtime + (if (range.isBlank()) "" else "（需要 " + range + "）") +
                "：已装 " + installed.size + " 个版本均不满足；请先安装兼容版本"
        }
        val dep = runCatching { install(ctx, runtime, null, depth + 1) }.getOrNull()
        if (dep == null || !dep.optBoolean("ok", false)) {
            val why = dep?.optString("detail", "")?.takeIf { it.isNotBlank() } ?: "自动安装失败"
            return "需要运行时 " + runtime + (if (range.isBlank()) "" else "（" + range + "）") +
                "；已装版本均不满足，自动安装也没成功：" + why
        }
        val after = RuntimeResolver.installedVersions(ctx, runtime)
            .filter { range.isBlank() || VersionRange.satisfies(it, range) }
        if (after.isEmpty()) {
            return "运行时 " + runtime + " 自动安装后仍无满足 " + range + " 的版本，请检查已装版本。"
        }
        return null
    }

    private fun currentRuntime(ctx: Context, runtime: String): String? = ProgramIndex.all(ctx)
        .firstOrNull { it.id == runtime && it.levelOf(ctx) == Level.PIECE }
        ?.let { installedVersion(ctx, runtime) ?: it.version }
        ?.takeIf { it.isNotBlank() }

    private fun fail(ctx: Context, name: String, why: String): JSONObject {
        RuntimeDiagnostics.append(ctx, "package", false, "包安装失败: " + name, why)
        return JSONObject().apply {
            put("ok", false)
            put("name", name)
            put("detail", why)
        }
    }
}

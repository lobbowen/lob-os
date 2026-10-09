package lobos.os

import android.content.Context
import java.io.File
import lobos.RuntimeDiagnostics
import lobos.ota.ProgramInstallPipeline
import lobos.quickapp.DesktopIcons
import lobos.runtime.ExecBits
import lobos.runtime.PrefixProvisioner
import lobos.runtime.SupplyProvisioner
import org.json.JSONArray
import org.json.JSONObject

object PackageInstaller {

    @Synchronized
    fun install(ctx: Context, name: String, version: String?): JSONObject {
        val entry = CatalogClient.entryFor(ctx, name)
            ?: return fail(ctx, name, "目录里没有这个包（先 os.catalog.refresh）")
        val url = entry.optString("url", "")
        val want = entry.optString("sha256", "")
        if (url.isBlank() || want.isBlank()) return fail(ctx, name, "目录项缺 url/sha256")
        val gate = runtimeGate(ctx, entry)
        if (gate != null) return fail(ctx, name, gate)
        val kind = entry.optString("kind", "").trim().uppercase()
        val zipTmp = File(ctx.cacheDir, name + ".pkg.zip.part")
        runCatching { SupplyProvisioner.httpGetToFile(url, zipTmp) }
            .onFailure { return fail(ctx, name, "下载失败：" + url + "（" + (it.message ?: it.javaClass.simpleName) + "）") }
        val got = SupplyProvisioner.sha256HexFile(zipTmp)
        if (got != want) return fail(ctx, name, "sha256 不符：" + got.take(12) + " != " + want.take(12))

        val entryRel = entry.optString("entry", "bin/" + name)
        val isApplication = kind == "APPLICATION" || kind == "APP"
        val r = lobos.ota.ProgramInstallPipeline.install(
            ctx,
            lobos.ota.ProgramInstallPipeline.Spec(
                from = lobos.ota.ProgramInstallPipeline.From.APP,
                programId = name,
                zip = zipTmp,
                shape = if (isApplication) lobos.ota.ProgramInstallPipeline.Shape.APPLICATION
                else lobos.ota.ProgramInstallPipeline.Shape.COMPONENT,
                expectedVersion = version?.takeIf { it.isNotBlank() }
                    ?: entry.optString("version", "").takeIf { it.isNotBlank() }
                    ?: got.take(12),
                expectedEntryRel = entryRel,
            ),
        )
        runCatching { zipTmp.delete() }
        if (!r.ok) return fail(ctx, name, "原因=${r.reason}；${r.detail}")

        val dest = File(ProgramManager.stateDirOf(ctx, name), r.version.orEmpty())
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

    private fun dirOf(ctx: Context, name: String): ProgramDir =
        ProgramDir(ctx, name, ProgramManager.stateDirOf(ctx, name))

    fun rollbackToBaseline(ctx: Context, name: String): Boolean {
        val reg = ProgramIndex.get(ctx, name)
        if (reg != null && !reg.removable) {
            Journal.note(ctx, "package", false, "拒绝回滚基础设施", "name=" + name + "（随 APK 交付，无可回滚基线）")
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
        if (reg != null && !reg.removable) {
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
        lobos.quickapp.DesktopIcons.withdrawNow(ctx, name)
        PortBroker.release(ctx, name)
        Journal.note(ctx, "package", removed, "包已卸载", "name=" + name)
        return removed
    }

    private fun safeSegment(raw: String): String? = ProgramIndex.safeSegment(raw)

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

    private fun runtimeGate(ctx: Context, entry: JSONObject): String? {
        val req = entry.optJSONObject("requires") ?: entry.optJSONObject("runtime") ?: return null
        val runtime = req.optString("name", "").trim()
        if (runtime.isBlank()) return null
        val range = req.optString("range", "").trim()
        val installed = ProgramIndex.all(ctx)
            .firstOrNull { it.id == runtime && it.level == Level.PIECE }
            ?.let { installedVersion(ctx, runtime) ?: it.version }
            ?.takeIf { it.isNotBlank() }
        if (installed == null) {
            return "需要先安装运行时 " + runtime + (if (range.isBlank()) "" else "（" + range + "）")
        }
        if (range.isNotBlank() && !VersionRange.satisfies(installed, range)) {
            return "运行时 " + runtime + " 版本不满足 " + range + "（当前 " + installed + "）"
        }
        return null
    }

    private fun fail(ctx: Context, name: String, why: String): JSONObject {
        RuntimeDiagnostics.append(ctx, "package", false, "包安装失败: " + name, why)
        return JSONObject().apply {
            put("ok", false)
            put("name", name)
            put("detail", why)
        }
    }
}

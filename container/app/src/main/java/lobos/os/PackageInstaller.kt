package lobos.os

import android.content.Context
import lobos.RuntimeDiagnostics
import lobos.runtime.ExecBits
import lobos.runtime.PrefixProvisioner
import lobos.runtime.SupplyProvisioner
import java.io.File
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
        val dir = ProgramManager.stateDirOf(ctx, name)
        val bytes = runCatching { SupplyProvisioner.httpGet(url) }.getOrNull()
            ?: return fail(ctx, name, "下载失败：" + url)
        val got = SupplyProvisioner.sha256Hex(bytes)
        if (got != want) return fail(ctx, name, "sha256 不符：" + got.take(12) + " != " + want.take(12))
        val rawVer = version?.takeIf { it.isNotBlank() }
            ?: entry.optString("version", "").takeIf { it.isNotBlank() }
            ?: got.take(12)
        val ver = safeSegment(rawVer)
            ?: return fail(ctx, name, "版本号非法（只接受字母数字与 . _ -，且非 . 或 ..）：" + rawVer.take(40))
        val staging = File(dir, "." + ver + ".staging")
        runCatching {
            staging.deleteRecursively()
            staging.mkdirs()
            SupplyProvisioner.unzipInto(bytes, staging)
        }.onFailure {
            staging.deleteRecursively()
            runCatching { if (dir.isDirectory && dir.list()?.isEmpty() == true) dir.delete() }
            return fail(ctx, name, "解包失败：" + it.message)
        }
        val dest = File(dir, ver)
        val rootCanon = dir.canonicalFile.path
        if (dest.canonicalFile.path != rootCanon + File.separator + ver) {
            staging.deleteRecursively()
            return fail(ctx, name, "落位越界，已拒绝：" + dest.canonicalFile.path)
        }
        runCatching { dest.deleteRecursively() }
        if (!staging.renameTo(dest)) {
            runCatching {
                staging.copyRecursively(dest, overwrite = true)
                staging.deleteRecursively()
            }.onFailure { return fail(ctx, name, "落位失败：" + it.message) }
        }
        dir.mkdirs()
        val entryRel = entry.optString("entry", "bin/" + name)
        val links = linkEntry(ctx, dest, entryRel, entry.optJSONArray("aliases"))
        val base = ProgramIndex.get(ctx, name) ?: ProgramIndex.empty(name, ProgramManager.levelOfKind(kind))
        ProgramIndex.upsert(
            ctx,
            base.copy(
                version = ver,
                enabled = true,
                deps = deps(entry),
                sha256 = want,
                origin = "ota",
                tier = entry.optString("tier", base.tier),
                stateDir = if (kind == "INFRA") "" else base.stateDir.ifBlank { ProgramManager.relStateDir(name, kind) },
            ),
        )
        if (!StateFiles.writeAtomic(File(dir, "CURRENT"), ver)) {
            return fail(ctx, name, "CURRENT 落位失败（安装未提交）")
        }
        Journal.note(
            ctx, "package", true, "包已安装",
            "name=" + name + " version=" + ver + " 入口链接=" + links,
        )
        return JSONObject().apply {
            put("ok", true)
            put("name", name)
            put("version", ver)
            put("sha256", got)
            put("dir", dest.absolutePath)
            put("links", links)
        }
    }

    fun installedVersion(ctx: Context, name: String): String? {
        val f = File(ProgramManager.stateDirOf(ctx, name), "CURRENT")
        if (!f.isFile) return null
        return runCatching { f.readText().trim().ifBlank { null } }.getOrNull()
    }

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
        Journal.note(ctx, "package", removed, "回退到 APK 基线", "name=" + name)
        return removed
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
            .firstOrNull { it.id == runtime && it.level == Level.CAPABILITY }
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

    private fun deps(entry: JSONObject): List<String> =
        entry.optJSONArray("deps")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList()

    private fun linkEntry(ctx: Context, dest: File, entryRel: String, aliases: JSONArray?): Int {
        var n = 0
        val bin = PrefixProvisioner.binDir(ctx)
        bin.mkdirs()
        val pairs = mutableListOf(entryRel.substringAfterLast("/") to entryRel)
        if (aliases != null) {
            for (i in 0 until aliases.length()) {
                val a = aliases.optJSONObject(i) ?: continue
                val an = a.optString("name", "")
                val ae = a.optString("entry", "")
                if (an.isNotBlank() && ae.isNotBlank()) pairs.add(an to ae)
            }
        }
        for ((linkName, rel) in pairs) {
            val target = File(dest, rel)
            if (!target.isFile) continue
            runCatching { ExecBits.apply(target) }
            runCatching {
                val link = File(bin, linkName)
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                android.system.Os.symlink(target.absolutePath, link.absolutePath)
                n += 1
            }
        }
        return n
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
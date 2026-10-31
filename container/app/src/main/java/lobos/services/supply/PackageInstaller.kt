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
        if (!r.ok) return fail(ctx, name, "原因=${r.reason}；${r.detail}")

        val ver = r.version?.takeIf { it.isNotBlank() }
        if (ver == null) return fail(ctx, name, "原因=version-missing；清单里没有 version —— 不用空版本落位，否则目录形状与正常的 <根>/<版本> 不同，之后会被读成「未安装」")
        val dest = File(ProgramManager.stateDirOf(ctx, name), ver)
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

    /**
     * 这一件**归不归包回退这一族管**（=> 而那桥侧与实现侧逐字**读同一件事**）。
     *
     * 缺陷名（C-102）**包回退入口的两道守卫方向相反** ——
     *   逐字（01 裁的 S602-01-ROLLBACK-SUBJECT-SET.md §5.4 的 R-4 逐字）：
     *   「桥侧 `CapabilityBroker.kt:411` 只放行 `isPiece` 一族，
     *     实现侧 `PackageInstaller.kt:75` 拒掉 `isPiece` 一族；
     *     两道守卫共用同一把形态尺（`ProgramIndex.kt:70-72`）而读的方向相反
     *     —— 于是这一条入口当前对任何一件都走不到成功。」
     *   => 而那形态尺逐字答的是「这一件**有没有程序入口**」（=> 而 assetEntry 空不空），
     *     => 而**不是**「这一件**归谁管**」。
     *   => 而**处置**（按 01 的 S602-01 §11 归属表第 4 行逐字）：
     *     「05 让 `CapabilityBroker.kt:411` 与 `PackageInstaller.kt:75` 两道守卫**读同一件事**
     *       （这一件归谁管），不再一个要 `isPiece`、一个不要 `isPiece`」。
     *
     * => 而那**读什么**（=> 而那是**两问**、=> 而按 C-135 那不是同一问）：
     *   · 「这一件**归谁管**」=> 而那判据逐字是「这一件**有没有一个可删的落位版本目录**」
     *     （=> 而那落位目录那一格逐字对程序形态件非空、对库形态件空串）。
     *   · 「这一件**能不能回退**」=> 而那逐字是**逐件的前置条件**（=> 而那在 rollbackToBaseline 里判）。
     *
     * => 而那**为什么不是** isPiece：=> 而那 isPiece 判的是形态（=> 而库形态件逐字**有** APK 原件基线：
     *   而那 PreinstalledPieces 那一支逐字**按角色**给每一件记了 apk 槽、=> 而那库角色也有），
     *   => 而而那一族的**准入**问句**不是**形态。
     */
    fun ownsPackageRollback(ctx: Context, name: String): Boolean {
        //  => 而那一格逐字是**落位目录**那一格（=> 而那它逐字对程序形态件非空、对库形态件空串）。
        //  => 而那**读法**逐字与那形态尺**不同问**（=> 而那形态尺问「有没有程序入口」、
        //     => 而这一格问「有没有一个可删的落位版本目录」）。
        return ProgramManager.relStateDir(ctx, name).isNotBlank()
    }

    fun rollbackToBaseline(ctx: Context, name: String): Boolean {
        val reg = ProgramIndex.get(ctx, name)
        //  => 而这一道守卫逐字**与桥侧读同一件事**（=> 而那 ownership 那一个）、
        //  => 而而**不是**那形态尺（=> 而那方向相反那一族逐字治掉了）。
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

    private fun runtimeGate(ctx: Context, entry: JSONObject): String? {
        val req = entry.optJSONObject("requires") ?: entry.optJSONObject("runtime") ?: return null
        val runtime = req.optString("name", "").trim()
        if (runtime.isBlank()) return null
        val range = req.optString("range", "").trim()
        val installed = ProgramIndex.all(ctx)
            .firstOrNull { it.id == runtime && it.levelOf(ctx) == Level.PIECE }
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

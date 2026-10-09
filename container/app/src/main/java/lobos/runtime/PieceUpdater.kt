package lobos.runtime

import android.content.Context
import java.io.File
import lobos.RuntimeDiagnostics
import lobos.os.PieceScan
import lobos.os.ProgramIndex
import org.json.JSONArray
import org.json.JSONObject

object PieceUpdater {

    private const val TAG = "PieceUpdater"
    private const val MANIFEST_NAME = "native-manifest.json"
    private const val PUB_PEM_ASSET = "supply/component-public.pem"
    private const val MAX_MANIFEST_BYTES = SupplyProvisioner.MAX_MANIFEST_BYTES

    data class State(
        val id: String,
        val installedVersion: String?,
        val installedSha256: String?,
        val source: File?,
        val updated: Boolean,
    ) {
        val sourceSha256: String? get() = installedSha256
    }

    fun versionDir(ctx: Context, id: String, version: String): File =
        SupplyProvisioner.versionDir(ctx, id, version).also { it.parentFile?.mkdirs() }

    fun states(ctx: Context): List<State> {
        val out = mutableListOf<State>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        // 读注册表（dpkg -s「just displays the entry in the installed package
        // status database」）—— 查状态不扫磁盘，扫盘是 verify() 的事。
        for (e0 in lobos.os.ProgramIndex.all(ctx)) {
            val pe = e0
            val ver = pe.version
            val installed = if (pe.stateDir.isBlank() || pe.assetEntry.isBlank()) null
                else File(File(pe.stateDir), pe.assetEntry)
            out += State(
                pe.id,
                ver.ifBlank { null },
                installed?.takeIf { it.isFile }?.let {
                    runCatching { SupplyProvisioner.sha256HexFile(it) }.getOrNull()
                },
                installed?.takeIf { it.isFile },
                false,
            )
        }
        return out
    }

    fun checkAndUpdate(
        ctx: Context,
        manifestUrl: String,
        dryRun: Boolean = false,
    ): JSONObject {
        val res = JSONObject()
        val nativeName = SupplyProvisioner.manifestNameOf(ctx, "native") ?: MANIFEST_NAME
        val dir = SupplyProvisioner.manifestDir(ctx)
        if (dir == null) {
            res.put("ok", false); res.put("detail", "锚点里没有 baseUrl —— 不知道去哪取系统件清单")
            return res
        }
        val url = if (manifestUrl.startsWith("http")) manifestUrl else "$dir/$nativeName"

        val pubPem = runCatching {
            ctx.assets.open(PUB_PEM_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (pubPem == null) {
            res.put("ok", false); res.put("detail", "信任根读不到：assets/$PUB_PEM_ASSET")
            return res
        }
        val body = runCatching {
            SupplyProvisioner.httpGet(SupplyProvisioner.uncached(url), MAX_MANIFEST_BYTES)
        }.getOrNull()
        if (body == null) { res.put("ok", false); res.put("detail", "清单下载失败：$url"); return res }

        val sigUrl = url + ".sig"
        val sig = runCatching {
            android.util.Base64.decode(
                SupplyProvisioner.httpGet(SupplyProvisioner.uncached(sigUrl), MAX_MANIFEST_BYTES)
                    .toString(Charsets.UTF_8).trim(),
                android.util.Base64.DEFAULT,
            )
        }.getOrNull()
        if (sig == null) { res.put("ok", false); res.put("detail", "签名下载失败：$sigUrl"); return res }
        if (!SupplyProvisioner.verifyEd25519(pubPem, body, sig)) {
            RuntimeDiagnostics.append(ctx, "piece-ota", false, "系统件清单验签不通过（拒用）", url)
            res.put("ok", false); res.put("detail", "清单验签不通过（拒用）")
            return res
        }

        val parsed = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
        if (parsed == null) { res.put("ok", false); res.put("detail", "清单不是合法 JSON"); return res }
        val expires = parsed.optLong("expiresEpochMs", 0L)
        if (expires <= 0L) { res.put("ok", false); res.put("detail", "清单缺 expiresEpochMs（必填）"); return res }
        if (System.currentTimeMillis() > expires) {
            res.put("ok", false); res.put("detail", "清单已过期（" + expires + "），需发布新一轮")
            return res
        }

        val arr = parsed.optJSONArray("components") ?: JSONArray()
        val applied = JSONArray()
        val skipped = JSONArray()
        val pending = mutableListOf<Triple<lobos.os.UnitEntry, String, JSONObject>>()
        // 按 id 索引注册表条目 —— pending 要的是条目本身（id/libName/assetEntry）。
        // 「这件有没有随落说明」单独判，不把 JSONObject 塞进来：
        // 此前 byId 的值是 pieceMeta 的返回，下游按 UnitEntry 用就编不过。
        val byId = lobos.os.ProgramIndex.all(ctx)
            .filter { it.stateDir.isNotBlank() }
            // 没有随落说明的件不进这一轮：清单说更新它，我们却无从确认版本
            .filter { lobos.os.PieceScan.pieceMeta(ctx, it.id) != null }
            .associateBy { it.id }

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            if (id.isBlank()) continue
            val e = byId[id]
            if (e == null) {
                skipped.put(JSONObject().put("id", id).put("why", "注册表里没有这一件（清单不能凭空加件）"))
                continue
            }
            val cur = states(ctx).firstOrNull { it.id == id }
            val src = c.optString("source", "")
            if (src != "ota") {
                skipped.put(
                    JSONObject().put("id", id).put("why", "清单标 source=$src（没有可下载的更新）；" +
                        "本机是 ${cur?.installedVersion ?: "随包原件"}")
                )
                continue
            }
            val sha = c.optString("sha256", "")
            if (sha.isBlank()) {
                skipped.put(JSONObject().put("id", id).put("why", "清单没有 sha256 —— 无从判断该不该装"))
                continue
            }
            if (cur?.sourceSha256 == sha) {
                skipped.put(
                    JSONObject().put("id", id)
                        .put("why", "本机已是这批字节（sha256 相同），无需重复装")
                )
                continue
            }
            if (dryRun) {
                applied.put(JSONObject().put("id", id).put("sha256", sha).put("wouldInstall", true))
                continue
            }
            // 第二项是**版本号**（要落进 usr/lib/<id>/<版本>/），不是 sha256 ——
            // installGroup 按 version 拼落位目录，此前这里塞的却是 sha
            pending.add(Triple(e, c.optString("version", ""), c))
        }

        for ((_, group) in pending.groupBy { it.third.optString("url", "") + "-" + it.third.optString("sha256", "") }) {
            val first = group.first().third
            val url = first.optString("url", "")
            val sha = first.optString("sha256", "")
            val results = installGroup(ctx, url, sha, group)
            for ((e, _, _) in group) {
                val r = results[e.id] ?: (false to "未处理")
                if (r.first) {
                    applied.put(JSONObject()
                        .put("id", e.id)
                        .put("version", InstalledRuntime.versionOf(ctx, e.id)))
                } else {
                    skipped.put(JSONObject().put("id", e.id).put("why", r.second ?: "未知原因"))
                }
            }
        }

        res.put("ok", true)
        res.put("applied", applied)
        res.put("skipped", skipped)
        res.put("dryRun", dryRun)
        RuntimeDiagnostics.append(
            ctx, "piece-ota", true,
            "件清单比对完成（${applied.length()} 件更新 / ${skipped.length()} 件跳过）",
            "清单=$url 过期于=$expires",
        )
        return res
    }

    private fun installGroup(
        ctx: Context,
        url: String,
        wantSha: String,
        items: List<Triple<lobos.os.UnitEntry, String, JSONObject>>,
    ): Map<String, Pair<Boolean, String?>> {
        val out = LinkedHashMap<String, Pair<Boolean, String?>>()
        if (url.isBlank() || wantSha.isBlank()) {
            for ((e, _, _) in items) out[e.id] = false to "清单项缺 url/sha256"
            return out
        }
        // 版本号要能安全落进 usr/lib/<id>/<版本>/ —— 判据就是 ProgramIndex 那一个
        val todo = items.filter { (e, ver, _) ->
            val ok = lobos.os.ProgramIndex.safeSegment(ver) != null
            if (!ok) out[e.id] = false to "版本号非法（会越界）：$ver"
            ok
        }
        if (todo.isEmpty()) return out

        val tmp = File(ctx.cacheDir, "native-pack-" + wantSha.take(12) + ".zip")
        val unpacked = File(ctx.cacheDir, "native-pack-" + wantSha.take(12) + "-unpack")
        return try {
            val cached = tmp.isFile && SupplyProvisioner.sha256HexFile(tmp) == wantSha
            if (!cached) SupplyProvisioner.httpGetToFile(url, tmp)
            val got = SupplyProvisioner.sha256HexFile(tmp)
            if (got != wantSha) {
                val why = "sha256 不符：${got.take(12)} != ${wantSha.take(12)}"
                for ((e, _, _) in todo) out[e.id] = false to why
                return out
            }
            runCatching { unpacked.deleteRecursively() }
            SupplyProvisioner.unzipFromFile(tmp, unpacked)

            for ((e, ver, c) in todo) {
                val inPackage = c.optString("libName", e.libName).ifBlank { e.libName }
                val picked = findInPackage(unpacked, inPackage)
                out[e.id] = if (picked == null) {
                    false to ("包里没有 $inPackage（包内实际有：" +
                        listPackTop(unpacked).take(8).joinToString(", ") + "）")
                } else {
                    place(ctx, e, ver, c.optString("entry", ""), picked)
                }
            }
            out
        } catch (ex: Throwable) {
            val why = ex.message ?: ex.javaClass.simpleName
            for ((e, _, _) in todo) if (out[e.id] == null) out[e.id] = false to why
            out
        } finally {
            runCatching { tmp.delete() }
            runCatching { unpacked.deleteRecursively() }
        }
    }

    private fun place(ctx: Context, e: lobos.os.UnitEntry, version: String, entryRel: String, picked: File): Pair<Boolean, String?> =
        try {
            val dir = versionDir(ctx, e.id, version)
            runCatching { dir.deleteRecursively() }
            dir.mkdirs()
            val dest = File(dir, entryRel)
            dest.parentFile?.mkdirs()
            picked.copyTo(dest, overwrite = true)
            if (!dest.isFile || dest.length() != picked.length()) return false to "落盘后长度不符"
            ExecBits.apply(dest)
            if (!pointEntryAt(ctx, e, version, dest)) return false to "入口软链切换失败"
            true to null
        } catch (ex: Throwable) {
            false to (ex.message ?: ex.javaClass.simpleName)
        }

    private fun findInPackage(root: File, rel: String): File? {
        val direct = File(root, rel)
        if (direct.isFile) return direct
        val byName = root.walkTopDown().firstOrNull { it.isFile && it.name == File(rel).name }
        return byName
    }

    private fun listPackTop(root: File): List<String> =
        (root.list()?.sorted() ?: emptyList()).take(8)

    // version 是形参：下面第 273 行用它兜底（InstalledRuntime 读不到时）。
    // 此前定义里漏了这个参数，函数体却引用它 —— 而调用点按 4 参传，
    // 编译报 "Too many arguments for pointEntryAt"。
    private fun pointEntryAt(
        ctx: Context,
        e: lobos.os.UnitEntry,
        version: String,
        dest: File,
    ): Boolean = try {
        val entryName = e.assetEntry.substringAfterLast("/")
        val link = if (e.assetEntry.startsWith("bin/")) {
            File(PrefixProvisioner.binDir(ctx), entryName)
        } else {
            File(PrefixProvisioner.libDir(ctx), entryName)
        }
        // 先建同目录临时软链再改名：rename 是原子的，直接建会留下半截链接
        val staging = File(link.parentFile, "." + entryName + ".new")
        runCatching { java.nio.file.Files.deleteIfExists(staging.toPath()) }
        java.nio.file.Files.createSymbolicLink(staging.toPath(), dest.toPath())
        val ok = staging.renameTo(link) || run {
            java.nio.file.Files.deleteIfExists(link.toPath())
            staging.renameTo(link)
        }
        if (ok) {
            val detected = InstalledRuntime.versionOf(ctx, e.id).ifBlank { version }
            SupplyProvisioner.selectVersion(ctx, e.id, detected)
        }
        ok
    } catch (_: Throwable) {
        false
    }

    fun rollback(ctx: Context, id: String): Pair<Boolean, String?> {
        val pe = lobos.os.ProgramIndex.get(ctx, id).takeIf { lobos.os.ProgramIndex.isPiece(ctx, id) }
            ?: return false to "注册表里没有 id=$id"
        // 入口名就是落位时的文件名（落位形状自带，不另存一个 installName）
        val entryName = pe.assetEntry.substringAfterLast("/")
        val link = if (pe.assetEntry.startsWith("bin/")) {
            File(PrefixProvisioner.binDir(ctx), entryName)
        } else {
            File(PrefixProvisioner.libDir(ctx), entryName)
        }
        return try {
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return false to "这一件没被更新接管（入口不是软链），无可回滚"
            }
            java.nio.file.Files.deleteIfExists(link.toPath())
            val rebuilt = PrefixProvisioner.provision(ctx).contains(pe.assetEntry.substringAfterLast("/"))
            SupplyProvisioner.selectVersion(ctx, id, "")
            RuntimeDiagnostics.append(
                ctx, "piece-ota", rebuilt,
                "件已回滚到 APK 原件：" + id,
                "软链已删，provision " + if (rebuilt) "已重建原件" else "重建未成功（下次启动会再试）",
            )
            rebuilt to null
        } catch (ex: Throwable) {
            false to (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun prune(ctx: Context, id: String, keep: Int = 1): Pair<List<String>, Long> {
        val root = SupplyProvisioner.pieceDir(ctx, id)
        if (!root.isDirectory) return emptyList<String>() to 0L
        val now = states(ctx).firstOrNull { it.id == id }?.installedVersion
        val versions = root.list()?.filter { it.isNotBlank() }?.sorted().orEmpty()
            .filter { it != now }
        val drop = versions.dropLast(keep.coerceAtLeast(0))
        var freed = 0L
        val gone = mutableListOf<String>()
        for (v in drop) {
            val d = File(root, v)
            freed += runCatching { d.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
            if (d.deleteRecursively()) gone += v
        }
        return gone to freed
    }

    private fun ProgramIndex_safeSegment(v: String): Boolean =
        v.isNotBlank() && v != "." && v != ".." && v.none { it == '/' || it == '\\' || it.code < 0x20 }
}
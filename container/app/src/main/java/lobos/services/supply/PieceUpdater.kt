package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.PrefixProvisioner
import lobos.kernel.elf.ExecBits
import lobos.services.reg.InstalledRuntime

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
        val origins = PieceOriginStore.all(ctx).associateBy { it.id }
        for (pe in lobos.services.reg.ProgramIndex.all(ctx)) {
            val ver = pe.version
            //  缺陷名（C-102）**落位目录那一格按相对路径拼**：原来这一处是
            //   File(File(pe.stateDir), pe.assetEntry)，=> 而 stateDir 记的是**相对**形态
            //   （opt/<id>），=> 而那拼出来的是**相对当前工作目录**的路径，
            //   => 而那**不是**落位目录（读侧 ProgramManager.stateDirOf 是
            //   File(ctx.filesDir, e.stateDir)）。=> 而这一格只是「没有来源记录时」的兜底，
            //   => 而它指向的那一份**从来不是**真的那一份。
            val installed = if (pe.stateDir.isBlank() || pe.assetEntry.isBlank()) null
                else File(File(ctx.filesDir, pe.stateDir), pe.assetEntry)
            val origin = origins[pe.id]
            //  「被更新接管了没有」这一格**不能**用「入口是不是软链」来答：
            //   缺陷名（C-102）**软链那一格把两件事混成一件** —— 预装那一支**也**建软链
            //   （usr/bin/xxx 指向落位那一份），=> 而那软链**不是**接管的证据，
            //   => 而按「是不是软链」答，=> 而**每一件**都答「接管过」。
            //  真正可判的事实是：**入口软链现在指着的那一份**是不是**记下来的 OTA 那一份**。
            //   => 而那需要来源记录（PieceOriginStore）才有得比。
            val otaActive = PieceOriginStore.otaInCharge(origin)
            val otaLink = origin?.otaEntryPath.orEmpty()
            val apkTarget = origin?.apkTargetPath.orEmpty()
            val activeFile = when {
                otaActive -> runCatching { File(otaLink).toPath().toRealPath().toFile() }.getOrNull()
                apkTarget.isNotBlank() -> File(apkTarget).takeIf { it.isFile }
                else -> installed
            }
            out += State(
                pe.id,
                ver.ifBlank { null },
                activeFile?.takeIf { it.isFile }?.let {
                    runCatching { SupplyProvisioner.sha256HexFile(it) }.getOrNull()
                },
                activeFile?.takeIf { it.isFile },
                otaActive,
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
        val pending = mutableListOf<Triple<lobos.services.reg.UnitEntry, String, JSONObject>>()
        val byId = lobos.services.reg.ProgramIndex.all(ctx)
            .filter { it.stateDir.isNotBlank() }
            .filter { PieceScan.pieceMeta(ctx, it.id) != null }
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
        if (applied.length() > 0) {
            runCatching { lobos.kernel.layout.LdConfig.run(ctx) }
                .onFailure {
                    RuntimeDiagnostics.append(
                        ctx, "piece-ota", false, "升件后刷新库链失败",
                        it.javaClass.simpleName + ": " + it.message,
                    )
                }
        }
        return res
    }

    private fun installGroup(
        ctx: Context,
        url: String,
        wantSha: String,
        items: List<Triple<lobos.services.reg.UnitEntry, String, JSONObject>>,
    ): Map<String, Pair<Boolean, String?>> {
        val out = LinkedHashMap<String, Pair<Boolean, String?>>()
        if (url.isBlank() || wantSha.isBlank()) {
            for ((e, _, _) in items) out[e.id] = false to "清单项缺 url/sha256"
            return out
        }
        val todo = items.filter { (e, ver, _) ->
            val ok = lobos.services.reg.ProgramIndex.safeSegment(ver) != null
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

    private fun place(ctx: Context, e: lobos.services.reg.UnitEntry, version: String, entryRel: String, picked: File): Pair<Boolean, String?> =
        try {
            val dir = versionDir(ctx, e.id, version)
            val staged = File(dir.parentFile, "." + dir.name + ".staging")
            runCatching { staged.deleteRecursively() }
            val stagedDest = File(staged, entryRel)
            stagedDest.parentFile?.mkdirs()
            picked.copyTo(stagedDest, overwrite = true)
            if (!stagedDest.isFile || stagedDest.length() != picked.length()) {
                runCatching { staged.deleteRecursively() }
                return false to "落盘后长度不符"
            }
            ExecBits.apply(stagedDest)
            val aside = if (dir.isDirectory) {
                File(dir.parentFile, dir.name + ".replaced-" + System.currentTimeMillis())
            } else null
            if (aside != null && !dir.renameTo(aside)) {
                runCatching { staged.deleteRecursively() }
                return false to "旧版本目录无法让位：不动指针，保持现状"
            }
            if (!staged.renameTo(dir)) {
                if (aside != null) aside.renameTo(dir)
                runCatching { staged.deleteRecursively() }
                return false to "无法把新版本换进去（旧版本已复位）"
            }
            if (aside != null) aside.deleteRecursively()
            val dest = File(dir, entryRel)
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

    private fun pointEntryAt(
        ctx: Context,
        e: lobos.services.reg.UnitEntry,
        version: String,
        dest: File,
    ): Boolean = try {
        val entryName = e.assetEntry.substringAfterLast("/")
        val link = if (e.assetEntry.startsWith("bin/")) {
            File(PrefixProvisioner.binDir(ctx), entryName)
        } else {
            File(PrefixProvisioner.libDir(ctx), entryName)
        }
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
            //  记录那一件现在的入口来源 = OTA 版本（=> 而那「回到 APK 原件」要靠这一格才判得出）。
            runCatching {
                PieceOriginStore.recordOtaVersion(ctx, e.id, detected, link.absolutePath, dest.absolutePath)
            }
        }
        ok
    } catch (_: Throwable) {
        false
    }

    fun rollback(ctx: Context, id: String): Pair<Boolean, String?> {
        val pe = lobos.services.reg.ProgramIndex.get(ctx, id).takeIf { lobos.services.reg.ProgramIndex.isPiece(ctx, id) }
            ?: return false to "注册表里没有 id=$id"
        val entryName = pe.assetEntry.substringAfterLast("/")
        val link = if (pe.assetEntry.startsWith("bin/")) {
            File(PrefixProvisioner.binDir(ctx), entryName)
        } else {
            File(PrefixProvisioner.libDir(ctx), entryName)
        }
        val origin = PieceOriginStore.get(ctx, id)
        return try {
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return false to "这一件没被更新接管（入口不是软链），无可回滚"
            }
            //  回退的落点是那**记下来的** APK 原件指针（=> 而不是猜一个目录）。
            val apkTarget = origin?.apkTargetPath.orEmpty()
            if (apkTarget.isBlank()) {
                return false to "没有记下这一件的 APK 原件指针，无从回退（来源记录缺失）"
            }
            val apkFile = File(apkTarget)
            if (!apkFile.isFile) {
                return false to "记下的 APK 原件已不在：$apkTarget"
            }
            val staging = File(link.parentFile, "." + entryName + ".rollback")
            runCatching { java.nio.file.Files.deleteIfExists(staging.toPath()) }
            java.nio.file.Files.createSymbolicLink(staging.toPath(), apkFile.toPath())
            val swapped = runCatching {
                java.nio.file.Files.deleteIfExists(link.toPath())
                staging.renameTo(link)
            }.getOrDefault(false)
            if (!swapped) {
                runCatching { java.nio.file.Files.deleteIfExists(staging.toPath()) }
                return false to "入口指针换不回 APK 原件（软链替换失败）"
            }
            SupplyProvisioner.selectVersion(ctx, id, "")
            PieceOriginStore.markApkActive(ctx, id)
            val active = runCatching { link.toPath().toRealPath().toString() }.getOrDefault("")
            val back = active == runCatching { apkFile.toPath().toRealPath().toString() }.getOrDefault("")
            RuntimeDiagnostics.append(
                ctx, "piece-ota", back,
                "件已回滚到 APK 原件：" + id,
                "入口=" + link.absolutePath + " 目标=" + active +
                    " APK 原件=" + apkFile.absolutePath +
                    (if (back) "（已核对一致）" else "（核对不一致）"),
            )
            back to null
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
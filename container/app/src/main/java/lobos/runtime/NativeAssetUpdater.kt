package lobos.runtime

import android.content.Context
import lobos.RuntimeDiagnostics
import lobos.native.NativeAssetRegistry
import lobos.native.NativeExecutable
import org.json.JSONObject
import java.io.File

object NativeAssetUpdater {

    private const val TAG = "NativeAssetUpdater"
    private const val MANIFEST_NAME = "native-manifest.json"
    private const val PUB_PEM_ASSET = "supply/component-public.pem"
    private const val MAX_MANIFEST_BYTES = SupplyProvisioner.MAX_MANIFEST_BYTES

    data class State(
        val id: String,
        val installedVersion: String?,
        val source: File?,
        val updated: Boolean,
    )

    fun toolchainDir(ctx: Context, id: String): File =
        File(SupplyProvisioner.toolchainDir(ctx), id).also { it.mkdirs() }

    fun versionDir(ctx: Context, id: String, version: String): File =
        File(toolchainDir(ctx, id), version)

    fun states(ctx: Context): List<State> {
        val out = mutableListOf<State>()
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        for (e in NativeAssetRegistry.BINS + NativeAssetRegistry.LIBS) {
            val entry = if (e in NativeAssetRegistry.BINS) {
                File(PrefixProvisioner.binDir(ctx), e.installedAs)
            } else {
                File(PrefixProvisioner.libDir(ctx), e.installedAs)
            }
            val apkFile = File(nativeDir, e.libName)
            val source: File? = apkFile.takeIf { it.isFile }
            out += State(
                e.id,
                SupplyProvisioner.selectedVersion(ctx, e.id).ifBlank { null },
                source,
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
            res.put("ok", false); res.put("detail", "锚点里没有 baseUrl —— 不知道去哪取原生件清单")
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
            RuntimeDiagnostics.append(ctx, "native-ota", false, "原生件清单验签不通过（拒用）", url)
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
        val pending = mutableListOf<Triple<NativeExecutable, String, JSONObject>>()
        val byId = (NativeAssetRegistry.BINS + NativeAssetRegistry.LIBS).associateBy { it.id }

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            if (id.isBlank()) continue
            val e = byId[id]
            if (e == null) {
                skipped.put(JSONObject().put("id", id).put("why", "注册表里没有这一件（清单不能凭空加件）"))
                continue
            }
            val ver = c.optString("version", "")
            if (ver.isBlank()) {
                skipped.put(JSONObject().put("id", id).put("why", "清单未声明版本"))
                continue
            }
            val cur = states(ctx).firstOrNull { it.id == id }
            if (cur?.installedVersion == ver) {
                skipped.put(JSONObject().put("id", id).put("why", "已是 $ver（无需重复装）"))
                continue
            }
            val installed = cur?.installedVersion
            if (installed != null && lobos.ota.ProgramOtaVersions.compare(ver, installed) < 0) {
                skipped.put(
                    JSONObject().put("id", id).put("why", "远端 $ver 低于本机 $installed —— 拒绝降级")
                )
                RuntimeDiagnostics.append(
                    ctx, "native-ota", false,
                    "拒绝把底座件降级：" + id,
                    "远端 $ver < 本机 $installed；降级会让 bash/openssl 回到有漏洞的旧版本",
                )
                continue
            }
            val src = c.optString("source", "")
            if (src != "ota") {
                skipped.put(
                    JSONObject().put("id", id).put("why", "清单标 source=$src（没有可下载的更新）；" +
                        "本机是 ${cur?.installedVersion ?: "随包原件（版本未记录）"}")
                )
                continue
            }
            if (dryRun) {
                applied.put(JSONObject().put("id", id).put("version", ver).put("wouldInstall", true))
                continue
            }
            pending.add(Triple(e, ver, c))
        }

        for ((group, groupKey) in pending.groupBy { it.third.optString("url", "") + "-" + it.third.optString("sha256", "") }) {
            val first = group.first().third
            val url = first.optString("url", "")
            val sha = first.optString("sha256", "")
            val results = installGroup(ctx, url, sha, group)
            for ((e, ver, _) in group) {
                val r = results[e.id] ?: (false to "未处理")
                if (r.first) applied.put(JSONObject().put("id", e.id).put("version", ver))
                else skipped.put(JSONObject().put("id", e.id).put("why", r.second ?: "未知原因"))
            }
        }

        res.put("ok", true)
        res.put("applied", applied)
        res.put("skipped", skipped)
        res.put("dryRun", dryRun)
        RuntimeDiagnostics.append(
            ctx, "native-ota", true,
            "原生件清单比对完成（${applied.length()} 件更新 / ${skipped.length()} 件跳过）",
            "清单=$url 过期于=$expires",
        )
        return res
    }

    private fun installGroup(
        ctx: Context,
        url: String,
        wantSha: String,
        items: List<Triple<NativeExecutable, String, JSONObject>>,
    ): Map<String, Pair<Boolean, String?>> {
        val out = LinkedHashMap<String, Pair<Boolean, String?>>()
        if (url.isBlank() || wantSha.isBlank()) {
            for ((e, _, _) in items) out[e.id] = false to "清单项缺 url/sha256"
            return out
        }
        val todo = items.filter { (_, ver, _) ->
            val ok = ProgramIndex_safeSegment(ver)
            if (!ok) out[it.first.id] = false to "版本号非法（会越界）：$ver"
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
                    place(ctx, e, ver, c.optString("entry", e.installedAs), picked)
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

    private fun place(ctx: Context, e: NativeExecutable, version: String, entryRel: String, picked: File): Pair<Boolean, String?> =
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

    private fun pointEntryAt(ctx: Context, e: NativeExecutable, version: String, dest: File): Boolean = try {
        val link = if (e.id in NativeAssetRegistry.BIN_IDS) {
            File(PrefixProvisioner.binDir(ctx), e.installedAs)
        } else {
            File(PrefixProvisioner.libDir(ctx), e.installedAs)
        }
        val tmp = File(link.parentFile, "." + link.name + ".newlink")
        runCatching { java.nio.file.Files.deleteIfExists(tmp.toPath()) }
        java.nio.file.Files.createSymbolicLink(tmp.toPath(), dest.toPath())
        val ok = tmp.renameTo(link) || run {
            java.nio.file.Files.deleteIfExists(link.toPath())
            tmp.renameTo(link)
        }
        if (ok) {
            SupplyProvisioner.selectVersion(ctx, e.id, version)
        }
        ok
    } catch (_: Throwable) {
        false
    }

    fun rollback(ctx: Context, id: String): Pair<Boolean, String?> {
        val e = (NativeAssetRegistry.BINS + NativeAssetRegistry.LIBS).firstOrNull { it.id == id }
            ?: return false to "注册表里没有 id=$id"
        val link = if (id in NativeAssetRegistry.BIN_IDS) {
            File(PrefixProvisioner.binDir(ctx), e.installedAs)
        } else {
            File(PrefixProvisioner.libDir(ctx), e.installedAs)
        }
        return try {
            if (!java.nio.file.Files.isSymbolicLink(link.toPath())) {
                return false to "这一件没被更新接管（入口不是软链），无可回滚"
            }
            java.nio.file.Files.deleteIfExists(link.toPath())
            val rebuilt = PrefixProvisioner.provision(ctx).contains(e.installedAs)
            SupplyProvisioner.selectVersion(ctx, id, "")
            RuntimeDiagnostics.append(
                ctx, "native-ota", rebuilt,
                "底座件已回滚到 APK 原件：" + id,
                "软链已删，provision " + if (rebuilt) "已重建原件" else "重建未成功（下次启动会再试）",
            )
            rebuilt to null
        } catch (ex: Throwable) {
            false to (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun prune(ctx: Context, id: String, keep: Int = 1): Pair<List<String>, Long> {
        val root = File(SupplyProvisioner.toolchainDir(ctx), id)
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
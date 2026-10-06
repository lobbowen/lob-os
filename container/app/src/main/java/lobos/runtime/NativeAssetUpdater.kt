package lobos.runtime

import android.content.Context
import lobos.RuntimeDiagnostics
import lobos.native.NativeAssetRegistry
import lobos.native.NativeExecutable
import org.json.JSONObject
import java.io.File

/**
 * 底座件（原生件）的 OTA 更新与回滚。
 *
 * ── 为什么底座件要能 OTA ──
 * 底座件随 APK 交付（原件在 `nativeLibraryDir`，永远不动，作回退基线）。
 * 若只能换 APK 才能换件，那么「修一个 bash 的 bug」就要重发整个 APK ——
 * 而底座件恰恰是最常需要打补丁的一批（bash/busybox/openssl 都是上游件，
 * 上游出安全更新是常规节奏）。
 *
 * ── 落位与回滚（对齐 docs/NATIVE-COMPONENT-UPDATE.md）──
 *   原件（APK）   nativeLibraryDir/libbash.so        ← 永远不动 = 回退基线
 *   更新件        $PREFIX/lib/toolchain/bash/<版本>/  ← OTA 落这里
 *   入口          $PREFIX/bin/bash → 软链到更新件    ← 优先指向新版本
 *   回滚          软链指回原件，或删掉软链让 provision 重建
 *
 * ── 为什么不用 ProgramInstallPipeline ──
 * 那条通道落 `programs/<id>/<version>/`，且 `ProgramDir.assertNotDirectlyExecutable`
 * 明确断言「内核入口不该在 filesDir 里直接 exec」。而底座件**必须**在 filesDir 里
 * 可执行（本机实测：filesDir 下 chmod +x 的脚本能正常 exec，输出 EXEC_OK）。
 * 两条落位规则不同，就该有两个安装点 —— 这正是「两条路一个安装点」里的
 * 「两条路」。这里复用它的**验签与下载**（`SupplyProvisioner`），不复用它的落位。
 *
 * ── 不新增签名体系 ──
 * 底座件清单与商店清单**用同一个 Ed25519 公钥**（assets/supply/userland-public.pem），
 * 验签走 `SupplyProvisioner.verifyEd25519`。两套信任根意味着两处要轮换、
 * 两处可能只更新一处 —— 那是最坏形态。
 */
object NativeAssetUpdater {

    private const val TAG = "NativeAssetUpdater"
    private const val MANIFEST_NAME = "native-manifest.json"
    private const val PUB_PEM_ASSET = "supply/userland-public.pem"
    private const val MAX_MANIFEST_BYTES = SupplyProvisioner.MAX_MANIFEST_BYTES

    /** 一件底座件当前的状态 —— 控制面板要显示它，也是回滚的依据。 */
    data class State(
        val id: String,
        val apkVersion: String,        // APK 原件的版本（注册表声明）
        val installedVersion: String?,  // 实际在用的版本；null = 还在用原件
        val source: File?,             // 当前生效的文件
        val updated: Boolean,
    )

    /** 更新件根目录：$PREFIX/lib/toolchain/<id>/。 */
    fun toolchainDir(ctx: Context, id: String): File =
        File(SupplyProvisioner.toolchainDir(ctx), id).also { it.mkdirs() }

    fun versionDir(ctx: Context, id: String, version: String): File =
        File(toolchainDir(ctx, id), version)

    /**
     * 当前每一件的状态。
     *
     * 「在用哪个版本」的判据是**软链指向哪儿**，不是查清单：
     * 清单可能还没刷新，而软链已经切过去了 —— 以文件系统的实际形态为准。
     */
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
            var installedVer: String? = null
            var source: File? = apkFile.takeIf { it.isFile }
            try {
                if (java.nio.file.Files.isSymbolicLink(entry.toPath())) {
                    val real = entry.toPath().toRealPath()
                    // 形如 .../toolchain/<id>/<version>/<file>
                    val rel = real.parentFile?.parentFile?.parentFile
                        ?.relativeTo(SupplyProvisioner.toolchainDir(ctx).toPath().toAbsolutePath())
                        ?.toString()
                    if (rel != null) {
                        val seg = rel.split(File.separatorChar).filter { it.isNotBlank() }
                        if (seg.size >= 2) {
                            installedVer = seg[1]
                            source = real.toFile()
                        }
                    }
                } else if (entry.isFile) {
                    // 实体文件 = provision 从 APK 铺下来的原件
                    installedVer = null
                    source = entry
                }
            } catch (_: Throwable) {
                // 读不出来就按「原件」算，并把实际路径记下来由上层判
            }
            out += State(e.id, e.version, installedVer, source, installedVer != null)
        }
        return out
    }

    /**
     * 比对清单与本地，有新版就装。
     *
     * 不自动调 —— 调用时机（启动 / 控制面板按钮 / OTA 轮次）由上层决定。
     * 底座件更新会换掉正在被 exec 的文件，所以**不能在程序运行中静默做**：
     * bash 正在跑时替换它，下次 exec 就是新版本，而这次 exec 仍在旧 inode 上 ——
     * 这本身没问题，但控制面板得知道发生了什么，所以要留日志。
     */
    fun checkAndUpdate(
        ctx: Context,
        manifestUrl: String,
        dryRun: Boolean = false,
    ): JSONObject {
        val res = JSONObject()
        val anchor = SupplyProvisioner.channelAnchor(ctx)
        val base = anchor?.optString("baseUrl", "")?.trimEnd('/').orEmpty()
        if (base.isBlank()) {
            res.put("ok", false); res.put("detail", "锚点里没有 baseUrl —— 不知道去哪取原生件清单")
            return res
        }
        val url = if (manifestUrl.startsWith("http")) manifestUrl else "$base/$MANIFEST_NAME"

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

        // 清单与商店清单同签名；签名件名与商店一致（<name>.sig）
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
        val byId = (NativeAssetRegistry.BINS + NativeAssetRegistry.LIBS).associateBy { it.id }

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            if (id.isBlank()) continue
            val e = byId[id]
            if (e == null) {
                // 清单里有注册表没有的件 → 拒用。不能让清单凭空往系统里塞东西。
                skipped.put(JSONObject().put("id", id).put("why", "注册表里没有这一件（清单不能凭空加件）"))
                continue
            }
            val ver = c.optString("version", "")
            if (ver.isBlank()) {
                skipped.put(JSONObject().put("id", id).put("why", "清单未声明版本"))
                continue
            }
            // APK 原件已是这个版本 → 不必更新（也没有更新的必要）
            if (e.version.isNotBlank() && ver == e.version) {
                skipped.put(JSONObject().put("id", id).put("why", "APK 原件已是 $ver"))
                continue
            }
            val cur = states(ctx).firstOrNull { it.id == id }
            if (cur?.installedVersion == ver) {
                skipped.put(JSONObject().put("id", id).put("why", "已是 $ver（无需重复装）"))
                continue
            }
            // 判 source 再判 url —— 顺序不能反。
            //
            // 清单里 source='apk' 的条目**不带** url/sha256（发布侧只给可 OTA 的
            // 那几件写下载地址）。而这一条恰好是「APK 原件版本与清单不同」才走到
            // 这里 —— 说明设备上装的是更旧的版本、清单在说「你该升级」。
            // 若不判 source 就去读 url，会报「清单项缺 url/sha256」：
            // 那把诊断指向了清单生成器（它没错），而真正的原因是**这一件压根没有
            // 可下载的更新**。诊断指错方向比诊断缺失更费时间。
            val src = c.optString("source", "")
            if (src != "ota") {
                skipped.put(
                    JSONObject().put("id", id).put("why", "清单标 source=$src（没有可下载的更新）；" +
                        "本机是 ${cur?.installedVersion ?: "APK 原件 " + e.version.ifBlank { "（版本未声明）" }}")
                )
                continue
            }
            if (dryRun) {
                applied.put(JSONObject().put("id", id).put("version", ver).put("wouldInstall", true))
                continue
            }
            val r = installOne(ctx, e, ver, c)
            if (r.first) applied.put(JSONObject().put("id", id).put("version", ver))
            else skipped.put(JSONObject().put("id", id).put("why", r.second ?: "未知原因"))
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

    /** 装一件：下载 → sha256 校验 → 落到 toolchain/<id>/<ver>/ → 切软链。 */
    private fun installOne(
        ctx: Context,
        e: NativeExecutable,
        version: String,
        entry: JSONObject,
    ): Pair<Boolean, String?> {
        val url = entry.optString("url", "")
        val wantSha = entry.optString("sha256", "")
        val entryRel = entry.optString("entry", e.installedAs)
        if (url.isBlank() || wantSha.isBlank()) return false to "清单项缺 url/sha256"
        if (!ProgramIndex_safeSegment(version)) return false to "版本号非法（会越界）：$version"

        val dir = versionDir(ctx, e.id, version)
        val tmp = File(ctx.cacheDir, "native-${e.id}-${version}.part")
        return try {
            SupplyProvisioner.httpGetToFile(url, tmp)
            val got = SupplyProvisioner.sha256HexFile(tmp)
            if (got != wantSha) return false to "sha256 不符：${got.take(12)} != ${wantSha.take(12)}"
            runCatching { dir.deleteRecursively() }
            dir.mkdirs()
            val dest = File(dir, entryRel)
            dest.parentFile?.mkdirs()
            tmp.copyTo(dest, overwrite = true)
            if (!dest.isFile || dest.length() != tmp.length()) return false to "落盘后长度不符"
            ExecBits.apply(dest)
            tmp.delete()
            if (!pointEntryAt(ctx, e, dest)) return false to "入口软链切换失败"
            true to null
        } catch (ex: Throwable) {
            runCatching { tmp.delete() }
            false to (ex.message ?: ex.javaClass.simpleName)
        }
    }

    /**
     * 把 `usr/bin/<name>`（或 `usr/lib/<lib>`）切到更新件。
     *
     * 切法是「先建软链再删原件」：任何时刻入口都指向一个存在的文件。
     * 反过来做（先删后建）会有一个窗口期入口不存在 —— 那一刻 exec 会得到
     * 「command not found」，而那正是最难查的一类瞬时故障。
     */
    private fun pointEntryAt(ctx: Context, e: NativeExecutable, dest: File): Boolean = try {
        val link = if (e.id in NativeAssetRegistry.BIN_IDS) {
            File(PrefixProvisioner.binDir(ctx), e.installedAs)
        } else {
            File(PrefixProvisioner.libDir(ctx), e.installedAs)
        }
        val tmp = File(link.parentFile, "." + link.name + ".newlink")
        runCatching { java.nio.file.Files.deleteIfExists(tmp.toPath()) }
        java.nio.file.Files.createSymbolicLink(tmp.toPath(), dest.toPath())
        // 原子替换：把临时软链改名盖掉旧的那个
        val ok = tmp.renameTo(link) || run {
            java.nio.file.Files.deleteIfExists(link.toPath())
            tmp.renameTo(link)
        }
        ok
    } catch (_: Throwable) {
        false
    }

    /**
     * 回滚一件：删掉软链，让 `PrefixProvisioner.provision` 下次启动用 APK 原件重建。
     *
     * 为什么不用「软链指回 APK 原件」：那样要往 APK 目录建软链，而
     * `nativeLibraryDir` 的内容由系统管理，我们写进去的东西重启后可能消失。
     * 删软链更干净 —— 重建的逻辑（provision）本来就存在，不新增第二条路径。
     */
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
            // 立刻用 APK 原件重建，别等下次启动 —— 回滚后用户马上就要能用
            val rebuilt = PrefixProvisioner.provision(ctx).contains(e.installedAs)
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

    /** 清理超出保留份数的旧版本（原件永不删 —— 它在 APK 里）。 */
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
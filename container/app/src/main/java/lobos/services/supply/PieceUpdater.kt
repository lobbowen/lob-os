package lobos.services.supply

import android.content.Context
import java.io.File
import lobos.services.log.RuntimeDiagnostics
import org.json.JSONArray
import org.json.JSONObject
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

    fun states(ctx: Context, perPiece: MutableList<Pair<String, Long>>? = null): List<State> {
        val out = mutableListOf<State>()
        val origins = PieceOriginStore.all(ctx).associateBy { it.id }
        for (pe in lobos.services.reg.ProgramIndex.all(ctx)) {
            val ver = pe.version

            val installed = if (pe.stateDir.isBlank() || pe.assetEntry.isBlank()) null
                else File(File(ctx.filesDir, pe.stateDir), pe.assetEntry)
            val origin = origins[pe.id]

            val otaSlotsInCharge = PieceOriginStore.otaSlotsInCharge(origin)
            val otaActive = otaSlotsInCharge.isNotEmpty()
            val apkSlots = origin?.apkSlots.orEmpty().filter { it.complete }
            val activeFile = when {
                otaActive -> runCatching {
                    File(otaSlotsInCharge.first().targetPath).toPath().toRealPath().toFile()
                }.getOrNull()
                apkSlots.isNotEmpty() -> File(apkSlots.first().targetPath).takeIf { it.isFile }
                else -> installed
            }
            val pieceStart = System.currentTimeMillis()
            val sha = activeFile?.takeIf { it.isFile }?.let {
                runCatching { SupplyProvisioner.sha256HexFile(it) }.getOrNull()
            }
            perPiece?.add(pe.id to (System.currentTimeMillis() - pieceStart))
            out += State(
                pe.id,
                ver.ifBlank { null },
                sha,
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

        val t0 = System.currentTimeMillis()
        val body = runCatching {
            SupplyProvisioner.httpGet(SupplyProvisioner.uncached(url), MAX_MANIFEST_BYTES)
        }.getOrNull()
        val t1 = System.currentTimeMillis()
        if (body == null) { res.put("ok", false); res.put("detail", "清单下载失败：$url"); return res }

        val sigUrl = url + ".sig"
        val sig = runCatching {
            android.util.Base64.decode(
                SupplyProvisioner.httpGet(SupplyProvisioner.uncached(sigUrl), MAX_MANIFEST_BYTES)
                    .toString(Charsets.UTF_8).trim(),
                android.util.Base64.DEFAULT,
            )
        }.getOrNull()
        val t2 = System.currentTimeMillis()
        if (sig == null) { res.put("ok", false); res.put("detail", "签名下载失败：$sigUrl"); return res }

        val facts = lobos.kernel.crypto.Crypto.verifyEd25519WithFacts(pubPem, body, sig)
        val t3 = System.currentTimeMillis()
        val timing = "计时：下载清单 " + (t1 - t0) + " 毫秒、下载签名 " + (t2 - t1) +
            " 毫秒、验签 " + (t3 - t2) + " 毫秒"
        if (!facts.ok) {
            RuntimeDiagnostics.append(ctx, "piece-ota", false, "系统件清单验签不通过（拒用）", url)

            RuntimeDiagnostics.append(
                ctx, "piece-ota-verify", false,
                "那验签那一格实际用的是什么锚、它实际验的是哪一份字节",
                facts.oneLine() + " " + timing,
            )
            res.put("ok", false); res.put("detail", "清单验签不通过（拒用）")
            return res
        }

        RuntimeDiagnostics.append(
            ctx, "piece-ota-verify", true,
            "那验签那一格实际用的是什么锚、它实际验的是哪一份字节",
            facts.oneLine() + " " + timing,
        )

        val tp0 = System.currentTimeMillis()
        val parsed = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
        val tp1 = System.currentTimeMillis()
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
        val tb0 = System.currentTimeMillis()
        val allEntries = lobos.services.reg.ProgramIndex.all(ctx)
        val tb1 = System.currentTimeMillis()
        val byId = allEntries
            .filter { it.stateDir.isNotBlank() }
            .filter { PieceScan.pieceMeta(ctx, it.id) != null }
            .associateBy { it.id }
        val tb2 = System.currentTimeMillis()

        val ts0 = System.currentTimeMillis()
        val perPiece = mutableListOf<Pair<String, Long>>()
        val allStates = states(ctx, perPiece)
        val ts1 = System.currentTimeMillis()
        val statesById = allStates.associateBy { it.id }
        val ts2 = System.currentTimeMillis()
        val pieceSizes = allStates.map { it.id to (it.source?.let { f -> f.length() } ?: -1L) }
        val ts3 = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id", "")
            if (id.isBlank()) continue
            val e = byId[id]
            if (e == null) {
                skipped.put(JSONObject().put("id", id).put("why", "注册表里没有这一件（清单不能凭空加件）"))
                continue
            }
            val cur = statesById[id]
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

        val t4 = System.currentTimeMillis()
        val pieceBreakdown = perPiece.joinToString(" ") { (id, ms) ->
            val size = pieceSizes.firstOrNull { it.first == id }?.second ?: -1L
            id + " " + ms + " 毫秒/" + size + " 字节"
        }
        RuntimeDiagnostics.append(
            ctx, "piece-ota", true,
            "件清单比对完成（${applied.length()} 件更新 / ${skipped.length()} 件跳过）",
            "清单=$url 过期于=$expires 计时：下载清单 " + (t1 - t0) + " 毫秒、下载签名 " + (t2 - t1) +
                " 毫秒、验签 " + (t3 - t2) + " 毫秒、比对 " + (t4 - t3) + " 毫秒" +
                " 比对细分：解清单 " + (tp1 - tp0) + " 毫秒、读注册表 " + (tb1 - tb0) +
                " 毫秒、读各件元数据 " + (tb2 - tb1) +
                " 毫秒、逐件读状态 " + (ts1 - ts0) + " 毫秒、建索引 " + (ts2 - ts1) +
                " 毫秒、读尺寸 " + (ts3 - ts2) + " 毫秒、逐件对清单 " + (t4 - ts3) + " 毫秒" +
                " 逐件耗时：" + pieceBreakdown,
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
            SupplyProvisioner.unzipFromFile(tmp, unpacked, ctx)

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

        val locatedAll = PieceEntryPointer.locateAll(ctx, e)
        if (locatedAll.pointers.isEmpty()) return false
        var allOk = true
        for (located in locatedAll.pointers) {
            val link = located.pointer
            val entryName = located.name
            val staging = File(link.parentFile, "." + entryName + ".new")
            runCatching { java.nio.file.Files.deleteIfExists(staging.toPath()) }
            java.nio.file.Files.createSymbolicLink(staging.toPath(), dest.toPath())
            val ok = staging.renameTo(link) || run {
                java.nio.file.Files.deleteIfExists(link.toPath())
                staging.renameTo(link)
            }
            if (!ok) {
                allOk = false
                continue
            }

            runCatching {
                PieceOriginStore.recordOtaSlot(
                    ctx, e.id, version, located.role, link.absolutePath, dest.absolutePath,
                )
            }
        }
        if (allOk) {
            val detected = InstalledRuntime.versionOf(ctx, e.id).ifBlank { version }
            SupplyProvisioner.selectVersion(ctx, e.id, detected)
        }
        allOk
    } catch (_: Throwable) {
        false
    }

    data class RollbackStatus(
        val id: String,
        val eligible: Boolean,

        val reason: String,

        val detail: String,

        val role: String,

        val entryPath: String,

        val apkTarget: String,

        val failureClass: String,
    )

    const val REASON_ROLLBACKABLE = "可回滚"
    const val REASON_NOT_TAKEN_OVER = "未接管"
    const val REASON_NO_ORIGIN_POINTER = "无来源指针"
    const val REASON_LIBRARY_ENTRY_UNLOCATED = "库形态入口未定位"
    const val REASON_NOT_REGISTERED = "非在册"

    fun rollbackStatus(ctx: Context, id: String): RollbackStatus {
        val pe = lobos.services.reg.ProgramIndex.get(ctx, id)
            ?: return RollbackStatus(
                id, false, REASON_NOT_REGISTERED,
                "注册表里没有 id=" + id + " —— 不在册的件没有回退主体",
                "", "", "", "",
            )
        val role = PieceEntryPointer.roleOf(pe)

        val locatedAll = PieceEntryPointer.locateAll(ctx, pe)
        val located = locatedAll.firstOrNull()
        if (role == ProgramInstallPipeline.ROLE_LIBRARY && locatedAll.pointers.isEmpty()) {
            return RollbackStatus(
                id, false, REASON_LIBRARY_ENTRY_UNLOCATED,
                "F3 库形态入口未定位：落位目录里读不到 " + ProgramInstallPipeline.SHAPE_NAME +
                    " 或其中没有库形态那一条（名字只能从产物那一份取，不拿派生索引顶替）",
                role, "", "", "F3",
            )
        }
        val pointer = located?.pointer
        val origin = PieceOriginStore.get(ctx, id)
        val otaSlots = PieceOriginStore.otaSlotsInCharge(origin)
        if (otaSlots.isEmpty()) {

            return RollbackStatus(
                id, false, REASON_NOT_TAKEN_OVER,
                "F2 未接管：入口软链当前 realpath 不等于来源记录里那一条 ota 槽那一份的 realpath" +
                    "（判据是「入口软链 current realpath == 记下的 ota 槽 body realpath」，" +
                    "不按「是不是软链」判）",
                role, pointer?.absolutePath.orEmpty(), "", "F2",
            )
        }
        val otaSlot = otaSlots.first()
        val apkSlot = PieceOriginStore.apkSlotFor(origin, otaSlot)
        if (apkSlot == null) {
            return RollbackStatus(
                id, false, REASON_NO_ORIGIN_POINTER,
                "F1 缺来源指针：来源记录里没有这一件对应的 APK 原件指针格子" +
                    "（回退的落点必须是那**记下来的**那一条，不猜一个目录）",
                role, pointer?.absolutePath.orEmpty(), "", "F1",
            )
        }
        return RollbackStatus(
            id, true, REASON_ROLLBACKABLE,
            "入口指针当前指着 OTA 那一份（入口软链 realpath == 记下的 ota 槽 body realpath），落点已记下（" +
                apkSlot.targetPath + "）",
            role, pointer?.absolutePath.orEmpty(), apkSlot.targetPath, "",
        )
    }

    fun rollback(ctx: Context, id: String): Pair<Boolean, String?> {
        val status = rollbackStatus(ctx, id)
        if (!status.eligible) {
            return false to (status.reason + "：" + status.detail)
        }
        val pe = lobos.services.reg.ProgramIndex.get(ctx, id) ?: return false to REASON_NOT_REGISTERED

        val locatedAll = PieceEntryPointer.locateAll(ctx, pe)
        if (locatedAll.pointers.isEmpty()) return false to REASON_LIBRARY_ENTRY_UNLOCATED
        val origin = PieceOriginStore.get(ctx, id)
        val otaSlots = PieceOriginStore.otaSlotsInCharge(origin)
        if (otaSlots.isEmpty()) {
            return false to (REASON_NOT_TAKEN_OVER + "：入口指针当前没有指向记下来的 OTA 那一份")
        }
        return try {

            var allSwapped = true
            var allBack = true
            val facts = mutableListOf<String>()
            for (located in locatedAll.pointers) {
                val link = located.pointer
                val entryName = located.name

                val otaSlot = otaSlots.firstOrNull { it.entryPath == link.absolutePath }
                    ?: otaSlots.firstOrNull { it.role == located.role }
                if (otaSlot == null) {
                    allSwapped = false
                    facts.add("入口=" + link.absolutePath + " （来源记录里没有这一条指针的 OTA 槽）")
                    continue
                }
                val apkSlot = PieceOriginStore.apkSlotFor(origin, otaSlot)
                if (apkSlot == null) {
                    allSwapped = false
                    facts.add("入口=" + link.absolutePath + " （来源记录里没有这一条指针的 APK 原件指针格子）")
                    continue
                }
                val apkFile = File(apkSlot.targetPath)
                if (!apkFile.isFile) {
                    allSwapped = false
                    facts.add("入口=" + link.absolutePath + " （记下的 APK 原件已不在：" + apkSlot.targetPath + "）")
                    continue
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
                    allSwapped = false
                    facts.add("入口=" + link.absolutePath + " （软链替换失败）")
                    continue
                }

                val active = runCatching { link.toPath().toRealPath().toString() }.getOrDefault("")
                val want = runCatching { apkFile.toPath().toRealPath().toString() }.getOrDefault("")
                val back = active.isNotBlank() && active == want
                if (!back) allBack = false
                facts.add("入口=" + link.absolutePath + " 目标=" + active +
                    " APK 原件=" + apkFile.absolutePath +
                    (if (back) "（已核对一致）" else "（核对不一致）"))
            }
            val back = allSwapped && allBack
            if (allSwapped) {
                SupplyProvisioner.selectVersion(ctx, id, "")
                PieceOriginStore.markApkActive(ctx, id)
            }

            RuntimeDiagnostics.append(
                ctx, "piece-ota", back,
                "件已回滚到 APK 原件：" + id,
                "软链删除与 provision 重建结果：" + facts.joinToString("；"),
            )
            back to null
        } catch (ex: Throwable) {
            false to (ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun verifyApkRestored(ctx: Context, id: String): Pair<Boolean, String> {
        val pe = lobos.services.reg.ProgramIndex.get(ctx, id)
            ?: return false to REASON_NOT_REGISTERED + "：注册表里没有 id=" + id

        val locatedAll = PieceEntryPointer.locateAll(ctx, pe)
        if (locatedAll.pointers.isEmpty()) {
            return false to REASON_LIBRARY_ENTRY_UNLOCATED + "：入口指针定位不到"
        }
        val origin = PieceOriginStore.get(ctx, id)

        val facts = mutableListOf<String>()
        var allOk = true
        for (located in locatedAll.pointers) {
            val apkSlot = origin?.apkSlots?.firstOrNull {
                it.complete && it.entryPath == located.pointer.absolutePath
            } ?: origin?.apkSlots?.firstOrNull { it.complete && it.role == located.role }
                ?: origin?.apkSlots?.firstOrNull { it.complete }
            if (apkSlot == null) {
                allOk = false
                facts.add("入口=" + located.pointer.absolutePath + " （没有它的 APK 原件指针格子）")
                continue
            }
            val ptrReal = runCatching { located.pointer.toPath().toRealPath().toString() }.getOrNull()
            if (ptrReal == null) {
                allOk = false
                facts.add("入口=" + located.pointer.absolutePath + " （入口指针读不出 realpath）")
                continue
            }
            val bodyReal = runCatching { File(apkSlot.targetPath).toPath().toRealPath().toString() }.getOrNull()
            if (bodyReal == null) {
                allOk = false
                facts.add("入口=" + located.pointer.absolutePath + " （APK 原件读不出 realpath）")
                continue
            }
            val same = ptrReal == bodyReal
            if (!same) allOk = false
            facts.add("入口=" + located.pointer.absolutePath +
                (if (same) "（realpath 与来源记录里记下的 APK 原件一致）" else "（realpath 与来源记录里记下的 APK 原件不一致）"))
        }
        return if (allOk) {
            true to "入口 realpath 与来源记录里记下的 APK 原件一致（" + facts.joinToString("；") + "）"
        } else {
            false to "入口 realpath 与来源记录里记下的 APK 原件不一致（" + facts.joinToString("；") + "）"
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

}
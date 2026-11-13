package lobos.services.app

import android.content.Context
import java.io.File
import lobos.kernel.layout.SystemDirs
import lobos.services.log.RuntimeDiagnostics
import lobos.services.supply.ProgramInstallPipeline

object PreinstalledPieces {

    private const val ASSET_DIR = "pieces"

    private fun trace(ctx: Context, stage: String, detail: String) {
        lobos.services.log.Journal.note(ctx, "prefix-trace", null, stage, detail)
    }

    fun pieceIds(ctx: Context): List<String> =
        runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
            .filter { it.endsWith(".zip") }
            .map { it.substringBeforeLast('.') }
            .sorted()

    fun installOneById(ctx: Context, id: String): Boolean {
        trace(ctx, "装 " + id, "开始")
        val r = try {
            installOne(ctx, id)
        } catch (e: Throwable) {
            trace(ctx, "装 " + id, "抛了 " + e.javaClass.name + ": " + (e.message ?: ""))
            false
        }

        trace(ctx, "装 " + id, if (r) "成" else "败")
        return r
    }

    fun installAll(ctx: Context): List<String> {
        trace(ctx, "进入", "thread=" + Thread.currentThread().name)
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .also { trace(ctx, "assets.list", "结果 " + (it.getOrNull()?.size ?: -1) + " 项，异常=" + (it.exceptionOrNull()?.javaClass?.simpleName ?: "无")) }
            .getOrDefault(emptyList())
        trace(ctx, "assets 列表", names.joinToString(",").take(300))
        if (names.isEmpty()) {
            RuntimeDiagnostics.append(ctx, "prefix", true, "APK 里没有预装件")
            return emptyList()
        }

        val done = mutableListOf<String>()
        var failed = 0

        for (n in names) {
            if (!n.endsWith(".zip")) continue
            val id = n.substringBeforeLast('.')

            val r = installOneById(ctx, id)
            if (r) done += id else failed++
        }

        RuntimeDiagnostics.append(
            ctx, "prefix", failed == 0,
            if (failed == 0) "预装件全部就位（${done.size} 件）"
            else "预装件 $failed 个没装上",
            "已装=" + done.joinToString(",") +
                if (failed > 0) "  失败=" + failed else "",
        )
        runCatching { lobos.kernel.layout.LdConfig.run(ctx) }
            .onFailure {
                RuntimeDiagnostics.append(
                    ctx, "envprobe", false, "环境探针失败",
                    it::class.java.simpleName + ": " + it.message,
                )
            }
        return done
    }

    private fun fail(ctx: Context, id: String, why: String): Boolean {
        RuntimeDiagnostics.append(ctx, "prefix", false, "预装件 $id", why)
        return false
    }

    private data class DerivedCheck(
        val total: Int,
        val okCount: Int,
        val rebuilt: Int,
        val detail: String,
    )

    private fun refreshDerivedLinks(
        ctx: Context,
        id: String,
        version: String,
    ): DerivedCheck {
        val verDir = File(SystemDirs.pieceDir(ctx, id), version)

        val shapeHere = ProgramInstallPipeline.PieceShape.read(verDir)
        val targets = ArrayList<Triple<String, String, String>>()
        if (shapeHere != null) {
            for (r in listOf(ProgramInstallPipeline.ROLE_LIBRARY, ProgramInstallPipeline.ROLE_PROGRAM)) {
                val items = shapeHere.byRole(r)
                if (items.isEmpty()) continue
                val dir = ProgramInstallPipeline.PieceShape.dirForRole(r) ?: continue
                val abs = when (r) {
                    ProgramInstallPipeline.ROLE_PROGRAM -> lobos.kernel.layout.PrefixProvisioner.binDir(ctx).absolutePath
                    else -> lobos.kernel.layout.PrefixProvisioner.libDir(ctx).absolutePath
                }

                val picked = ProgramInstallPipeline.PieceShape.pointerLayerOf(shapeHere, r, verDir)
                val chosen = if (picked.isNotEmpty()) {
                    picked
                } else {

                    emptyList()
                }
                if (chosen.isEmpty()) {

                    if (r == ProgramInstallPipeline.ROLE_PROGRAM && items.size == 1) {
                        val name = items.first().path.substringAfterLast('/')
                        targets.add(Triple(dir + "/" + name, abs, r))
                    }
                    continue
                }
                for (item in chosen) {
                    val name = item.path.substringAfterLast('/')
                    targets.add(Triple(dir + "/" + name, abs, r))
                }
            }
        }
        if (targets.isEmpty()) {
            return DerivedCheck(
                0, 0, 0,
                "落位目录里没有形态说明（" + ProgramInstallPipeline.SHAPE_NAME +
                    "）—— 派生物该建在哪由产物那一份决定（ 而不按某一支兜底）",
            )
        }
        var total = 0
        var ok = 0
        var rebuilt = 0
        val bad = mutableListOf<String>()
        for ((landedRel, dirAbs, role) in targets) {
            val dir = File(dirAbs)
            val name = landedRel.substringAfterLast('/')
            val landed = File(verDir, landedRel).canonicalFile.path
            val link = File(dir, name)

            runCatching {
                lobos.services.supply.PieceOriginStore.recordApkSlot(
                    ctx, id, version, role, link.absolutePath, File(verDir, landedRel).absolutePath,
                )
            }
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && !link.exists()) {

                bad += dir.name + "/" + name + " 缺失（该有而没有）"
                continue
            }
            total++
            val okNow = runCatching {

                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (okNow) { ok++; continue }

            val fresh = File(verDir, landedRel)
            val made = runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                android.system.Os.symlink(fresh.absolutePath, link.absolutePath)
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (made) { ok++; rebuilt++ } else {
                bad += dir.name + "/" + name + " 重算后仍不通"
            }
        }
        return DerivedCheck(total, ok, rebuilt, bad.joinToString("；"))
    }

    private fun installOne(ctx: Context, id: String): Boolean {
        val pkg = extract(ctx, id) ?: return fail(ctx, id, "assets 里取不出 pieces/$id.zip")

        val unpacked = File(ctx.cacheDir, "preinst/u/$id").also { it.deleteRecursively(); it.mkdirs() }
        runCatching { lobos.services.supply.SupplyProvisioner.unzipFromFile(pkg, unpacked, ctx) }
            .onFailure { return fail(ctx, id, "解包失败: " + it.message) }

        val meta = runCatching {
            val f = File(unpacked, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) return fail(ctx, id, "包里没有 component-meta.json（解包后）")
            org.json.JSONObject(f.readText())
        }.getOrNull() ?: return fail(ctx, id, "component-meta.json 解析失败")

        val version = meta.optString("version", "").ifBlank { return fail(ctx, id, "说明里没有 version") }

        val entry = meta.optString("entry", "").trim()

        val shape = ProgramInstallPipeline.PieceShape.read(unpacked)
            ?: return fail(ctx, id, "包里没有 piece-shape.json（产物没带形态）—— 报缺，不探测字节")
        if (entry.isNotBlank() && !File(unpacked, entry).isFile) return fail(ctx, id, "包里没有 entry 指的文件 " + entry)

        val entryRole: String = if (entry.isNotBlank()) {

            shape.systemRole.takeIf { it.isNotBlank() && it != "unknown" }
                ?: return fail(
                    ctx, id,
                    "产物那一份形态说明里没有那系统角色 —— 报缺，不猜" +
                        "（它逐字该从那 component-meta.json 派生、按 C-05 不猜填）",
                )
        } else {

            val verDirForRole = File(
                SystemDirs.pieceDir(ctx, id),
                lobos.services.reg.ProgramIndex.safeSegment(version)
                    ?: return fail(ctx, id, "版本号非法 " + version),
            )
            val libLayer = ProgramInstallPipeline.PieceShape.pointerLayerOf(
                shape, ProgramInstallPipeline.ROLE_LIBRARY, verDirForRole,
            )
            libLayer.firstOrNull()?.role
                ?: return fail(
                    ctx, id,
                    "产物没有带库形态的落位项，而这一件又没有入口 —— 形态是构建期算好的，" +
                        "运行期只读那一格；读不到就报缺，不自己答一个",
                )
        }
        val landedEntry = if (entry.isBlank()) "" else {
            ProgramInstallPipeline.PieceShape.landedRel(shape, entry)
                ?: return fail(ctx, id, "入口 " + entry + " 的形态认不出（role=" + entryRole + "）—— 报缺，不猜")
        }

        val verDir = File(SystemDirs.pieceDir(ctx, id), lobos.services.reg.ProgramIndex.safeSegment(version) ?: return fail(ctx, id, "版本号非法 " + version))

        val landedPresent = if (landedEntry.isBlank()) {
            val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
            libDir.isDirectory && libDir.listFiles()?.any { it.isFile } == true
        } else {
            File(verDir, landedEntry).isFile
        }

        val unverifiable = mutableListOf<String>()

        val staleInLanding = mutableListOf<String>()
        run {
            val declared = shape.files.map { fs ->
                val d = ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir
                d + "/" + fs.path.substringAfterLast('/')
            }.toSet()
            for (roleDir in listOf(
                ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_PROGRAM),
                ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY),
            )) {
                val rd = roleDir ?: continue
                val dir = File(verDir, rd)
                val kids = dir.listFiles() ?: continue
                for (k in kids) {
                    val rel = rd + "/" + k.name
                    if (!declared.contains(rel)) staleInLanding += rel
                }
            }
        }
        if (staleInLanding.isNotEmpty()) {
            lobos.services.log.Journal.note(
                ctx, "prefix-trace", false,
                "落位目录里有产物说没有的那几项 " + id,
                staleInLanding.joinToString("，") +
                    "（一版产物里没有它们，它们是上一版落下的遗留物，" +
                    "按「产物说没有的就不该在」这一条，走重装把它们换掉）",
            )
        }
        val sameBytes = shape.files.all { fs ->
            if (fs.sha256.isBlank()) {
                unverifiable += fs.path + "（产物没带摘要）"
                return@all false
            }
            val dst = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir)
                .let { File(it, fs.path.substringAfterLast('/')) }
            val real = runCatching { dst.canonicalFile }.getOrNull()
            if (real == null) {
                unverifiable += fs.path + "（落位路径算不出来）"
                return@all false
            }
            if (!real.isFile) return@all false
            sha256Of(real) == fs.sha256
        }

        val landedUpToDate = landedPresent && sameBytes && staleInLanding.isEmpty()
        if (unverifiable.isNotEmpty()) {
            lobos.services.log.Journal.note(
                ctx, "prefix-trace", false, "无法判定是不是那一代 " + id,
                unverifiable.joinToString("，") +
                    "（按 §1.55：缺证据不答「相同」这一格不判已落位到最新 走重装）",
            )
        }
        if (verDir.isDirectory && landedUpToDate) {

            val landedMeta = runCatching {
                val j = org.json.JSONObject(meta.toString())
                j.put("entry", landedEntry)
                j
            }.getOrNull()

        val modesLanded = runCatching {
            var n = 0
            for (fs in shape.files) {
                if (fs.mode.isBlank()) continue
                val dst = File(
                    verDir,
                    ProgramInstallPipeline.PieceShape.dirForRole(fs.role) ?: fs.dir,
                ).let { File(it, fs.path.substringAfterLast('/')) }
                if (!dst.isFile) continue
                val m = runCatching { fs.mode.toInt(8) }.getOrNull() ?: continue
                runCatching { android.system.Os.chmod(dst.absolutePath, m) }
                n++
            }
            n
        }.getOrDefault(0)
        if (modesLanded == 0 && shape.files.isNotEmpty()) {
            trace(ctx, "落权限", id + " 一项也没落（产物没带 mode）—— 而运行期不替产物决定权限")
        }
    val manifestWritten = runCatching {
                lobos.services.supply.ManifestWriter.write(
                    verDir, landedMeta, version, entryRole
                )
            }
        val shapeRefreshed = runCatching {
            val src = File(unpacked, ProgramInstallPipeline.SHAPE_NAME)
            if (!src.isFile) {
                false
            } else {
                val dst = File(verDir, ProgramInstallPipeline.SHAPE_NAME)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                true
            }
        }.getOrDefault(false)
        if (!shapeRefreshed) {
            trace(ctx, "刷新 " + ProgramInstallPipeline.SHAPE_NAME,
                id + " 失败（快路径）：产物那一份不在 —— 而建链会报缺，不猜")
        }
        val metaRefreshed = runCatching {
            val j = landedMeta ?: return@runCatching false
            val dst = File(verDir, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            dst.parentFile?.mkdirs()
            dst.writeText(j.toString(1) + "\n")
            true
        }.getOrDefault(false)
        if (!metaRefreshed) {
            trace(ctx, "刷新 " + lobos.kernel.layout.PrefixProvisioner.META_NAME,
                id + " 失败（快路径）：落位目录那份仍是旧的那一份")
        }
val derived = refreshDerivedLinks(ctx, id, version)
            trace(ctx, "校验派生物软链", id + " 共=" + derived.total + " 对=" + derived.okCount +
                " 重算=" + derived.rebuilt + " " + derived.detail)
            runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }

            trace(ctx, "写 program-manifest.json", id + " 成=" + manifestWritten.isSuccess +
                " 目标=" + verDir.absolutePath + "/" + lobos.services.supply.ProgramDir.MANIFEST_NAME +
                (if (manifestWritten.isSuccess) "" else " 失败=" + manifestWritten.exceptionOrNull()))

            trace(ctx, "装 " + id, "登记前")
            register(ctx, id, version, relStateDir(id), landedEntry, entryRole, verDir, landedMeta)
            return true
        }
        val r = ProgramInstallPipeline.install(
            ctx,
            ProgramInstallPipeline.Spec(
                from = ProgramInstallPipeline.From.APP,
                programId = id,
                zip = pkg,
                shape = ProgramInstallPipeline.Shape.COMPONENT,
                expectedVersion = version,
                expectedEntryRel = entry,
            ),
        )
        runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }
        if (!r.ok) {
            RuntimeDiagnostics.append(
                ctx, "prefix", false, "预装件 $id 装不上",
                (r.reason ?: "") + " — " + r.detail,
            )
            return false

        val pkgBuild = runCatching {
            val f = File(unpacked, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")
        val landedBuild = runCatching {
            val f = File(verDir, lobos.kernel.layout.PrefixProvisioner.META_NAME)
            if (!f.isFile) "" else org.json.JSONObject(f.readText()).optString("buildSha", "").trim()
        }.getOrDefault("")

        val pkgIsLocal = pkgBuild.equals("local", ignoreCase = true)
        val landedIsLocal = landedBuild.equals("local", ignoreCase = true)
        when {
            pkgBuild.isBlank() || landedBuild.isBlank() || pkgIsLocal || landedIsLocal -> {
                val missing = listOfNotNull(
                    "产物那份".takeIf { pkgBuild.isBlank() || pkgIsLocal },
                    "落位目录那份".takeIf { landedBuild.isBlank() || landedIsLocal },
                ).joinToString(" 与 ")
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", null, "报缺：无法判定是不是这一代 " + id,
                    missing + "没有 buildSha 那一格 —— 而「缺」既不是「= APK 那一代」， 也不是「= 未知的那一代」",
                )
            }
            pkgBuild != landedBuild -> {
                lobos.services.log.Journal.note(
                    ctx, "prefix-trace", false, "不同代：不是这一次构建的那一份 " + id,
                    "产物 buildSha=" + pkgBuild + "、落位 buildSha=" + landedBuild +
                        " —— 只报，不拦落位（构建标识是观察点，逐项摘要才是闸门）",
                )
            }
        }

        }
        return true
    }

    private fun sha256Of(f: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun libFileNameOf(verDir: File): String {
        val libDir = File(verDir, ProgramInstallPipeline.PieceShape.dirForRole(ProgramInstallPipeline.ROLE_LIBRARY) ?: "lib")
        val files = libDir.listFiles()?.filter { it.isFile } ?: return ""
        val primary = files.firstOrNull { it.name.endsWith(".so") }
        return (primary ?: files.firstOrNull())?.name ?: ""
    }

    private fun relStateDir(id: String): String =
        lobos.kernel.layout.SystemDirs.relPieceDir(id)

    private fun extract(ctx: Context, id: String): File? = runCatching {
        val dst = File(ctx.cacheDir, "preinst/$id.zip")
        dst.parentFile?.mkdirs()
        ctx.assets.open("$ASSET_DIR/$id.zip").use { ins ->
            dst.outputStream().use { ins.copyTo(it) }
        }
        dst.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun levelOf(role: String): lobos.services.reg.Level =
        if (role == ProgramInstallPipeline.ROLE_PROGRAM) lobos.services.reg.Level.PROGRAM
        else lobos.services.reg.Level.PIECE

    private fun register(
        ctx: Context,
        id: String,
        version: String,
        stateDir: String,
        entry: String,
        role: String,

        verDir: File,
        meta: org.json.JSONObject?,
    ) {
        val prev = lobos.services.reg.ProgramIndex.get(ctx, id)
        val isProgram = role == ProgramInstallPipeline.ROLE_PROGRAM

        val isLibrary = role == ProgramInstallPipeline.ROLE_LIBRARY
        runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.services.reg.ProgramIndex.empty(id, levelOf(role))).copy(
                    id = id,
                    version = version,

                    stateDir = stateDir,
                    enabled = true,
                    desired = lobos.services.reg.Desired.RUNNING,

                    libName = if (entry.isNotBlank()) entry.substringAfterLast("/") else libFileNameOf(verDir),

                    assetEntry = if (isLibrary) "" else entry,
                    role = role,
                    required = meta?.optBoolean("essential", false) ?: false,
                    capabilities = emptyList(),
                    requires = (meta?.optJSONArray("requires") ?: org.json.JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    },
                ),
            )
        }.onFailure {
            RuntimeDiagnostics.append(
                ctx, "prefix", false, "预装件 $id 登记失败",
                it::class.java.simpleName + ": " + it.message,
            )
        }
    }
}

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
            trace(ctx, "装 " + id, "开始")
            val r = try {
                installOne(ctx, id)
            } catch (e: Throwable) {
                trace(ctx, "装 " + id, "抛了 " + e.javaClass.name + ": " + (e.message ?: ""))
                false
            }
            trace(ctx, "装 " + id, if (r) "成" else "败")
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

    /** 校验这一件的派生物软链，有一条不对就当场重算它。
     *
     *  缺陷（C-102）① 落位检查只验原物在不在、派生物指向哪不管
     *  ② 软链是派生物而落位跳过已存在项，派生物因此永不刷新
     *  ③ link 成功不等于目标可达
     *
     *  判据：每一条「指向这一件」的软链，目标必须存在，且必须落在落位目录里。
     *  真机读数（07，ls -l）：libbusybox.so / liblobosjq.so / libssl.so 的目标是
     *  files/data/user/0/lobos.os/files/opt/<id>/<ver>/<ver>/<entry> —— 前缀两遍、版本段两遍，
     *  而 linkcrypto/libcurl/libz 那三行是干净的。跨多版装机不变，因为这一支只验原物。 */
    private fun refreshDerivedLinks(ctx: Context, id: String, version: String, entry: String): DerivedCheck {
        val verDir = File(SystemDirs.pieceDir(ctx, id), version)
        val landed = File(verDir, entry).canonicalFile.path
        val primary = entry.substringAfterLast("/")
        val dirs = if (entry.startsWith("lib/")) {
            listOf(lobos.kernel.layout.PrefixProvisioner.libDir(ctx))
        } else {
            listOf(lobos.kernel.layout.PrefixProvisioner.binDir(ctx))
        }
        var total = 0
        var ok = 0
        var rebuilt = 0
        val bad = mutableListOf<String>()
        for (dir in dirs) {
            val link = File(dir, primary)
            if (!java.nio.file.Files.isSymbolicLink(link.toPath()) && !link.exists()) continue
            total++
            val okNow = runCatching {
                // readSymbolicLink() 在 java.nio.file.Path 上，不在 java.io.File 上。
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (okNow) { ok++; continue }
            val fresh = File(verDir, entry)
            val made = runCatching {
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                android.system.Os.symlink(fresh.absolutePath, link.absolutePath)
                val t = File(java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
                t.isFile && t.canonicalFile.path == landed
            }.getOrDefault(false)
            if (made) { ok++; rebuilt++ } else {
                bad += dir.name + "/" + primary + " 重算后仍不通"
            }
        }
        return DerivedCheck(total, ok, rebuilt, bad.joinToString("；"))
    }

    private fun installOne(ctx: Context, id: String): Boolean {
        val pkg = extract(ctx, id) ?: return fail(ctx, id, "assets 里取不出 pieces/$id.zip")

        val unpacked = File(ctx.cacheDir, "preinst/u/$id").also { it.deleteRecursively(); it.mkdirs() }
        runCatching { lobos.services.supply.SupplyProvisioner.unzipFromFile(pkg, unpacked) }
            .onFailure { return fail(ctx, id, "解包失败: " + it.message) }

        val meta = runCatching {
            val f = File(unpacked, "component-meta.json")
            if (!f.isFile) return fail(ctx, id, "包里没有 component-meta.json（解包后）")
            org.json.JSONObject(f.readText())
        }.getOrNull() ?: return fail(ctx, id, "component-meta.json 解析失败")

        val version = meta.optString("version", "").ifBlank { return fail(ctx, id, "说明里没有 version") }
        val entry = meta.optString("entry", "").ifBlank { return fail(ctx, id, "说明里没有 entry") }
        if (!File(unpacked, entry).isFile) return fail(ctx, id, "包里没有 entry 指的文件 " + entry)

        val verDir = File(SystemDirs.pieceDir(ctx, id), lobos.services.reg.ProgramIndex.safeSegment(version) ?: return fail(ctx, id, "版本号非法 " + version))
        if (verDir.isDirectory && File(verDir, entry).isFile) {
            // 缺陷（C-102）① 落位检查只验原物在不在、派生物指向哪不管
            //        ② 软链是派生物而落位跳过已存在项，派生物因此永不刷新
            // ⇒ 这一支原来只验 verDir/<entry>（原物）在，就 register 并 return true；
            //   而 usr/bin 与 usr/lib 下指向这一件的软链是**派生物**，坏链就永远留在这儿。
            //   真机读数（07）：libbusybox.so / liblobosjq.so / libssl.so 三条坏的，跨多版装机不变。
            // ⇒ return 之前先校验派生物；有一条不对就重算它。
            val derived = refreshDerivedLinks(ctx, id, version, entry)
            trace(ctx, "校验派生物软链", id + " 共=" + derived.total + " 对=" + derived.okCount +
                " 重算=" + derived.rebuilt, derived.detail)
            runCatching { pkg.deleteRecursively() }
        runCatching { unpacked.deleteRecursively() }
            val manifestWritten = runCatching {
                lobos.services.supply.ManifestWriter.write(
                    verDir, meta, version, lobos.services.supply.PieceScan.roleFromDisk(ctx, id)
                )
            }
            trace(ctx, "写 program-manifest.json", id + " 成=" + manifestWritten.isSuccess +
                " 目标=" + verDir.absolutePath + "/" + lobos.services.supply.ProgramDir.MANIFEST_NAME +
                (if (manifestWritten.isSuccess) "" else " 失败=" + manifestWritten.exceptionOrNull()))
            register(ctx, id, version, verDir.absolutePath, entry, meta)
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
        }
        return true
    }

    private fun extract(ctx: Context, id: String): File? = runCatching {
        val dst = File(ctx.cacheDir, "preinst/$id.zip")
        dst.parentFile?.mkdirs()
        ctx.assets.open("$ASSET_DIR/$id.zip").use { ins ->
            dst.outputStream().use { ins.copyTo(it) }
        }
        dst.takeIf { it.length() > 0 }
    }.getOrNull()

    private fun levelOf(meta: org.json.JSONObject): lobos.services.reg.Level {
        if (meta.optString("kind", "").equals("program", true)) {
            return lobos.services.reg.Level.PROGRAM
        }
        return lobos.services.reg.Level.PIECE
    }

    private fun register(
        ctx: Context,
        id: String,
        version: String,
        stateDir: String,
        entry: String,
        meta: org.json.JSONObject,
    ) {
        val prev = lobos.services.reg.ProgramIndex.get(ctx, id)
        runCatching {
            lobos.services.reg.ProgramIndex.upsert(
                ctx,
                (prev ?: lobos.services.reg.ProgramIndex.empty(id, levelOf(meta))).copy(
                    id = id,
                    version = version,
                    stateDir = stateDir,
                    enabled = true,
                    desired = lobos.services.reg.Desired.RUNNING,
                    assetEntry = entry,
                    role = meta.optString("form", ""),
                    required = meta.optBoolean("essential", false),
                    capabilities = emptyList(),
                    requires = (meta.optJSONArray("requires") ?: org.json.JSONArray()).let { arr ->
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
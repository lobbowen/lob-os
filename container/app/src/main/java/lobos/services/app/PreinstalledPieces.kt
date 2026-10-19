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
package lobos.services.app

import android.content.Context
import java.io.File
import lobos.kernel.layout.SystemDirs
import lobos.services.log.RuntimeDiagnostics
import lobos.services.supply.ProgramInstallPipeline

object PreinstalledPieces {

    private const val ASSET_DIR = "pieces"

    fun installAll(ctx: Context): List<String> {
        val names = runCatching { ctx.assets.list(ASSET_DIR)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
        if (names.isEmpty()) {
            RuntimeDiagnostics.append(ctx, "prefix", true, "APK 里没有预装件")
            return emptyList()
        }

        val done = mutableListOf<String>()
        var failed = 0

        for (n in names) {
            if (!n.endsWith(".tar.gz") && !n.endsWith(".tgz")) continue
            val id = n.substringBeforeLast('.')
            val r = installOne(ctx, id)
            if (r) done += id else failed++
        }

        RuntimeDiagnostics.append(
            ctx, "prefix", failed == 0,
            if (failed == 0) "预装件全部就位（${done.size} 件）"
            else "预装件 $failed 个没装上",
            "已装=" + done.joinToString(",") +
                if (failed > 0) "  失败=" + failed else "",
        )
        return done
    }

    private fun installOne(ctx: Context, id: String): Boolean {
        val pkg = extract(ctx, id) ?: return false

        val meta = runCatching {
            val f = File(pkg, "component-meta.json")
            if (!f.isFile) return false
            org.json.JSONObject(f.readText())
        }.getOrNull() ?: return false

        val version = meta.optString("version", "").ifBlank { return false }
        val entry = meta.optString("entry", "").ifBlank { return false }
        if (!File(pkg, entry).isFile) return false

        val verDir = File(SystemDirs.pieceDir(ctx, id), lobos.services.reg.ProgramIndex.safeSegment(version) ?: return false)
        if (verDir.isDirectory && File(verDir, entry).isFile) {
            runCatching { pkg.deleteRecursively() }
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
        val dst = File(ctx.cacheDir, "preinst/$id.tar.gz")
        dst.parentFile?.mkdirs()
        ctx.assets.open("$ASSET_DIR/$id.tar.gz").use { ins ->
            dst.outputStream().use { ins.copyTo(it) }
        }
        dst.takeIf { it.length() > 0 }
    }.getOrNull()

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
                (prev ?: lobos.services.reg.ProgramIndex.empty(id, lobos.services.reg.Level.PIECE)).copy(
                    id = id,
                    version = version,
                    stateDir = stateDir,
                    enabled = true,
                    assetEntry = entry,
                    role = meta.optString("form", "exec"),
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
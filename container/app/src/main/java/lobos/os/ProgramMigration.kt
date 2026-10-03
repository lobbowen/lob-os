package lobos.os

import android.content.Context
import java.io.File

object ProgramMigration {

    private const val RETIRED = ".migrated"

    data class Report(
        val migrated: Int,
        val fromFacility: Int,
        val fromApp: Int,
        val infra: Int,
        val capability: Int,
        val channel: Int,
        val application: Int,
    ) {
        fun toLine(): String =
            "总计=$migrated 基础设施=$infra 能力件=$capability 通道=$channel 应用程序=$application" +
                "（设施表 $fromFacility 条 / 启停表 $fromApp 条）"
    }

    fun needed(ctx: Context): Boolean {
        if (ProgramIndex.file(ctx).isFile) return legacyStateDirs(ctx)
        return legacyFacilityFile(ctx).isFile || legacyAppFile(ctx).isFile || ProgramRegistry.listIds(ctx).isNotEmpty()
    }

    private fun legacyStateDirs(ctx: Context): Boolean =
        ProgramIndex.all(ctx).any { e ->
            e.stateDir.isNotBlank() && e.stateDir != ProgramRegistry.PROGRAMS_DIR + "/" + e.id
        }

    private fun legacyFacilityFile(ctx: Context) = File(File(ctx.filesDir, "sys"), "registry.json")

    private fun legacyAppFile(ctx: Context) = File(File(ctx.filesDir, "os"), "programs.json")

    @Synchronized
    fun run(ctx: Context): Report? {
        if (ProgramIndex.file(ctx).isFile) return relocateStateDirs(ctx)
        if (!needed(ctx)) return null
        val out = linkedMapOf<String, IndexEntry>()

        val fromFacility = migrateFacilities(ctx, out)
        val fromApp = migrateApps(ctx, out)

        ProgramIndex.replaceAll(ctx, out.values.toList())
        retire(ctx, legacyFacilityFile(ctx))
        retire(ctx, legacyAppFile(ctx))

        val report = Report(
            migrated = out.size,
            fromFacility = fromFacility,
            fromApp = fromApp,
            infra = out.values.count { it.level == Level.INFRA },
            capability = out.values.count { it.level == Level.CAPABILITY },
            channel = out.values.count { it.level == Level.CHANNEL },
            application = out.values.count { it.level == Level.APPLICATION },
        )
        Journal.note(ctx, "index", true, "程序索引迁移完成", report.toLine())
        return report
    }

    private fun migrateFacilities(ctx: Context, out: LinkedHashMap<String, IndexEntry>): Int {
        val f = legacyFacilityFile(ctx)
        if (!f.isFile) return 0
        val arr = StateFiles.readJson(f)?.optJSONArray("facilities") ?: return 0
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optString("name", "")
            if (name.isBlank()) continue
            val kindRaw = e.optString("kind", "").trim().uppercase()
            val level = when (kindRaw) {
                "INFRA" -> Level.INFRA
                "RUNTIME" -> Level.CAPABILITY
                "CHANNEL" -> Level.CHANNEL
                else -> Level.CAPABILITY
            }
            val category = when (kindRaw) {
                "RUNTIME" -> Category.RUNTIME
                "INFRA", "CHANNEL" -> Category.NONE
                else -> Category.TOOLCHAIN
            }
            val stateDir = e.optString("stateDir", "")
            out[name] = ProgramIndex.empty(name, level).copy(
                category = category,
                origin = e.optString("source", "apk").ifBlank { "apk" },
                version = e.optString("version", ""),
                enabled = e.optBoolean("enabled", true),
                stateDir = stateDir,
                deps = e.optJSONArray("deps")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                sha256 = e.optString("sha256", ""),
                tier = e.optString("tier", "base"),
                libName = e.optString("libName", ""),
                assetEntry = e.optString("entry", ""),
            )
        }
        return out.size
    }

    private fun migrateApps(ctx: Context, out: LinkedHashMap<String, IndexEntry>): Int {
        val desiredById = legacyDesired(ctx)
        var n = 0
        for (spec in ProgramRegistry.list(ctx)) {
            out[spec.id] = ProgramIndex.empty(spec.id, Level.APPLICATION).copy(
                category = Category.APPLICATION,
                origin = "store",
                version = spec.version,
                enabled = true,
                stateDir = ProgramRegistry.programRoot(ctx).name + "/" + spec.id,
                tier = "optional",
                role = spec.role,
                resident = spec.resident,
                restart = spec.restart,
                maxRestarts = spec.maxRestarts,
                backoffMs = spec.backoffMs,
                capabilities = spec.capabilities,
                requires = spec.requires,
                env = spec.env,
                httpPort = spec.http?.port ?: 0,
                httpHealth = spec.http?.health ?: "",
                desired = desiredById[spec.id] ?: Desired.STOPPED,
                invalid = spec.invalid,
            )
            n++
        }
        return n
    }

    private fun legacyDesired(ctx: Context): Map<String, Desired> {
        val f = legacyAppFile(ctx)
        if (!f.isFile) return emptyMap()
        val root = StateFiles.readJson(f) ?: return emptyMap()
        val names = root.names() ?: return emptyMap()
        val out = mutableMapOf<String, Desired>()
        for (i in 0 until names.length()) {
            val id = names.optString(i)
            val raw = root.optJSONObject(id)?.optString("desired", "STOPPED") ?: "STOPPED"
            runCatching { Desired.valueOf(raw) }.getOrNull()?.let { out[id] = it }
        }
        return out
    }

    private fun relocateStateDirs(ctx: Context): Report? {
        val want = ProgramRegistry.PROGRAMS_DIR
        val moved = mutableListOf<String>()
        for (e in ProgramIndex.all(ctx)) {
            if (e.level == Level.INFRA) continue
            val old = e.stateDir
            if (old.isBlank() || old == want + "/" + e.id) continue
            val from = File(ctx.filesDir, old)
            val to = File(ctx.filesDir, want, e.id)
            if (from.isDirectory && !to.exists()) {
                val ok = (to.parentFile?.mkdirs() == true) && from.renameTo(to)
                if (!ok) {
                    Journal.note(ctx, "index", false, "旧状态目录搬迁失败，保留原位", "id=" + e.id + " from=" + old)
                    continue
                }
            } else if (to.isDirectory) {
                runCatching { from.deleteRecursively() }
            }
            ProgramIndex.upsert(ctx, e.copy(stateDir = want + "/" + e.id))
            moved += e.id
        }
        if (moved.isEmpty()) return null
        val after = ProgramIndex.all(ctx)
        Journal.note(
            ctx, "index", true, "状态目录统一到 " + want,
            "搬=" + moved.joinToString(",") + " 共=" + after.size +
                " 基础设施=" + after.count { it.level == Level.INFRA } +
                " 能力件=" + after.count { it.level == Level.CAPABILITY } +
                " 通道=" + after.count { it.level == Level.CHANNEL } +
                " 应用程序=" + after.count { it.level == Level.APPLICATION },
        )
        return null
    }

    private fun retire(ctx: Context, f: File) {
        if (!f.isFile) return
        runCatching { f.renameTo(File(f.parentFile, f.name + RETIRED)) }
    }
}

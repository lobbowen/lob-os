package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

import lobos.ota.ProgramInstaller
import lobos.ota.ProgramOtaUpdater
import lobos.runtime.InstalledRuntime

object ProgramManager {

    data class Reality(
        val id: String,
        val level: Level,
        val installed: Boolean,
        val version: String,
        val evidence: String,
    )

    data class Snapshot(
        val updatedAt: Long,
        val entries: List<IndexEntry>,
        val realities: Map<String, Reality>,
    ) {
        val installedCount: Int get() = realities.values.count { it.installed }

        fun toJson(): JSONObject = JSONObject().apply {
            put("updatedAt", updatedAt)
            put("total", entries.size)
            put("installedCount", installedCount)
            put("byLevel", JSONObject().apply {
                for (l in Level.entries) put(l.name, entries.count { it.level == l })
            })
            put("entries", JSONArray().apply {
                for (e in entries.sortedBy { it.id }) {
                    val r = realities[e.id]
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("level", e.level.name)
                        put("category", e.category.name)
                        put("asApplication", e.asApplication)
                        put("enabled", e.enabled)
                        put("declaredVersion", e.version)
                        put("currentVersion", r?.version ?: "")
                        put("installed", r?.installed ?: false)
                        put("desired", e.desired.name)
                        put("deps", JSONArray(e.deps))
                        put("stateDir", e.stateDir)
                        put("tier", e.tier)
                        put("asApplication", e.asApplication)
                        put("evidence", r?.evidence ?: "")
                        if (e.level == Level.APPLICATION) {
                            put("role", e.role)
                            put("resident", e.resident)
                            put("restart", e.restart.name)
                            put("maxRestarts", e.maxRestarts)
                            put("capabilities", JSONArray(e.capabilities))
                            put("httpPort", e.httpPort)
                            put("invalid", e.invalid ?: JSONObject.NULL)
                        }
                    })
                }
            })
        }
    }

    fun stateDirOf(ctx: Context, id: String): File {
        val e = ProgramIndex.get(ctx, id)
        if (e != null) {
            if (e.level == Level.INFRA) return infraSourceFile(ctx, e)
            if (e.stateDir.isNotBlank()) return File(ctx.filesDir, e.stateDir)
        }
        return File(ProgramRegistry.programRoot(ctx), id)
    }

    fun infraSourceFile(ctx: Context, e: IndexEntry): File =
        if (e.libName.isNotBlank()) File(ctx.applicationInfo.nativeLibraryDir, e.libName)
        else SystemDirs.usr(ctx).let { File(it, e.assetEntry.ifBlank { e.id }) }
    fun levelOfKind(kind: String): Level = when (kind) {
        "INFRA" -> Level.INFRA
        "RUNTIME", "COMPONENT" -> Level.CAPABILITY
        "CHANNEL" -> Level.CHANNEL
        else -> Level.CAPABILITY
    }

    fun stateRoot(ctx: Context): File = ProgramIndex.root(ctx)

    fun relStateDir(id: String, kind: String): String =
        if (kind.trim().uppercase() == "INFRA") "" else ProgramRegistry.PROGRAMS_DIR + "/" + id

    fun probe(ctx: Context, e: IndexEntry): Reality {
        if (e.level == Level.INFRA) {
            val src = infraSourceFile(ctx, e)
            return Reality(e.id, e.level, src.isFile, "", src.absolutePath)
        }
        val d = stateDirOf(ctx, e.id)
        val version = if (e.stateDir.isNotBlank()) {
            runCatching { ProgramDir(ctx, e.id, d).currentVersion() }.getOrNull().orEmpty()
        } else {
            ""
        }
        val versionDir = version.takeIf { it.isNotBlank() }?.let { File(d, it) }
        return Reality(
            id = e.id,
            level = e.level,
            installed = versionDir?.isDirectory == true,
            version = version.ifBlank { e.version },
            evidence = versionDir?.absolutePath ?: d.absolutePath,
        )
    }

    @Synchronized
    fun snapshot(ctx: Context): Snapshot {
        val entries = ProgramIndex.all(ctx)
        val realities = entries.associate { it.id to probe(ctx, it) }
        return Snapshot(System.currentTimeMillis(), entries, realities)
    }

    @Synchronized
    fun status(ctx: Context): JSONArray = snapshot(ctx).toJson().optJSONArray("entries") ?: JSONArray()

    @Synchronized
    fun reconcile(ctx: Context) {
        alignLevels(ctx)
        val snap = snapshot(ctx)
        val stateFile = File(ProgramIndex.file(ctx).parentFile ?: SystemDirs.libvar(ctx), "program-state.json")
        StateFiles.writeJson(
            stateFile,
            JSONObject().apply {
                put("updatedAt", snap.updatedAt)
                put("total", snap.entries.size)
                put("installedCount", snap.installedCount)
                put("entries", snap.toJson().optJSONArray("entries") ?: JSONArray())
            },
        )
        val missing = snap.entries.filter { snap.realityOf(it)?.installed == false }
        if (missing.isNotEmpty()) {
            Journal.note(
                ctx, "program", false, "设施缺失（登记与实物不一致）",
                "缺=" + missing.joinToString(",") { it.id },
            )
        }
    }

    private fun Snapshot.realityOf(e: IndexEntry): Reality? = realities[e.id]

    private fun alignLevels(ctx: Context) {
        val fixed = mutableListOf<String>()
        for (e in ProgramIndex.all(ctx)) {
            val want = levelFor(ctx, e) ?: continue
            if (e.level == want) continue
            ProgramIndex.upsert(ctx, e.copy(level = want))
            fixed += e.id + "→" + want.name
        }
        if (fixed.isNotEmpty()) {
            Journal.note(ctx, "index", null, "层级与实物不一致，已纠正", "改=" + fixed.joinToString(","))
        }
    }

    private fun levelFor(ctx: Context, e: IndexEntry): Level? = when {
        e.asApplication -> Level.APPLICATION
        else -> levelOfKind(CatalogClient.entryFor(ctx, e.id)?.optString("kind", "").orEmpty())
    }

    fun dirOf(ctx: Context, id: String): ProgramDir = ProgramDir(ctx, id, stateDirOf(ctx, id))

    fun currentVersion(ctx: Context, id: String): String? =
        runCatching { dirOf(ctx, id).currentVersion() }.getOrNull()

fun nodeBin(ctx: Context): File? = InstalledRuntime.binOf(ctx, "node")

    fun assemble(ctx: Context) {
        val enabled = ProgramIndex.all(ctx).filter { it.enabled }
        val usr = SystemDirs.usr(ctx)
        usr.mkdirs()
        StateFiles.writeJson(File(usr, "facilities.json"), JSONObject().apply {
            put("updatedAt", System.currentTimeMillis())
            put("facilities", JSONArray(enabled.map { it.id }))
        })
        val cur = File(usr, "current")
        cur.mkdirs()
        for (e in enabled) {
            if (e.level == Level.INFRA) continue
            val version = currentVersion(ctx, e.id) ?: continue
            val target = File(stateDirOf(ctx, e.id), version)
            if (!target.isDirectory) continue
            val link = File(cur, e.id)
            runCatching {
                if (link.exists()) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
            }
        }
        Journal.note(ctx, "program", null, "装配视图已更新（usr/）", "启用=" + enabled.joinToString(",") { it.id })
    }

    @Synchronized
    fun setEnabled(ctx: Context, id: String, enabled: Boolean): Boolean {
        if (!ProgramIndex.mutate(ctx, id) { it.copy(enabled = enabled) }) return false
        Journal.note(ctx, "program", null, if (enabled) "启用设施" else "停用设施", "name=" + id)
        reconcile(ctx)
        return true
    }

    @Synchronized
    fun resolveHttpPort(ctx: Context, id: String, declared: Int): Int {
        if (declared > 0) return declared
        return PortBroker.claim(ctx, id)
    }

    fun setDesired(ctx: Context, id: String, d: Desired): Boolean {
        if (!ProgramIndex.mutate(ctx, id) { it.copy(desired = d) }) return false
        Journal.append(ctx, "registry", null, "upsert " + id + " desired=" + d.name)
        return true
    }
}

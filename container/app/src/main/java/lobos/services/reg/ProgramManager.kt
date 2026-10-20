package lobos.services.reg

import android.content.Context
import java.io.File
import lobos.services.log.Journal
import org.json.JSONArray
import org.json.JSONObject

import lobos.services.supply.ProgramInstaller
import lobos.services.supply.ProgramOtaUpdater
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles
import lobos.services.supply.ProgramDir
import lobos.services.supervise.UnitJobs

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
        val entries: List<UnitEntry>,
        val realities: Map<String, Reality>,
    ) {
        val installedCount: Int get() = realities.values.count { it.installed }

        fun toJson(ctx: Context): JSONObject = JSONObject().apply {
            put("updatedAt", updatedAt)
            put("total", entries.size)
            put("installedCount", installedCount)
            put("byLevel", JSONObject().apply {
                for (l in Level.entries) put(l.name, entries.count { it.levelOf(ctx) == l })
            })
            put("entries", JSONArray().apply {
                for (e in entries.sortedBy { it.id }) {
                    val r = realities[e.id]
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("level", e.levelOf(ctx).name)
                        put("enabled", e.enabled)
                        put("declaredVersion", e.version)
                        put("currentVersion", r?.version ?: "")
                        put("installed", r?.installed ?: false)
                        put("desired", e.desired.name)
                        put("requires", JSONArray(e.requires))
                        put("stateDir", e.stateDir)
                        put("required", e.required)
                        put("evidence", r?.evidence ?: "")
                        if (e.levelOf(ctx) == Level.PROGRAM) {
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

    fun dirOf(ctx: Context, id: String): ProgramDir = ProgramDir(ctx, id)

    fun currentVersion(ctx: Context, id: String): String? =
        runCatching { dirOf(ctx, id).currentVersion() }.getOrNull()

    /** 位置只从登记的格子读（C-102）：入口格空  它只有落位目录（库/头）；
     *  入口格有值且 stateDir 有值  那是程序的数据目录。不再问「它是什么形态」。 */
    fun stateDirOf(ctx: Context, id: String): File {
        val e = ProgramIndex.get(ctx, id)
        if (e != null) {
            if (e.assetEntry.isBlank()) return infraSourceFile(ctx, e)
            if (e.stateDir.isNotBlank()) return File(ctx.filesDir, e.stateDir)
        }
        return File(ProgramRegistry.programRoot(ctx), id)
    }

    fun infraSourceFile(ctx: Context, e: UnitEntry): File =
        SystemDirs.opt(ctx).let { File(it, e.id) }
    /** 形态只读登记的两格（C-102）：不调 isPiece，也不再走一次磁盘探测。 */
    fun levelOf(ctx: Context, id: String): Level =
        ProgramIndex.get(ctx, id)?.levelOf(ctx) ?: Level.PIECE

    private fun realityOf(ctx: Context, e: UnitEntry): Reality? {
        val dir = stateDirOf(ctx, e.id)
        // 「落位在哪」由登记格回答（stateDirOf 已经只读格）；这里只做存在性核对，不判形态。
        val landed = dir.isDirectory
        val version = if (landed) {
            runCatching { dirOf(ctx, e.id).currentVersion() }.getOrNull().orEmpty()
        } else ""
        return Reality(
            id = e.id,
            level = e.levelOf(ctx),
            installed = landed,
            version = version,
            evidence = if (landed) "落位=" + dir.absolutePath else "落位缺失=" + dir.absolutePath,
        )
    }

    fun stateRoot(ctx: Context): File = ProgramIndex.root(ctx)

    /** 只有程序有 usr 下的视图；库/头（入口格空）没有（C-102：读格，不判形态）。 */
    fun relStateDir(ctx: Context, id: String): String {
        val e = ProgramIndex.get(ctx, id) ?: return ""
        if (e.assetEntry.isBlank()) return ""
        return SystemDirs.relPieceDir(id)
    }

    @Synchronized
    fun snapshot(ctx: Context): Snapshot {
        val entries = ProgramIndex.all(ctx)
        val realities = entries.mapNotNull { e -> realityOf(ctx, e)?.let { e.id to it } }.toMap()
        return Snapshot(System.currentTimeMillis(), entries, realities)
    }

    @Synchronized
    fun status(ctx: Context): JSONArray = snapshot(ctx).toJson(ctx).optJSONArray("entries") ?: JSONArray()

    @Synchronized
    fun reconcile(ctx: Context) {
        val snap = snapshot(ctx)
        val stateFile = File(ProgramIndex.file(ctx).parentFile ?: SystemDirs.libvar(ctx), "program-state.json")
        StateFiles.writeJson(
            stateFile,
            JSONObject().apply {
                put("updatedAt", snap.updatedAt)
                put("total", snap.entries.size)
                put("installedCount", snap.installedCount)
                put("entries", snap.toJson(ctx).optJSONArray("entries") ?: JSONArray())
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

    private fun Snapshot.realityOf(e: UnitEntry): Reality? = realities[e.id]

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
            if (e.assetEntry.isBlank()) continue   // C-102：无入口格者不参与 usr/ 装配
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
        if (!ProgramIndex.mutate(ctx, id) { it.edited(enabled = enabled) }) return false
        Journal.note(ctx, "program", null, if (enabled) "启用设施" else "停用设施", "name=" + id)
        reconcile(ctx)
        return true
    }

    @Synchronized
    fun resolveHttpPort(ctx: Context, id: String, declared: Int): Int {
        if (declared > 0) return declared
        return PortBroker.claim(ctx, id)
    }

    fun requestDesired(ctx: Context, id: String, d: Desired, reason: String = ""): String? {
        val cur = ProgramIndex.get(ctx, id)?.desired
        if (cur == d) return null                      // 已经是这个期望，不排
        return when (val v = UnitJobs.enqueue(
            ctx,
            UnitJobs.Job(id, d, reason.ifBlank { "requested " + d.name }),
        )) {
            is UnitJobs.Verdict.Ok -> {
                Journal.note(ctx, "job", true, "入队 " + id + " → " + d.name, reason)
                null
            }
            is UnitJobs.Verdict.Reject -> {
                Journal.note(ctx, "job", false, "拒绝 " + id + " → " + d.name, v.why)
                v.why
            }
        }
    }

    fun setDesired(ctx: Context, id: String, d: Desired): Boolean =
        requestDesired(ctx, id, d) == null
}

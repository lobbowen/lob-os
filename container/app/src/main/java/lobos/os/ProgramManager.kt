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
                        put("enabled", e.enabled)
                        put("declaredVersion", e.version)
                        put("currentVersion", r?.version ?: "")
                        put("installed", r?.installed ?: false)
                        put("desired", e.desired.name)
                        put("requires", JSONArray(e.requires))
                        put("stateDir", e.stateDir)
                        put("required", e.required)
                        put("evidence", r?.evidence ?: "")
                        if (e.level == Level.PROGRAM) {
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
            if (e.piece != null) return infraSourceFile(ctx, e)
            if (e.stateDir.isNotBlank()) return File(ctx.filesDir, e.stateDir)
        }
        return File(ProgramRegistry.programRoot(ctx), id)
    }

    fun infraSourceFile(ctx: Context, e: IndexEntry): File =
        if (e.libName.isNotBlank()) File(ctx.applicationInfo.nativeLibraryDir, e.libName)
        else SystemDirs.usr(ctx).let { File(it, e.assetEntry.ifBlank { e.id }) }
    /**
     * 这个 id 是什么 —— **判据是它自己的 role**（注册表里声明），不读包外清单的 kind。
     *
     * 此前是 levelOfKind(kind: String) 把 "INFRA"/"RUNTIME"/"CHANNEL" 映射过来，
     * 于是「它是什么」由一份可被替换的外部清单决定 —— 换清单就换分类。
     *
     * 只有两类：系统文件（件）与应用程序。件按 role 细分落位，不进 opt/。
     */
    /**
     * 这是件还是程序 —— **看注册表条目的形状**，不去问盘。
     *
     * 注册表里分[PieceEntry] 与 [ProgramEntry] 两种条目，`piece != null` 就是答案。
     * 用 pieceDir 判等于「去磁盘确认它在不在」，那是 verify() 的事。
     */
    fun levelOf(ctx: Context, id: String): Level =
        if (ProgramIndex.get(ctx, id)?.piece != null) Level.PIECE else Level.PROGRAM

    fun stateRoot(ctx: Context): File = ProgramIndex.root(ctx)

    /**
     * 落位规则 —— **判据是件自己的 role，不是包外清单里的 kind 字符串**。
     *
     * 此前这里比的是 `kind == "INFRA"`，而 kind 来自包外清单（可被换掉），
     * 于是「装哪」由外部声明决定 —— 与「包里说它是什么就是什么」相反。
     *
     * 件（role=library/exec/shell/multi-command/headers）落usr/lib/<id>/<版本>/，
     * 已经在 PieceUpdater 与 Provisioner 里铺好了，不占 opt/；
     * 程序（走安装链的 zip）落 opt/<id>/。
     */
    /** 件不占 opt/（它落在 usr/lib/<id>/<版本>/）；程序占 —— 判据是注册表条目的形状 */
    fun relStateDir(ctx: Context, id: String): String {
        if (ProgramIndex.get(ctx, id)?.piece != null) return ""
        return SystemDirs.REL_OPT + "/" + id
    }


    @Synchronized
    fun snapshot(ctx: Context): Snapshot {
        val entries = ProgramIndex.all(ctx)
        val realities = entries.associate { it.id to realityOf(ctx, it) }
        return Snapshot(System.currentTimeMillis(), entries, realities)
    }

    @Synchronized
    fun status(ctx: Context): JSONArray = snapshot(ctx).toJson().optJSONArray("entries") ?: JSONArray()

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


        /**
     * 层级与实物对齐：它在注册表里是 PieceEntry 就是件，否则是程序。
     * 判据是**注册表条目的形状**，不是任何字段里的名字。
     */

fun nodeBin(ctx: Context): File? = InstalledRuntime.binOf(ctx, InstalledRuntime.programRuntime(ctx).id)

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
            if (e.piece != null) continue
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

    fun setDesired(ctx: Context, id: String, d: Desired): Boolean {
        if (!ProgramIndex.mutate(ctx, id) { it.edited(desired = d) }) return false
        Journal.append(ctx, "registry", null, "upsert " + id + " desired=" + d.name)
        return true
    }
}

package lobos.os

import android.content.Context
import java.io.File
import lobos.log.Journal
import org.json.JSONArray
import org.json.JSONObject

import lobos.ota.ProgramInstaller
import lobos.ota.ProgramOtaUpdater
import lobos.runtime.InstalledRuntime
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles

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

    /**
     * 程序（或件）的落位目录句柄。
     *
     * CapabilityBroker 与 LobosBridge 都要它，此前两处各自转发到一个
     * 不存在的 ProgramManager.dirOf。放这里是因为「目录从哪来」是
     * 注册表的职责 —— 与 stateDirOf 同源，不是桥的职责。
     */
    fun dirOf(ctx: Context, id: String): ProgramDir = ProgramDir(ctx, id)

    /**
     * 当前版本 —— CURRENT 指针指到的那一版，没有则 null。
     *
     * CatalogClient / InstalledRuntime / QuickAppBinder / LobosBridge /
     * QuickAppRegistry 五处都要「这个 id 现在装的是哪一版」，此前各自转发到一个
     * 不存在的 ProgramManager.currentVersion。放这里，与 dirOf 同源。
     */
    fun currentVersion(ctx: Context, id: String): String? =
        runCatching { dirOf(ctx, id).currentVersion() }.getOrNull()

    fun stateDirOf(ctx: Context, id: String): File {
        val e = ProgramIndex.get(ctx, id)
        if (e != null) {
            if (ProgramIndex.isPiece(e)) return infraSourceFile(ctx, e)
            if (e.stateDir.isNotBlank()) return File(ctx.filesDir, e.stateDir)
        }
        return File(ProgramRegistry.programRoot(ctx), id)
    }

    fun infraSourceFile(ctx: Context, e: UnitEntry): File =
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
        if (ProgramIndex.isPiece(ctx, id)) Level.PIECE else Level.PROGRAM

    /**
     * 注册表条目 ↔ 盘上实物 —— 「声明了什么」与「实际是什么」的差。
     *
     * Reality 的每个字段都从既有的事实源取，不另存副本：
     * 层级取注册表条目的形状（levelOf 的同一判据），版本取 CURRENT 指针，
     * 就位与否看落位目录在不在，evidence 是把两者拼成一句人话。
     */
    private fun realityOf(ctx: Context, e: UnitEntry): Reality? {
        val dir = stateDirOf(ctx, e.id)
        val landed = when {
            ProgramIndex.isPiece(e) -> infraSourceFile(ctx, e).exists()
            else -> dir.isDirectory
        }
        val version = if (landed) {
            runCatching { dirOf(ctx, e.id).currentVersion() }.getOrNull().orEmpty()
        } else ""
        return Reality(
            id = e.id,
            level = e.level,
            installed = landed,
            version = version,
            evidence = if (landed) "落位=" + dir.absolutePath else "落位缺失=" + dir.absolutePath,
        )
    }

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
        if (ProgramIndex.isPiece(ctx, id)) return ""
        return SystemDirs.REL_OPT + "/" + id
    }


    @Synchronized
    fun snapshot(ctx: Context): Snapshot {
        val entries = ProgramIndex.all(ctx)
        val realities = entries.mapNotNull { e -> realityOf(ctx, e)?.let { e.id to it } }.toMap()
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

    private fun Snapshot.realityOf(e: UnitEntry): Reality? = realities[e.id]


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
            if (ProgramIndex.isPiece(e)) continue
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

    /**
     * 改期望状态 —— **排一个作业，不是直接改字段**。
     *
     * 照抄 systemd(1)：「these requests are ENCAPSULATED AS JOBS and maintained
     * in a job queue … their execution is ORDERED BASED ON THE ORDERING
     * DEPENDENCIES of the units they have been scheduled for」。
     *
     * 直接改的后果有三个：请求还没执行状态就已经变了 · 依赖顺序没保证 ·
     * 循环依赖检测不到。现在先入队（[UnitJobs.enqueue] 会做事务校验），
     * 由 [UnitJobs.takeReady] 按after 依赖出队执行。
     *
     * @return 拒绝理由（null = 已入队）
     */
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

    /** 旧接口保留一层转发 —— 新代码用 requestDesired（语义是「排队」不是「改」） */
    fun setDesired(ctx: Context, id: String, d: Desired): Boolean =
        requestDesired(ctx, id, d) == null
}

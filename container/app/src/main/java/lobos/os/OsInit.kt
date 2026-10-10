package lobos.os

import android.content.Context
import java.io.File
import lobos.log.Journal
import lobos.quickapp.ProgramGroup
import org.json.JSONObject

object OsInit {

    private fun dir(ctx: Context): File = SystemDirs.libvar(ctx)
    private const val FILE = "state.json"

    @Volatile
    private var interruptedThisLife: String? = null

    private fun file(ctx: Context): File {
        val d = dir(ctx)
        d.mkdirs()
        return File(d, FILE)
    }

    @Synchronized
    fun current(ctx: Context): OsPhase = snapshot(ctx).phase

    @Synchronized
    fun snapshot(ctx: Context): OsSnapshot {
        val obj = runCatching { JSONObject(file(ctx).readText()) }.getOrNull()
            ?: return OsSnapshot(OsPhase.BOOTING, OsFacts(), interruptedThisLife, 0L)
        val phase = OsPhase.values().firstOrNull { it.name == obj.optString("phase") } ?: OsPhase.BOOTING
        val f = obj.optJSONObject("facts")
        val facts = if (f == null) OsFacts() else OsFacts(
            readingsCollected = f.optBoolean("readingsCollected"),
            controlPlaneUp = f.optBoolean("controlPlaneUp"),
        )
        return OsSnapshot(
            phase = phase,
            facts = facts,
            interrupted = interruptedThisLife,
            atMs = obj.optLong("at", 0L),
        )
    }

    private fun write(ctx: Context, snap: OsSnapshot, previous: OsPhase, note: String?) {
        val obj = JSONObject().apply {
            put("phase", snap.phase.name)
            put("label", snap.phase.label)
            put("previous", previous.name)
            put("at", snap.atMs)
            if (!note.isNullOrBlank()) put("note", note)
            put("facts", JSONObject().apply {
                put("readingsCollected", snap.facts.readingsCollected)
                put("controlPlaneUp", snap.facts.controlPlaneUp)
                put("channel", snap.facts.channel.name)
            })
            snap.interrupted?.let { put("interrupted", it) }
        }
        runCatching { lobos.os.StateFiles.writeAtomic(file(ctx), obj.toString(2)) }
    }

    @Synchronized
    fun beginLife(ctx: Context, interrupted: String?): OsSnapshot {
        val stalled = snapshot(ctx).phase
        interruptedThisLife = interrupted
        SystemDirs.ensureAll(ctx)
        // 索引从落位推导（等价 ldconfig 扫目录建缓存）——
        // 「系统里有什么」由文件系统说了算，不由安装器是否记得写登记说了算
        val scanned = PieceScan.rebuild(ctx)

  // 前后端成组对账—— systemd 的 daemon-reload 是重新读 unit 文件，
  // 我们是重新对一遍「登记的 UI 绑定」与「实际装进 dimina 的」
  runCatching { lobos.quickapp.ProgramGroup.reconcile(ctx) }
        // Linux 的 /run 是 tmpfs，重启即失由内核保证；我们在普通文件系统上，
        // 所以「本次启动的状态不继承上世」要在这里自己保证。
        // 不清的话：上世的 pid 占着端口、上世的会话以为还活着。
        val swept = SystemDirs.clearRun(ctx)
        val snap = OsSnapshot(OsPhase.BOOTING, OsFacts(), interrupted, System.currentTimeMillis())
        write(ctx, snap, OsPhase.BOOTING, null)
        Journal.append(
            ctx, "os-phase", null,
            "宿主出生：本世从 BOOTING 起算（上世停在 " + stalled.name + "，那份读数不继承）",
        )
        if (scanned > 0) {
            Journal.append(ctx, "os-run", null, "索引已从落位重建：$scanned 项")
        }
        if (swept > 0) {
            Journal.append(ctx, "os-run", null, "已清 /run：$swept 项（本次启动不继承上世运行态）")
        }
        return snap
    }

    @Synchronized
    fun transition(ctx: Context, phase: OsPhase, note: String?, interrupted: String?): OsPhase {
        val prev = snapshot(ctx)
        val now = System.currentTimeMillis()
        interruptedThisLife = interrupted
        write(ctx, prev.copy(phase = phase, atMs = now, interrupted = interrupted), prev.phase, note)
        Journal.append(ctx, "os-phase", null, prev.phase.name + " -> " + phase.name + (note?.let { "（" + it + "）" } ?: ""))
        return phase
    }

    @Synchronized
    fun refresh(ctx: Context, facts: OsFacts, interrupted: String?): OsSnapshot {
        val prev = snapshot(ctx)
        val next = OsPhaseRule.next(prev.phase, facts)
        val now = System.currentTimeMillis()
        interruptedThisLife = interrupted
        val snap = prev.copy(
            phase = next ?: prev.phase,
            facts = facts,
            interrupted = interrupted,
            atMs = if (next != null) now else prev.atMs,
        )
        write(ctx, snap, prev.phase, next?.let { OsPhaseRule.reason(it, facts) })
        if (next != null) {
            Journal.append(
                ctx, "os-phase", null,
                prev.phase.name + " -> " + next.name + "（" + OsPhaseRule.reason(next, facts) + "）",
            )
        }
        return snap
    }

    @Synchronized
    fun statusLine(ctx: Context): String {
        val s = snapshot(ctx)
        val runtime = when {
            !s.facts.readingsCollected -> "状态采集中…"
            s.facts.controlPlaneUp -> "运行时在线"
            else -> "运行时未响应"
        }
        val programs = ProgramStatusHub.snapshot(ctx)
        val running = programs.count { it.active == UnitState.Active.ACTIVE }
        val prefix = s.interrupted?.let { it + " · " } ?: ""
        return prefix + "Lob OS · " + s.phase.label + " · " + runtime +
            " · " + running + "/" + programs.size + " 个程序在跑"
    }}

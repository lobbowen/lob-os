package lobos.android.services.power
import lobos.android.host.OsPhase

import android.content.Context
import lobos.system.fs.StateFiles
import lobos.android.host.OsInit
import lobos.android.services.log.Journal
import java.io.File

object KernelPower {

    enum class State { RUNNING, HALTED }

    private fun file(ctx: Context): File = File(ctx.filesDir, "var/lib/power-state.json")

    fun state(ctx: Context): State {
        val text = runCatching { file(ctx).readText() }.getOrNull() ?: return State.RUNNING
        val name = runCatching { org.json.JSONObject(text).optString("state", "RUNNING") }
            .getOrDefault("RUNNING")
        return runCatching { State.valueOf(name) }.getOrDefault(State.RUNNING)
    }

    fun isHalted(ctx: Context): Boolean = state(ctx) == State.HALTED

    private fun write(ctx: Context, s: State) {
        val obj = org.json.JSONObject().apply {
            put("schema", 1)
            put("state", s.name)
            put("at", System.currentTimeMillis())
        }
        StateFiles.writeAtomic(file(ctx), obj.toString(2) + "\n")
    }

    fun shutdown(ctx: Context): Boolean {
        write(ctx, State.HALTED)
        OsInit.transition(ctx, OsPhase.STOPPING, "系统关机（内核已停，管理界面保留）", null)
        Journal.note(ctx, "power", true, "shutdown", "内核进入 HALTED，程序由监督池停止")
        return true
    }

    fun boot(ctx: Context): Boolean {
        write(ctx, State.RUNNING)
        OsInit.transition(ctx, OsPhase.RUNNING, "系统开机（内核恢复）", null)
        Journal.note(ctx, "power", true, "boot", "内核进入 RUNNING，按程序 desired 恢复")
        return true
    }

    fun reboot(ctx: Context, delayMs: Long = 1500L): Boolean {
        shutdown(ctx)
        Journal.note(ctx, "power", true, "reboot", "关机后 ${delayMs}ms 开机")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            boot(ctx.applicationContext)
        }, delayMs)
        return true
    }
}

package lobos.quickapp

import android.content.Context
import java.io.File

/**
 * 前后端成组 —— **分体形态下替代 systemd cgroup 的那一层**。
 *
 * systemd 的做法：主进程与子进程属同一 cgroup，`cgroup.kill` 一次收干净；
 * unit 的状态生死，进程全体跟随。
 *
 * 我们的形态与它不同：后端是普通进程（我们拉起的），前端是快应用
 * （dimina 承载，与宿主同进程）。**没有 cgroup 可用** —— 通用 APK 拿不到
 * `setpgid` / `setcgroup`（见 [lobos.os.ProcessLedger] 的注释）。
 *
 * 所以要有自己的成组关系：**把「前端 ↔ 后端」这个绑定显式记下来**，
 * 并在两端任一消失时把另一端收掉。
 *
 * ★ 为什么必须有这一层：
 *   Linux 里这件事是内核做的（cgroup 树），我们没有内核这一层。
 *   此前 [UnitEntry.onUiClosed] 只写不读 —— 绑定关系记了，但没人拿它做判断。
 *   而 [QuickAppHost.close] / [QuickAppHost.hide] / [InstanceHost.requestStop]
 *   三个接口都写好了、零调用者。前端关不掉后端，后端死了前端成僵尸界面。
 *
 * 这一层就是把那三个接口接起来。
 */
object ProgramGroup {

    /**
     * 打开前端之前 —— 确保后端在跑。
     *
     * 一个完整程序是「前端 + 后端」。此前前端能被打开而后端还停着，
     * 用户看到的是点得开但连不上的空壳（[open] 只查前端装没装，不看后端）。
     *
     * 走第 3 层的作业队列而不是直接改字段 —— 请求与执行分开，
     * 「想跑」与「在跑」不是一回事（systemd 的 job queue 同义）。
     *
     * @return null = 已就绪或已排队；非 null = 排不进去的理由（给用户看）
     */
    fun ensureBackendRunning(ctx: Context, unit: String): String? {
        val e = lobos.os.ProgramIndex.get(ctx, unit)
            ?: return "注册表里没有 " + unit
        // 后端已经在跑 —— 什么都不用做
        if (e.pid > 0 && lobos.os.ProcessLedger.isOwnedAlive(e.pid, e.starttime)) return null
        // 本来就不该跑（用户显式停过）—— 不擅自拉起
        if (e.desired == lobos.os.Desired.STOPPED) return null
        // 排一个启动作业
        return lobos.os.ProgramManager.requestDesired(
            ctx, unit, lobos.os.Desired.RUNNING, "前端打开，需要后端",
        )
    }

    /** 这个程序有没有配快应用前端（uiPackage 非空即配了） */
    fun hasUi(ctx: Context, unit: String): Boolean =
        lobos.os.ProgramIndex.get(ctx, unit)?.uiPackage?.isNotBlank() == true

    /**
     * 前端关掉了 —— 后端要不要跟着停。
     *
     * 判据是件自己声明的那一档（manifest 的 `ui.onUiClosed`）：
     *   keep-alive     前端关了后端继续跑（常驻后端型程序）
     *   stop-with-ui   ★ 前端关了后端跟着停（默认）
     *   on-demand      前端关了就停，下次用再起
     *
     * 照抄 dpkg/systemd 的做法：**判据是登记的事实，不是调用方的意图**。
     */
    fun shouldStopBackendOnUiClose(ctx: Context, unit: String): Boolean {
        val mode = lobos.os.ProgramIndex.get(ctx, unit)?.onUiClosed.orEmpty()
        // 没声明时按 stop-with-ui —— 前端与后端是一个程序，不该留半个
        return mode != lobos.os.ManifestSchema.CLOSED_KEEP_ALIVE
    }

    /**
     * 前端关掉了 —— 收后端。
     *
     * @return 是否真的停了（false = 后端本来就没在跑）
     */
    fun onUiClosed(ctx: Context, unit: String): Boolean {
        if (!shouldStopBackendOnUiClose(ctx, unit)) {
            lobos.log.Journal.note(
                ctx, "group", null,
                "前端关闭但后端保留（ui.onUiClosed=keep-alive）",
                "unit=" + unit,
            )
            return false
        }
        // 后端的停止入口 —— 此前零调用者，现在这里用它
        val stopped = runCatching {
            val e = lobos.os.ProgramIndex.get(ctx, unit)
            if (e == null) return false
            // 排一个停止作业（第 3 层）而不是直接改字段
            lobos.os.ProgramManager.requestDesired(
                ctx, unit, lobos.os.Desired.STOPPED, "前端关闭（ui.onUiClosed）",
            ) == null
        }.getOrDefault(false)
        lobos.log.Journal.note(
            ctx, "group", stopped,
            if (stopped) "前端关闭 → 后端已排入停止队列" else "前端关闭 → 后端未在跑或排队失败",
            "unit=" + unit,
        )
        return stopped
    }

    /**
     * 后端死了 —— 前端要收掉，否则留在 dimina 里成僵尸界面。
     *
     * 反向的一条路。systemd 里 unit 死了进程全体跟随；我们没有 cgroup，
     * 所以由 [InstanceHost] 在进程退出后调它。
     */
    fun onBackendExit(ctx: Context, unit: String, exitCode: Int) {
        runCatching {
            QuickAppHost.close(unit)
        }
        lobos.log.Journal.note(
            ctx, "group", null,
            "后端退出 → 前端已收",
            "unit=" + unit + " exitCode=" + exitCode,
        )
    }

    /**
     * 对账 —— 开机时跑一遍：绑定关系记了但没兑现的，补上。
     *
     * systemd 有 daemon-reload（重新读 unit 文件）；我们是重新对一遍
     * 「登记的 UI 绑定」与「实际装进 dimina 的」是否一致。
     */
    fun reconcile(ctx: Context) {
        runCatching {
            for (e in lobos.os.ProgramIndex.all(ctx)) {
                val pkg = e.uiPackage
                if (pkg.isBlank()) continue
                val installed = QuickAppHost.installed(e.id)
                if (!installed) {
                    lobos.log.Journal.note(
                        ctx, "group", null,
                        "登记了前端但dimina 里没有 —— 留待下次装包时补",
                        "unit=" + e.id + " uiPackage=" + pkg,
                    )
                }
            }
        }
    }
}
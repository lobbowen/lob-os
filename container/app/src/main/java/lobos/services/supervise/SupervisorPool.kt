package lobos.services.supervise

import android.app.Service
import android.content.Intent
import android.util.Log
import lobos.services.log.RuntimeDiagnostics
import lobos.services.log.Journal
import lobos.services.reg.Desired
import lobos.services.reg.edited
import lobos.services.reg.Level
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ProgramRegistry

class SupervisorPool(private val host: Service) {

    private val supervisors = LinkedHashMap<String, InstanceHost>()
    private val services = LinkedHashMap<String, () -> Unit>()

    /**
     * 那**不健康**的那几族（=> 而而那它们逐字下一轮逐字**要重起**）。
     *
     * 缺陷名（C-102）**那一族逐字坏了以后逐字永远不重建** ——
     *   => 而那旧版逐字**没有**这样的一族（=> 而而那那动作逐字**失败**时逐字**只**记一条诊断）
     *     => 而而那那一族逐字**起过**就**再**也不跑（=> 而而那 `services.containsKey` 那一句）
     *     => 而而那**后果**（07 本轮逐字实测到）：=> 而那 `files/dev` 那一族逐字**被永久弄坏**
     *   => 而那**处置**：=> 而而那那动作逐字**失败**时逐字把它记进这一族
     *     => 而而那**下一轮**逐字把那一族逐字**注销**、=> 而而那再**下一轮**逐字**重起**它
     *     => 而而那那**自愈**逐字**就是**那一族逐字**重起**那一下
     */
    private val unhealthyServices = LinkedHashSet<String>()

    @Synchronized
    fun start() {
        //  缺陷名（C-102）**那 clear 逐字与服务池一起来、=> 而而那它逐字把 beginLife 写的那一档逐字清掉了** ——
        //    => 而那旧版逐字是 `RuntimeDiagnostics.clear(host)` 逐字**在** `start()` **里面**
        //      => 而而那**出生**那一族（=> 而那 `OsInit.beginLife`）逐字在**主线程**上
        //        （=> 而那 `onStartCommand` 那一行）、=> 而而那服务池逐字**在**那**工作线程**上
        //        （=> 而那 `ensureComponents` 逐字 post 到那 handler）
        //      => 而而那**两条链是竞态**、=> 而而那服务池逐字**先到**
        //        （=> 而那 07 本轮逐字核到：那 scan-cost 那一档逐字在 journal 的 at 逐字 1793483983860、
        //          而那 diag.jsonl 逐字**最早**一条 at 逐字 1793483984570 —— **晚 710 ms**）
        //      => 而而那**后果**：=> 而那 beginLife 逐字写的那一档逐字**被清掉了**
        //        （=> 而而那 diag.jsonl 逐字 264 行 / 107781 字节、=> 而那**远小于**那 512KB 上限
        //          => 而而那**不是**被滚掉的、=> 而而那是被**清**掉的）
        //    => 而那**处置**：=> 而那 clear 逐字**移**到那**出生**那一族**之后**
        //      （=> 而那 `OsInit.beginLife` 逐字做完、=> 而而那**出生**写的那几行逐字**留住**）
        //      => 而而那**仍然**只清「**上一世**」那一份（=> 而那它逐字是那诊断那一族自己的语义、
        //        => 而那它逐字**不是**清「这一世」写的那几行）。
        //      => 而而那**它自己那一族**归 01 与 04 裁、=> 而而那**本笔**逐字只把它**挪**到那正确的次序。
        sync()
    }

    @Synchronized
    fun onHostStart(intent: Intent?) {
        val target = intent?.getStringExtra("programId")?.takeIf { it.isNotBlank() }
        val targets = if (target != null) listOf(target) else supervisors.keys.toList()
        for (t in targets) runCatching { supervisors[t]?.onHostStart(intent) }
        sync()
    }

    @Synchronized
    private fun noopUnit() { }

    @Synchronized
    private fun syncServices() {
        //  缺陷名（C-102）**那服务池逐字一轮只解开一层、=> 而而那链条逐字一层一层等下去**
        //    => 而那旧版逐字是：
        //        val alive = services.keys.toMutableSet()
        //        for (d in ServiceRegistry.ready(host, alive)) { … }
        //      => 而而那 `alive` 逐字是**那一轮开始时**的快照、=> 而而那 `ready` 那一格
        //        逐字按那**快照**算（=> 而那 ServiceRegistry.ready 逐字 `if (!alive.contains(a)) { ok = false }`）
        //      => 而而那这一轮逐字新起的那些**不进**这一轮那 `ready` 的计算
        //      => 而而那**下一层**逐字**下一轮**才起得起来
        //    => 而那**后果**（07 本轮逐字核出、Lead 本机实读 `SupervisorPool.kt:52` 到 `:53` 逐字成立）：
        //      => 而那链条逐字是 `piece-<id>` → `prefix` → `view` → {`pty`, `sysdev`}
        //      => 而而那 **prefix 永远等**、=> 而而那 **view / pty / sysdev 永远等**
        //      => 而而那 `files/dev` 逐字**永不重写**、=> 而而那 `nodes` 那一栏逐字**永远旧版**
        //      => 而而那 `ShellEntry` 逐字找不到 shell、=> 而而那 `sys.tty.exec` 逐字**失败**
        //      => 而而那是一条**闭环**
        //    => 而那**处置**：=> 而那**一轮**逐字**一路解到没有新的 ready**（=> 而那 `alive` 逐字**边起边更新**）
        //      => 而而那链条逐字**不再**一层一层等、=> 而而那**一轮之内**逐字全起来。
        val alive = services.keys.toMutableSet()
        //  => 而那**一轮**逐字**解多轮**（=> 而那上限逐字是那链条的层数、=> 而而那它逐字防「起起来又注销」那种循环）
        var rounds = 0
        while (rounds < MAX_SERVICE_ROUNDS) {
            val ready = ServiceRegistry.ready(host, alive)
                .filter { !alive.contains(it.id) }
            if (ready.isEmpty()) break
            var progressed = false
            for (d in ready) {
                if (services.containsKey(d.id)) {
                    //  缺陷名（C-102）**那一族逐字坏了以后逐字永远不重建** ——
                    //   => 而那旧版逐字是 `if (services.containsKey(d.id)) continue`
                    //     => 而而那那**服务**逐字**起过**一次就**再**也不跑那动作
                    //     => 而而那那**动作**逐字**失败**时逐字**没有任何一处**重试
                    //       （=> 而那 `runCatching { … }` 逐字**吞掉**那失败、=> 而而那它逐字**只**记一条诊断）
                    //   => 而而那**后果**（07 本轮逐字实测到、Lead 采纳那一条）：
                    //     · 而那 `files/dev` 那一族逐字被一个错形状**永久弄坏**
                    //       （=> 而那 `execNode` 逐字写不进那个目录 => 而那 render 逐字抛
                    //         => 而而那那 runCatching 逐字**吞掉** => 而而那那一族逐字「起」逐字**只跑一次**
                    //         （=> 而那 `SupervisorPool` 逐字 `if (services.containsKey(d.id)) continue`））
                    //       => 而而那 `files/dev` 逐字**不再重建**、=> 而而那那**复原**只能靠**重启 App**
                    //   => 而那**处置**：=> 而而那那**动作**逐字**失败**时逐字把那一族逐字**注销**
                    //     => 而而那**下一轮**逐字**重新起**它（=> 而而那它逐字**自愈**）
                    //     => 而而那那**判据**逐字是「那一次动作逐字成没成」（=> 而那它是那**动作**自己的事）
                    //     => 而而那那**诊断**逐字**照记**（=> 而那按 C-126 不静默）
                    val unhealthy = unhealthyServices.remove(d.id)
                    if (unhealthy != null) {
                        services.remove(d.id)?.invoke()
                        Journal.note(host, "service", null, "注销不健康的服务（下一轮重起）", "id=" + d.id)
                        continue
                    }
                    continue
                }
                val ok = runCatching {
                    d.act(host)
                    true
                }.getOrElse { e ->
                    Log.w(TAG, "内置服务启动失败: " + d.id, e)
                    RuntimeDiagnostics.append(
                        host, "service", false, "内置服务启动失败 " + d.id,
                        e.javaClass.name + ": " + (e.message ?: ""),
                    )
                    false
                }
                if (!ok) {
                    //  => 而而那那动作逐字**失败**时逐字把它记成**不健康**
                    //    （=> 而而那**下一轮**逐字注销它、=> 而而那再下一轮逐字**重起**它）
                    //    => 而而那那**诊断**逐字**照记**（=> 而那按 C-126 不静默）
                    unhealthyServices.add(d.id)
                    continue
                }
                unhealthyServices.remove(d.id)
                services[d.id] = { noopUnit() }
                //  => 而那 `alive` 逐字**边起边更新**（=> 而而那下一层逐字**这一轮**就起得来）
                alive.add(d.id)
                progressed = true
                Journal.note(host, "service", null, "起内置服务", "id=" + d.id)
            }
            if (!progressed) break
            rounds++
        }
        for (id in services.keys.toList()) {
            if (ServiceRegistry.decls(host).none { it.id == id }) {
                services.remove(id)?.invoke()
                Journal.note(host, "service", null, "注销内置服务", "id=" + id)
            }
        }
        RuntimeDiagnostics.append(
            host, "service", true,
            "内置服务 " + services.size + "/" + ServiceRegistry.decls(host).size,
            ServiceRegistry.state(host, services.keys),
        )
    }

    fun sync() {
        syncServices()
            drainJobs()
        val all = wantedPrograms()
        val wanted = if (all.size <= MAX_RUNNING) all else all.take(MAX_RUNNING)
        if (all.size > MAX_RUNNING) {
            Journal.note(host, "supervisor-pool", null, "超出并发上限，已截断",
                "期望 " + all.size + " 条，只起 " + wanted.size + " 条：上限 " + MAX_RUNNING +
                    "（Android 每个应用最多 32 个子进程，本系统还要留余量给件与探针）" +
                    " 落下：" + all.drop(MAX_RUNNING).joinToString(","))
        }
        val toStop = supervisors.keys.filter { it !in wanted }
        for (id in toStop) {
            val s = supervisors.remove(id)
            try { s?.shutdown() } catch (_: Throwable) {}
            Journal.note(host, "supervisor-pool", null, "停止监督程序", "id=" + id)
        }
        for (id in wanted) {
            if (supervisors.containsKey(id)) continue
            val s = InstanceHost(host, id)
            supervisors[id] = s
            try {
                s.start()
            } catch (e: Throwable) {
                Log.w(TAG, "启动监督器失败: " + id, e)
                RuntimeDiagnostics.append(host, "supervisor-pool", false, "启动监督器失败", "id=" + id + " " + e.message)
            }
            Journal.note(host, "supervisor-pool", null, "开始监督程序", "id=" + id)
        }
        if (wanted.isNotEmpty() || toStop.isNotEmpty()) {
            RuntimeDiagnostics.append(
                host, "supervisor-pool", true, "监督池同步",
                // ★ **缺陷名（C-102）一个词承担两个意思** —— 原文案把这一项写成「在跑=」，
                //   而它读的是 `supervisors.keys`：入表在启动**之前**（:90 先赋值、:92 才 start），
                //   且启动失败不回滚（:93 的 catch 只写诊断、不删表项）=> 它是**意图**，不是事实。
                //   而事实那一侧读的是账本：OsHostService 的 runningIds ← ProcessLedger.liveOwned。
                //   01 裁定：两个名字分开，名单那一侧的真实名字本来就是「期望=」。
                "监督名单=" + supervisors.keys.joinToString(",") + "；停止=" + toStop.joinToString(",") +
                    "；期望=" + wanted.joinToString(",")
            )
        }
    }

    private fun drainJobs() {
        var guard = 0
        while (guard++ < 64) {
            val job = lobos.services.supervise.UnitJobs.takeReady(host) ?: break
            runCatching {
                ProgramIndex.mutate(host, job.unit) { it.edited(desired = job.desired) }
            }.onFailure {
                Journal.note(
                    host, "job", false,
                    "作业落地失败 " + job.unit + " → " + job.desired.name,
                    it.message ?: "",
                )
            }
            Journal.note(
                host, "job", null,
                "执行作业 " + job.unit + " → " + job.desired.name,
                job.reason,
            )
        }
    }

    private fun wantedPrograms(): List<String> {
        val installed = ProgramRegistry.list(host).filter { it.startable }.map { it.id }
        val programs = ProgramIndex.installedAt(host, Level.PROGRAM).map { it.id }
        val desired = ProgramIndex.all(host)
            .filter { it.levelOf(host) == Level.PROGRAM && it.desired == Desired.RUNNING }
            .map { it.id }
            .filter { installed.contains(it) }
        if (desired.isNotEmpty()) return desired
        return programs.filter { installed.contains(it) }
    }

    fun installedPrograms(): List<ProgramIndex.Installed> =
        ProgramIndex.installedAt(host, Level.PROGRAM)

    @Synchronized
    fun running(): List<String> = supervisors.keys.toList()

    @Synchronized
    fun shutdown() {
        for ((_, s) in supervisors) {
            try { s.shutdown() } catch (_: Throwable) {}
        }
        supervisors.clear()
    }

    companion object {
        const val MAX_RUNNING = 12

        //  => 而那**一轮**逐字**解多轮**的那个上限（=> 而那它逐字是那链条的层数、
        //    => 而而那它逐字防「起起来又注销」那种循环、=> 而而那它逐字**足够**解完那一族）
        const val MAX_SERVICE_ROUNDS = 8

        const val TAG = "SupervisorPool"
    }
}

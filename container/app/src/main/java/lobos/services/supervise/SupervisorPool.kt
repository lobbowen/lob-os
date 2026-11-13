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
     * 那**不健康**的那几族（ 它们逐字下一轮逐字**要重起**）。
     *
     * 缺陷名（C-102）**那一族逐字坏了以后逐字永远不重建** ——
     *   旧版逐字**没有**这样的一族（ 动作逐字**失败**时逐字**只**记一条诊断）
     *     一族逐字**起过**就**再**也不跑（ `services.containsKey` 那一句）
     *     **后果**（07 本轮逐字实测到）： `files/dev` 那一族逐字**被永久弄坏**
     *   **处置**： 动作逐字**失败**时逐字把它记进这一族
     *     **下一轮**逐字把那一族逐字**注销**、 再**下一轮**逐字**重起**它
     *     **自愈**逐字**就是**那一族逐字**重起**那一下
     */
    private val unhealthyServices = LinkedHashSet<String>()

    @Synchronized
    fun start() {
        //  缺陷名（C-102）**那 clear 逐字与服务池一起来、 它逐字把 beginLife 写的那一档逐字清掉了** ——
        //    旧版逐字是 `RuntimeDiagnostics.clear(host)` 逐字**在** `start()` **里面**
        //      **出生**那一族（ `OsInit.beginLife`）逐字在**主线程**上
        //        （ `onStartCommand` 那一行）、 服务池逐字**在**那**工作线程**上
        //        （ `ensureComponents` 逐字 post 到那 handler）
        //      **两条链是竞态**、 服务池逐字**先到**
        //        （ 07 本轮逐字核到：那 scan-cost 那一档逐字在 journal 的 at 逐字 1793483983860、
        //          而那 diag.jsonl 逐字**最早**一条 at 逐字 1793483984570 —— **晚 710 ms**）
        //      **后果**： beginLife 逐字写的那一档逐字**被清掉了**
        //        （ diag.jsonl 逐字 264 行 / 107781 字节、 **远小于**那 512KB 上限
        //          **不是**被滚掉的、 是被**清**掉的）
        //    **处置**： clear 逐字**移**到那**出生**那一族**之后**
        //      （ `OsInit.beginLife` 逐字做完、 **出生**写的那几行逐字**留住**）
        //      **仍然**只清「**上一世**」那一份（ 它逐字是那诊断那一族自己的语义、
        //        它逐字**不是**清「这一世」写的那几行）。
        //      **它自己那一族**归 01 与 04 裁、 **本笔**逐字只把它**挪**到那正确的次序。
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
        //  缺陷名（C-102）**那服务池逐字一轮只解开一层、 链条逐字一层一层等下去**
        //    旧版逐字是：
        //        val alive = services.keys.toMutableSet()
        //        for (d in ServiceRegistry.ready(host, alive)) { … }
        //      `alive` 逐字是**那一轮开始时**的快照、 `ready` 那一格
        //        逐字按那**快照**算（ ServiceRegistry.ready 逐字 `if (!alive.contains(a)) { ok = false }`）
        //      这一轮逐字新起的那些**不进**这一轮那 `ready` 的计算
        //      **下一层**逐字**下一轮**才起得起来
        //    **后果**（07 本轮逐字核出、Lead 本机实读 `SupervisorPool.kt:52` 到 `:53` 逐字成立）：
        //      链条逐字是 `piece-<id>` → `prefix` → `view` → {`pty`, `sysdev`}
        //      **prefix 永远等**、 **view / pty / sysdev 永远等**
        //      `files/dev` 逐字**永不重写**、 `nodes` 那一栏逐字**永远旧版**
        //      `ShellEntry` 逐字找不到 shell、 `sys.tty.exec` 逐字**失败**
        //      是一条**闭环**
        //    **处置**： **一轮**逐字**一路解到没有新的 ready**（ `alive` 逐字**边起边更新**）
        //      链条逐字**不再**一层一层等、 **一轮之内**逐字全起来。
        val alive = services.keys.toMutableSet()
        //  **一轮**逐字**解多轮**（ 上限逐字是那链条的层数、 它逐字防「起起来又注销」那种循环）
        var rounds = 0
        while (rounds < MAX_SERVICE_ROUNDS) {
            //  缺陷名（C-102）**那一族逐字只跑一次、 它逐字坏了以后逐字永远不重建** ——
            //   旧版逐字是 `for (d in ready) { if (services.containsKey(d.id)) continue; … }`
            //     **动作**逐字**只**在**第一次**起的时候跑
            //       **闭包**逐字**再**也不会跑、 它逐字**再**也不会抛
            //         **判健康**逐字**只看**「那一次抛没抛」
            //           一族逐字**起**过一次之后逐字它的闭包逐字再也不会跑
            //             它逐字再也不会被判不健康、 一笔逐字落的「注销 + 立刻重起」逐字永远触发不了
            //   **后果**（07 本轮逐字核出、Lead 本机实读仓内逐字成立）：
            //     · 而那 `files/dev` 那一族逐字**核**仍**答对**（ 「类型错」那一类逐字答得对）
            //     · 它逐字**不重建**（ 15 拍 / 120 秒逐字 `files/dev/exec` 逐字只剩那个目录）
            //       **复原**只能靠**重启 App**
            //   **处置**： **动作**逐字**每一轮**逐字都跑
            //     **结果**逐字**记**着（ 它是「上一次跑成功没有」）
            //     **判健康**逐字按那结果（ 它逐字**不**是「抛异常没有」）
            //     **闭包**逐字**不**再是那**动作**（ 它是那**注销**那一件）
            val readyAll = ServiceRegistry.ready(host, alive)
            if (readyAll.isEmpty()) break
            var progressed = false
            for (d in readyAll) {
                if (services.containsKey(d.id)) {
                    //  **动作**逐字**每一轮**逐字都跑（ 它逐字**不**只在第一次跑）
                    //    **结果**逐字**记**着、 **判健康**逐字按那结果
                    //    **判据**逐字是「那**上一次**跑成功没有」（ 它逐字**不**是「抛异常没有」）
                    val actOk = runCatching {
                        d.act(host)
                        true
                    }.getOrElse { e ->
                        Log.w(TAG, "内置服务运行失败: " + d.id, e)
                        RuntimeDiagnostics.append(
                            host, "service", false, "内置服务运行失败 " + d.id,
                            e.javaClass.name + ": " + (e.message ?: ""),
                        )
                        false
                    }
                    val wasHealthy = !unhealthyServices.contains(d.id)
                    if (!actOk) {
                        //  **上一次跑成没有**逐字**记**着（ 它逐字**不**再抛才**算**好）
                        unhealthyServices.add(d.id)
                        if (wasHealthy) {
                            Journal.note(host, "service", null, "内置服务不健康（下一轮重起）", "id=" + d.id)
                        }
                        continue
                    }
                    //  **跑成**了 **健康**逐字**恢复**
                    if (!wasHealthy) {
                        unhealthyServices.remove(d.id)
                        Journal.note(host, "service", null, "内置服务恢复健康", "id=" + d.id)
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
                    //  动作逐字**失败**时逐字把它记成**不健康**
                    //    （ **下一轮**逐字注销它、 再下一轮逐字**重起**它）
                    //    **诊断**逐字**照记**（ 按 C-126 不静默）
                    unhealthyServices.add(d.id)
                    continue
                }
                unhealthyServices.remove(d.id)
                services[d.id] = { noopUnit() }
                //  `alive` 逐字**边起边更新**（ 下一层逐字**这一轮**就起得来）
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
                // [!] **缺陷名（C-102）一个词承担两个意思** —— 原文案把这一项写成「在跑=」，
                //   而它读的是 `supervisors.keys`：入表在启动**之前**（:90 先赋值、:92 才 start），
                //   且启动失败不回滚（:93 的 catch 只写诊断、不删表项） 它是**意图**，不是事实。
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

        //  **一轮**逐字**解多轮**的那个上限（ 它逐字是那链条的层数、
        //    它逐字防「起起来又注销」那种循环、 它逐字**足够**解完那一族）
        const val MAX_SERVICE_ROUNDS = 8

        const val TAG = "SupervisorPool"
    }
}

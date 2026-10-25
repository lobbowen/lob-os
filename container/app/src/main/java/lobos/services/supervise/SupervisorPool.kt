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

    @Synchronized
    fun start() {
        RuntimeDiagnostics.clear(host)
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
        val alive = services.keys.toMutableSet()
        for (d in ServiceRegistry.ready(host, alive)) {
            if (services.containsKey(d.id)) continue
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
            if (!ok) continue
            services[d.id] = { noopUnit() }
            alive.add(d.id)
            Journal.note(host, "service", null, "起内置服务", "id=" + d.id)
        }
        for (id in services.keys.toList()) {
            if (ServiceRegistry.decls().none { it.id == id }) {
                services.remove(id)?.invoke()
                Journal.note(host, "service", null, "注销内置服务", "id=" + id)
            }
        }
        RuntimeDiagnostics.append(
            host, "service", true,
            "内置服务 " + services.size + "/" + ServiceRegistry.decls().size,
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

        const val TAG = "SupervisorPool"
    }
}

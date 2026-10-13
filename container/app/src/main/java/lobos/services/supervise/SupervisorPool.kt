        val alive = services.keys.toMutableSet()
        for (d in lobos.services.supervise.ServiceRegistry.ready(host, alive)) {
            if (services.containsKey(d.id)) continue
            val box = arrayOfNulls<() -> Unit>(1)
            runCatching { d.start(host) }
                .onSuccess { box[0] = { } }
                .onFailure {
                    Log.w(TAG, "内置服务启动失败: " + d.id, it)
                    RuntimeDiagnostics.append(
                        host, "service", false, "内置服务启动失败 " + d.id,
                        it.javaClass.name + ": " + (it.message ?: ""),
                    )
                    continue
                }
            services[d.id] = box[0]!!
            alive.add(d.id)
        }
        val wantedSvc = alive.toSet()
        for (id in services.keys.toList()) {
            if (id in wantedSvc) continue
            services.remove(id)?.invoke()
            Journal.note(host, "service", null, "停止内置服务", "id=" + id)
        }
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
    private fun syncServices() {
        val alive = services.keys.toMutableSet()
        for (d in ServiceRegistry.ready(host, alive)) {
            if (services.containsKey(d.id)) continue
            val stop = arrayOfNulls<() -> Unit>(1)
            val ok = runCatching {
                stop[0] = d.start(host)
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
            services[d.id] = { stop[0]?.invoke() }
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

    @Synchronized
    fun sync() {
        syncServices()
            drainJobs()
        val wanted = wantedPrograms()
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
                "在跑=" + supervisors.keys.joinToString(",") + "；停止=" + toStop.joinToString(",") +
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
        val records = ProgramIndex.all(host).filter { it.level == Level.PROGRAM }
        val installed = ProgramRegistry.list(host).filter { it.startable }.map { it.id }
        val desired = records
            .filter { it.desired == Desired.RUNNING }
            .map { it.id }
            .filter { installed.contains(it) }
        if (desired.isNotEmpty()) return desired
        if (records.isNotEmpty()) return emptyList()
        return installed.take(1)
    }

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
        const val TAG = "SupervisorPool"
    }
}

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
    fun sync() {
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

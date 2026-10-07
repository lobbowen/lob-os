package lobos.runtime

import android.app.Service
import android.content.Intent
import android.util.Log
import lobos.RuntimeDiagnostics
import lobos.os.Desired
import lobos.log.Journal
import lobos.os.Level
import lobos.os.ProgramIndex
import lobos.os.ProgramRegistry

class SupervisorPool(private val host: Service) {

    private val supervisors = LinkedHashMap<String, InstanceHost>()

    @Synchronized
    fun start() {
        RuntimeDiagnostics.clear(host)
        sync()
    }

    @Synchronized
    fun onHostStart(intent: Intent?) {
    runCatching { resetComponents("宿主动作：" + (intent?.action ?: "无")) }
        val target = intent?.getStringExtra("programId")?.takeIf { it.isNotBlank() }
        val targets = if (target != null) listOf(target) else supervisors.keys.toList()
        for (t in targets) runCatching { supervisors[t]?.onHostStart(intent) }
        sync()
    }

    @Synchronized
    fun sync() {
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
                    "；期望=" + wanted.joinToString(","),
            )
        }
    }

    private fun wantedPrograms(): List<String> {
        val records = ProgramIndex.all(host).filter { it.level == Level.APPLICATION }
        val desired = records.filter { it.desired == Desired.RUNNING }.map { it.id }
        val installed = ProgramRegistry.list(host).filter { it.startable }.map { it.id }
        val out = desired.filter { installed.contains(it) }
        if (out.isNotEmpty()) return out
        if (records.isNotEmpty()) return emptyList()
        return installed.take(1)
    }

    @Synchronized
    fun running(): List<String> {
        val ids = supervisors.keys.toList()
        lobos.os.ProgramStatusHub.publishRunning(ids.toSet())
        return ids
    }

    fun tickComponents() {
        runCatching { lobos.capability.AdbChannelComponent.tick(host) }
    }

    fun resetComponents(why: String) {
        runCatching { lobos.capability.AdbChannelComponent.reset(host, why) }
    }

    @Synchronized
    fun shutdown() {
    runCatching { lobos.capability.AdbChannelComponent.stop(host) }
        for ((_, s) in supervisors) {
            try { s.shutdown() } catch (_: Throwable) {}
        }
        supervisors.clear()
    }

    companion object {
        const val TAG = "SupervisorPool"
    }
}

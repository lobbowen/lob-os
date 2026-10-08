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
            // 先出队执行（第 3 层）：把已排的作业按ordering 依赖落成事实
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
                    "；期望=" + wanted.joinToString(",") +
            )
        }
    }

    /**
     * 监督谁 —— **读的是「执行后的期望」，不是 desired 字段本身**。
     *
     * systemd 的分工：请求排成 job（UnitJobs.enqueue）→ 队��按 ordering 出队
     *（UnitJobs.takeReady）→ 执行完才知道实际该跑谁。
     * 此前这里直接读 desired 字段，把队列绕过了 —— 于是「想跑」与「在跑」
     * 混成一件，第 3 层等于没接上。
     */
    /**
     * 出队执行 —— 把已排的作业落成「期望状态变了」这个事实。
     *
     * systemd(1)：「their execution is ordered based on the ordering dependencies
     * of the units they have been scheduled for」—— [UnitJobs.takeReady] 返回
     * 下一个可以执行的作业（after 里的单元都已就位的那个）。
     *
     * 出队一个就把期望状态改成作业要的 —— **请求至此才变成事实**。
     * 排不进去的（事务校验没过）留在队列里，下一拍再试。
     */
    private fun drainJobs() {
        var guard = 0
        while (guard++ < 64) {
            val job = lobos.os.UnitJobs.takeReady(host) ?: break
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
        // 想跑 = 已登记 + 已装 + 期望 RUNNING
        val desired = records
            .filter { it.desired == Desired.RUNNING }
            .map { it.id }
            .filter { installed.contains(it) }
        if (desired.isNotEmpty()) return desired
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

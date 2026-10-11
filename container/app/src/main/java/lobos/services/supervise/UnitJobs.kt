package lobos.services.supervise

import android.content.Context
import java.io.File
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles
import lobos.kernel.proc.ProcessLedger
import lobos.services.reg.ProgramIndex
import lobos.services.reg.UnitState
import lobos.services.reg.Desired

object UnitJobs {

    data class Job(
        val unit: String,
        val desired: Desired,
        val reason: String = "",
    )

    sealed class Verdict {
        object Ok : Verdict()

        data class Reject(val why: String) : Verdict()
    }

    private fun file(ctx: Context): File =
        SystemDirs.run(ctx).let { File(it, "unit-jobs.json") }

    fun enqueue(ctx: Context, job: Job): Verdict {
        val pending = read(ctx)
        if (pending.any { it.unit == job.unit }) return Verdict.Ok   // 已在队里，不重复排
        val next = pending + job
        return verify(ctx, next).also {
            if (it is Verdict.Ok) write(ctx, next)
        }
    }

    fun verify(ctx: Context, jobs: List<Job>): Verdict {
        val known = ProgramIndex.all(ctx).associateBy { it.id }
        for (j in jobs) {
            if (!known.containsKey(j.unit)) return Verdict.Reject("单元不在册：" + j.unit)
        }
        val cycle = findCycle(known.keys.toSet(), { u -> known[u]?.after ?: emptyList() })
        if (cycle != null) {
            return Verdict.Reject("排序依赖成环：" + cycle.joinToString(" -> ") +
                "（systemd 在这里报 cycle-free 检查失败）")
        }
        return Verdict.Ok
    }

    private fun findCycle(nodes: Set<String>, edges: (String) -> List<String>): List<String>? {
        val WHITE = 0
        val GRAY = 1
        val BLACK = 2
        val color = nodes.associateWith { WHITE }.toMutableMap()
        val stack = mutableListOf<String>()

        fun dfs(n: String): List<String>? {
            color[n] = GRAY
            stack.add(n)
            for (m in edges(n)) {
                if (!color.containsKey(m)) continue          // 不在册的边由 verify() 先拦
                when (color[m]) {
                    GRAY -> {                                 // 回边 = 环
                        val from = stack.indexOf(m)
                        return stack.subList(from, stack.size).toList() + m
                    }
                    WHITE -> dfs(m)?.let { return it }
                }
            }
            stack.removeAt(stack.size - 1)
            color[n] = BLACK
            return null
        }

        for (n in nodes) {
            if (color[n] == WHITE) dfs(n)?.let { return it }
        }
        return null
    }

    fun read(ctx: Context): List<Job> =
        runCatching {
            val o = org.json.JSONObject(file(ctx).readText())
            val a = o.optJSONArray("jobs") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val it = a.optJSONObject(i) ?: return@mapNotNull null
                val u = it.optString("unit", "")
                if (u.isEmpty()) null
                else Job(
                    unit = u,
                    desired = runCatching { Desired.valueOf(it.optString("desired", "STOPPED")) }
                        .getOrDefault(Desired.STOPPED),
                    reason = it.optString("reason", ""),
                )
            }
        }.getOrDefault(emptyList())

    private fun write(ctx: Context, jobs: List<Job>) {
        val a = org.json.JSONArray()
        for (j in jobs) {
            a.put(
                org.json.JSONObject().apply {
                    put("unit", j.unit)
                    put("desired", j.desired.name)
                    put("reason", j.reason)
                },
            )
        }
        StateFiles.writeAtomic(
            file(ctx),
            org.json.JSONObject().apply {
                put("schema", 1)
                put("jobs", a)
            }.toString(1) + "\n",
        )
    }

    fun takeReady(ctx: Context): Job? {
        val jobs = read(ctx)
        if (jobs.isEmpty()) return null
        val entries = ProgramIndex.all(ctx).associateBy { it.id }
        val ready = jobs.firstOrNull { j ->
            val after = entries[j.unit]?.after ?: emptyList()
            after.all { dep ->
                val e = entries[dep]
                e == null || !UnitState.isRunnable(e) || e.desired != Desired.RUNNING ||
                    (e.pid > 0 && ProcessLedger.isOwnedAlive(e.pid, e.starttime))
            }
        } ?: return null
        write(ctx, jobs - ready)
        return ready
    }

    fun pendingCount(ctx: Context): Int = read(ctx).size
}
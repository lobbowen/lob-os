package lobos.os

import android.content.Context
import java.io.File

/**
 * 作业队列 —— 照抄 systemd 的 job queue 与最小事务系统。
 *
 * systemd(1) 原文：
 * > "Application programs and units (via dependencies) may request state
 * > changes of units. In systemd, these requests are **encapsulated as 'jobs'
 * > and maintained in a job queue.** Jobs may succeed or can fail, their
 * > execution is **ordered based on the ordering dependencies** of the units
 * > they have been scheduled for."
 *
 * > "systemd has a **minimal transaction system**: if a unit is requested to
 * > start up or shut down it will add it and **all its dependencies to a
 * > temporary transaction**. Then, it will **verify if the transaction is
 * > consistent (i.e. whether the ordering of all units is cycle-free)**."
 *
 * 我们此前的形状：`setDesired()` 直接把字段改掉就返回 true。
 * 后果有三个：
 *   · 请求还没执行，状态就已经变了（「想跑」与「在跑」混为一谈）
 *   · 依赖顺序没有保证 —— 谁先起看谁跑得快
 *   · **循环依赖检测不到** —— a.after=b 且 b.after=a 会一直互相等
 *
 * 所以第3 层：请求先排成 job，成批校验，通过才入队。
 */
object UnitJobs {

    /** 一个作业 —— 「让某个单元到达某个期望状态」这个请求 */
    data class Job(
        val unit: String,
        val desired: Desired,
        /** 排这个作业的理由（进 journal，systemd 也记） */
        val reason: String = "",
    )

    /** 校验结果 —— 与 systemd 一样，先验后做 */
    sealed class Verdict {
        /** 通过，可以入队 */
        object Ok : Verdict()

        /** 拒绝，并说明为什么 */
        data class Reject(val why: String) : Verdict()
    }

    private fun file(ctx: Context): File =
        SystemDirs.run(ctx).let { File(it, "unit-jobs.json") }

    // ── 排作业 ────────────────────────────────────────────────

    /**
     * 排一个请求 —— **不是直接改，是入队**。
     *
     * @return null 表示通过；非 null 是拒绝理由
     */
    fun enqueue(ctx: Context, job: Job): Verdict {
        val pending = read(ctx)
        if (pending.any { it.unit == job.unit }) return Verdict.Ok   // 已在队里，不重复排
        val next = pending + job
        return verify(ctx, next).also {
            if (it is Verdict.Ok) write(ctx, next)
        }
    }

    // ── 事务校验 ──────────────────────────────────────────────

    /**
     * 事务校验 —— 照抄 systemd 的「verify the transaction is consistent」。
     *
     * 两件事：
     *   ① 每个作业的单元都得在册（systemd 会自动从磁盘装载 unit，这里是查注册表）
     *   ② **排序依赖无环** —— systemd 明写「whether the ordering of all units
     *      is cycle-free」。我们有 `after`（第 1 层加的），到这里才有用。
     *
     * 注意：systemd 的原文说 ordering 与 requirement 是**正交**的 ——
     * 所以判环只看 after/requires 里的排序那一半，不把 requires 也当顺序。
     */
    fun verify(ctx: Context, jobs: List<Job>): Verdict {
        val known = ProgramIndex.all(ctx).associateBy { it.id }
        for (j in jobs) {
            if (!known.containsKey(j.unit)) return Verdict.Reject("单元不在册：" + j.unit)
        }
        // 环检测：把 after 画成有向图，DFS 找回边
        val cycle = findCycle(known.keys.toSet(), { u -> known[u]?.after ?: emptyList() })
        if (cycle != null) {
            return Verdict.Reject("排序依赖成环：" + cycle.joinToString(" -> ") +
                "（systemd 在这里报 cycle-free 检查失败）")
        }
        return Verdict.Ok
    }

    /**
     * 有向图里找环 —— 返回环上的那条路径，找不到返回 null。
     *
     * systemd 的原文：「verify that the transaction is consistent
     * (i.e. whether the ordering of all units is cycle-free)」。
     */
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

    // ── 队列 ──────────────────────────────────────────────────

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

    /**
     * 执行队列里**下一个可以执行**的作业 —— 按 ordering 依赖。
     *
     * systemd 的 job queue 是有序的：作业的执行顺序由它自己那条 ordering
     * dependencies 决定。这里同��：只有 `after` 里列的单元都已就位，
     * 这个作业才能出队。
     */
    fun takeReady(ctx: Context): Job? {
        val jobs = read(ctx)
        if (jobs.isEmpty()) return null
        val entries = ProgramIndex.all(ctx).associateBy { it.id }
        val ready = jobs.firstOrNull { j ->
            val after = entries[j.unit]?.after ?: emptyList()
            after.all { dep ->
                // 依赖的单元「已就位」= ACTIVE，或它本来就不是要跑的东西（件）
                // 「已就位」= 进程账本里那个 pid 还活着（含 starttime 防 pid 复用）
                val e = entries[dep]
                e == null || !UnitState.isRunnable(e) || e.desired != Desired.RUNNING ||
                    (e.pid > 0 && ProcessLedger.isOwnedAlive(e.pid, e.starttime))
            }
        } ?: return null
        write(ctx, jobs - ready)
        return ready
    }

    /** 队列里还有几个作业 */
    fun pendingCount(ctx: Context): Int = read(ctx).size
}
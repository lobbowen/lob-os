package lobos.kernel.proc

import android.content.Context

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles

object ProcessLedger {

    private const val SIGTERM = 15

    private fun dir(ctx: Context): File = SystemDirs.run(ctx)
    private const val FILE = "process-ledger.json"
    private const val SCHEMA = 2

    data class Entry(
        val programId: String,
        val generation: Long,
        val pid: Int,
        val starttime: Long,
        val startedAt: Long = 0L,
    )

    private fun file(ctx: Context): File {
        val d = dir(ctx)
        d.mkdirs()
        return File(d, FILE)
    }

    private fun read(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Throwable) {
            lobos.kernel.KernelHooks.report(
                ctx, "ledger", false,
                "进程账本不可解析：按无进程处理（已失去全部归属信息）",
                f.absolutePath + " " + e::class.java.simpleName + ": " + (e.message ?: "")
                )
            JSONObject()
        }
    }

    private fun write(ctx: Context, obj: JSONObject) {
        runCatching {
            obj.put("schema", SCHEMA)
            StateFiles.writeAtomic(file(ctx), obj.toString(2))
        }
    }

    private fun statFields(pid: Int): List<String>? {
        if (pid <= 0) return null
        val stat = runCatching { File("/proc/" + pid + "/stat").readText() }.getOrNull() ?: return null
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 2).split(' ').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun starttimeOf(pid: Int): Long {
        val f = statFields(pid) ?: return -1
        if (f.size < 20) return -1
        return f[19].toLongOrNull() ?: -1
    }

    fun ppidOf(pid: Int): Int = runCatching {
        val stat = File("/proc/" + pid + "/stat").readText()
        stat.substringAfterLast(") ").split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    }.getOrDefault(-1)

    fun descendantsOf(rootPid: Int, maxDepth: Int = 16): List<Int> {
        if (rootPid <= 1) return emptyList()
        val me = android.os.Process.myPid()
        val children = HashMap<Int, MutableList<Int>>()
        for (name in File("/proc").list() ?: return emptyList()) {
            val pid = name.toIntOrNull() ?: continue
            if (pid <= 1 || pid == me) continue
            val ppid = ppidOf(pid)
            if (ppid <= 1) continue
            (children.getOrPut(ppid) { mutableListOf() }).add(pid)
        }
        val out = mutableListOf<Int>()
        val seen = HashSet<Int>()
        var frontier = listOf(rootPid)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = mutableListOf<Int>()
            for (p in frontier) {
                for (c in children[p].orEmpty()) {
                    if (c == me || c in seen) continue
                    seen.add(c)
                    out.add(c)
                    next.add(c)
                }
            }
            frontier = next
            depth++
        }
        return out
    }

    fun killTree(entry: Entry, timeoutMs: Long = 3000L): Int {
        val self = android.os.Process.myPid()
        val rootAlive = starttimeOf(entry.pid) == entry.starttime
        val victims = (if (rootAlive) descendantsOf(entry.pid) else emptyList()) + entry.pid
        var signalled = 0
        for (pid in victims) {
            if (pid <= 1 || pid == self) continue
            if (runCatching { android.system.Os.kill(pid, SIGTERM) }.isSuccess) signalled++
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val anyAlive = victims.any { it > 1 && it != self && starttimeOf(it) > 0 }
            if (!anyAlive) return signalled
            runCatching { Thread.sleep(150L) }
        }
        for (pid in victims) {
            if (pid <= 1 || pid == self) continue
            if (starttimeOf(pid) <= 0) continue
            runCatching { android.os.Process.killProcess(pid) }
        }
        return signalled
    }

    fun scanChildPid(entryPath: String): Int = runCatching {
        val me = android.os.Process.myPid()
        val names = File("/proc").list() ?: return 0
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            if (pid <= 1) continue
            val stat = runCatching { File("/proc/" + name + "/stat").readText() }.getOrNull() ?: continue
            val ppid = stat.substringAfterLast(") ").split(" ").getOrNull(1)?.toIntOrNull() ?: continue
            if (ppid != me) continue
            val cmd = runCatching {
                File("/proc/" + name + "/cmdline").readBytes().toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (cmd.contains(entryPath)) return pid
        }
        0
    }.getOrDefault(0)
    fun isOwnedAlive(pid: Int, starttime: Long): Boolean {
        if (pid <= 0) return false
        if (starttime <= 0) return false
        return starttimeOf(pid) == starttime
    }

    /** 判活的第一条路（不依赖 starttime）：/proc/<pid>/cmdline 里含这个件的 entry 绝对路径。
     *
     *  为什么需要它：宿主 JVM 读不到**自己 fork 出来的子进程**的 /proc/<pid>/stat
     *  （真机读数：进程起来了 pid=30605/30624/30638，账本也记了，而 starttimeOf 返回 -1
     *  => begin() 把 -1 原样存下=> isOwnedAlive 立刻 false => programsRunning 恒 0）。
     *  而 scanChildPid（:128-143）已经在读 /proc/<pid>/cmdline 并与 entry 绝对路径做子串匹配，
     *  这条路是实测可用的。
     *
     *  **它的边界**：cmdline 只回答「这个 pid 现在跑的是不是这个件的入口」，
     *  **不防 pid 复用** —— starttime 原本正是防复用的那一位，而这一条把它跳过了。
     *  所以只有 starttime 两边都是 -1（拿不到）时才走它。 */
    fun isOwnedAliveByCmdline(pid: Int, entryPath: String): Boolean {
        if (pid <= 0 || entryPath.isBlank()) return false
        val cmd = runCatching {
            File("/proc/" + pid + "/cmdline").readBytes().toString(Charsets.UTF_8)
        }.getOrNull() ?: return false
        return cmd.contains(entryPath)
    }

    fun cmdlineOf(pid: Int): String = runCatching {
        File("/proc/" + pid + "/cmdline").readBytes().toString(Charsets.UTF_8)
    }.getOrDefault("")

    @Synchronized

    fun list(ctx: Context): List<Entry> {
        val arr = read(ctx).optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                programId = o.optString("programId", ""),
                generation = o.optLong("generation", 0L),
                pid = o.optInt("pid", 0),
                starttime = o.optLong("starttime", -1L),
                startedAt = o.optLong("startedAt", 0L),
            )
        }
    }

    @Synchronized
    private fun persist(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("programId", e.programId)
                put("generation", e.generation)
                put("pid", e.pid)
                put("starttime", e.starttime)
                put("startedAt", e.startedAt)
            })
        }
        write(ctx, read(ctx).put("entries", arr))
    }

    @Synchronized
    fun nextGeneration(ctx: Context, programId: String): Long {
        val max = list(ctx).filter { it.programId == programId }.maxOfOrNull { it.generation } ?: 0L
        return max + 1
    }

    @Synchronized
    fun begin(ctx: Context, programId: String, generation: Long, pid: Int): Entry? {
        val st = starttimeOf(pid)
        val e = Entry(
            programId = programId,
            generation = generation,
            pid = pid,
            starttime = st,
            startedAt = System.currentTimeMillis(),
        )
        persist(ctx, list(ctx).filterNot { it.pid == pid } + e)
        return e
    }

    @Synchronized
    fun end(ctx: Context, pid: Int) {
        if (pid <= 0) return
        val cur = list(ctx)
        val kept = cur.filterNot { it.pid == pid }
        if (kept.size != cur.size) persist(ctx, kept)
    }

    /** 这个 programId 的 entry 绝对路径 —— 三处共用（liveOwned 的调用方都要它），
     *  ★ 所以收在这里而不是让每个调用方各写一份。 */
    fun entryPathOf(ctx: Context, programId: String): String? = runCatching {
        val spec = lobos.services.reg.ProgramRegistry.spec(ctx, programId) ?: return@runCatching null
        spec.entryFile.takeIf { it.isFile }?.absolutePath
    }.getOrNull()

    /** 在跑的条目 —— 判据两条，按顺序取：
     *  ① starttime 可用（账本里 >0 且现在读得到且相等）=> 以它为准（防 pid 复用）；
     *  ② starttime 拿不到（账本里就是 -1）=> 退到 cmdline 路径（见 isOwnedAliveByCmdline 的注释与它的边界）。
     *  ★ 而今天这一格是②：宿主读不到自己子进程的 /proc/<pid>/stat。
     *  ★ 三处调用方（OsHostService / InstanceHost / ProgramStatus）都走这一条，
     *  ★ 免得其中某处退回纯 starttime 而与另两处读出不同的数。 */
    @Synchronized
    fun liveOwned(ctx: Context): List<Entry> =
        list(ctx).filter { isLive(e = it, entryPath = entryPathOf(ctx, it.programId)) }

    /** 判活的那一条（三处共用）。② 里 cmdline 只回答「这个 pid 是不是这个件的入口」，不防 pid 复用。 */
    fun isLive(e: Entry, entryPath: String?): Boolean =
        if (e.starttime > 0) isOwnedAlive(e.pid, e.starttime)
        else entryPath != null && isOwnedAliveByCmdline(e.pid, entryPath)

    fun pidReused(ctx: Context): List<Entry> =
        list(ctx).filter { it.pid > 0 && starttimeOf(it.pid) > 0 && !isOwnedAlive(it.pid, it.starttime) }

    fun gone(ctx: Context): List<Entry> = list(ctx).filter { starttimeOf(it.pid) <= 0 }
}

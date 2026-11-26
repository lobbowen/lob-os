package lobos.kernel.proc


import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs
import lobos.kernel.fs.StateFiles

object ProcessLedger {

    private const val SIGTERM = 15

    private fun dir(): File = SystemDirs.run()
    private const val FILE = "process-ledger.json"
    private const val SCHEMA = 2

    data class Entry(
        val programId: String,
        val generation: Long,
        val pid: Int,
        val starttime: Long,
        val startedAt: Long = 0L,
    )

    private fun file(): File {
        val d = dir()
        d.mkdirs()
        return File(d, FILE)
    }

    private fun read(): JSONObject {
        val f = file()
        if (!f.isFile) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (e: Throwable) {
            lobos.kernel.spi.KernelServices.logger().log("ledger", false,
                "进程账本不可解析：按无进程处理（已失去全部归属信息）",
                f.absolutePath + " " + e::class.java.simpleName + ": " + (e.message ?: "")
                )
            JSONObject()
        }
    }

    private fun write(obj: JSONObject) {
        runCatching {
            obj.put("schema", SCHEMA)
            StateFiles.writeAtomic(file(), obj.toString(2))
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

    fun list(): List<Entry> {
        val arr = read().optJSONArray("entries") ?: return emptyList()
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
    private fun persist(entries: List<Entry>) {
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
        write(read().put("entries", arr))
    }

    @Synchronized
    fun nextGeneration(programId: String): Long {
        val max = list().filter { it.programId == programId }.maxOfOrNull { it.generation } ?: 0L
        return max + 1
    }

    @Synchronized
    fun begin(programId: String, generation: Long, pid: Int): Entry? {
        val st = starttimeOf(pid)
        val e = Entry(
            programId = programId,
            generation = generation,
            pid = pid,
            starttime = st,
            startedAt = System.currentTimeMillis(),
        )
        persist(list().filterNot { it.pid == pid } + e)
        return e
    }

    @Synchronized
    fun end(pid: Int) {
        if (pid <= 0) return
        val cur = list()
        val kept = cur.filterNot { it.pid == pid }
        if (kept.size != cur.size) persist(kept)
    }

    fun entryPathOf(programId: String): String? = null // V1 program registry removed
}

package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ProcessLedger {

    private const val DIR = "os"
    private const val FILE = "process-ledger.json"
    private const val SCHEMA = 1

    data class Entry(
        val programId: String,
        val generation: Long,
        val pid: Int,
        val starttime: Long,
        val pgid: Int,
        val startedAt: Long,
    )

    private fun file(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        d.mkdirs()
        return File(d, FILE)
    }

    private fun read(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

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

    fun exists(pid: Int): Boolean = statFields(pid) != null

    fun isOwnedAlive(pid: Int, starttime: Long): Boolean {
        if (pid <= 0 || starttime <= 0) return false
        return starttimeOf(pid) == starttime
    }

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
                pgid = o.optInt("pgid", -1),
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
                put("pgid", e.pgid)
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

    fun pgidOf(pid: Int): Int = runCatching {
        val rest = java.io.File("/proc/" + pid + "/stat").readText().substringAfterLast(") ")
        rest.split(" ").getOrNull(2)?.toIntOrNull() ?: -1
    }.getOrDefault(-1)
    @Synchronized
    fun begin(ctx: Context, programId: String, generation: Long, pid: Int): Entry? {
        val st = starttimeOf(pid)
        if (pid <= 0 || st <= 0) return null
        val e = Entry(programId, generation, pid, st, pgidOf(pid), System.currentTimeMillis())
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

    @Synchronized
    fun clear(ctx: Context) = persist(ctx, emptyList())

    fun liveOwned(ctx: Context): List<Entry> =
        list(ctx).filter { isOwnedAlive(it.pid, it.starttime) }

    fun pidReused(ctx: Context): List<Entry> =
        list(ctx).filter { it.pid > 0 && starttimeOf(it.pid) > 0 && !isOwnedAlive(it.pid, it.starttime) }

    fun gone(ctx: Context): List<Entry> = list(ctx).filter { starttimeOf(it.pid) <= 0 }
}

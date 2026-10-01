package lobos.os

import android.content.Context
import java.io.File
import org.json.JSONObject

object AppRegistry {

    private const val DIR = "os"
    private const val FILE = "programs.json"

    enum class Desired { RUNNING, STOPPED, FROZEN }

    data class Entry(
        val id: String,
        val version: String?,
        val role: String,
        val desired: Desired,
    )

    private fun file(ctx: Context): File {
        val d = File(ctx.filesDir, DIR)
        d.mkdirs()
        return File(d, FILE)
    }

    @Synchronized
    private fun root(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())

    @Synchronized
    private fun persist(ctx: Context, obj: JSONObject) {
        runCatching { lobos.os.StateFiles.writeAtomic(file(ctx), obj.toString(2)) }
    }

    @Synchronized
    fun all(ctx: Context): List<Entry> {
        val obj = root(ctx)
        val names = obj.names() ?: return emptyList()
        return (0 until names.length()).mapNotNull { i ->
            val id = names.optString(i)
            val o = obj.optJSONObject(id) ?: return@mapNotNull null
            Entry(
                id = id,
                version = o.optString("version").takeIf { it.isNotBlank() },
                role = o.optString("role", "agent"),
                desired = runCatching { Desired.valueOf(o.optString("desired", "STOPPED")) }
                    .getOrDefault(Desired.STOPPED),
            )
        }
    }

    @Synchronized
    fun upsert(ctx: Context, entry: Entry) {
        val obj = root(ctx)
        obj.put(entry.id, JSONObject().apply {
            put("version", entry.version ?: "")
            put("role", entry.role)
            put("desired", entry.desired.name)
        })
        persist(ctx, obj)
        Journal.append(ctx, "registry", null, "upsert " + entry.id + " desired=" + entry.desired.name)
    }

    @Synchronized
    fun reconcileNotRunning(ctx: Context): List<String> {
        val obj = root(ctx)
        val names = obj.names() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until names.length()) {
            val id = names.optString(i)
            val o = obj.optJSONObject(id) ?: continue
            if (o.optString("desired", "STOPPED") != "RUNNING") continue
            val version = o.optString("version", "")
            val cur = runCatching {
                File(File(File(ctx.filesDir, "programs"), id), "CURRENT").readText().trim()
            }.getOrNull()
            if (version.isBlank() && cur.isNullOrBlank()) {
                o.put("desired", "STOPPED")
                out.add(id)
            }
        }
        if (out.isNotEmpty()) persist(ctx, obj)
        return out
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        val obj = root(ctx)
        obj.remove(id)
        persist(ctx, obj)
        Journal.append(ctx, "registry", null, "remove " + id)
    }

}

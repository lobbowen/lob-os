package lobos.os

import android.content.Context
import java.io.File
import lobos.log.Journal
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs

object PortBroker {

    private fun dir(ctx: Context): File = SystemDirs.run(ctx)
    private const val FILE = "ports.json"
    const val RANGE_START = 41000
    const val RANGE_END = 50999

    data class Lease(val port: Int, val owner: String)

    private fun file(ctx: Context): File {
        val d = dir(ctx)
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
    fun list(ctx: Context): List<Lease> {
        val obj = root(ctx)
        val names = obj.names() ?: return emptyList()
        return (0 until names.length()).mapNotNull { i ->
            val owner = names.optString(i)
            val port = obj.optInt(owner, 0)
            if (port == 0) null else Lease(port, owner)
        }
    }

    @Synchronized
    fun claim(ctx: Context, owner: String, preferred: Int? = null): Int {
        val obj = root(ctx)
        obj.optInt(owner, 0).takeIf { it != 0 }?.let { return it }
        val used = list(ctx).map { it.port }.toSet()
        val port = preferred?.takeIf { it in RANGE_START..RANGE_END && it !in used }
            ?: (RANGE_START..RANGE_END).firstOrNull { it !in used }
            ?: return 0
        obj.put(owner, port)
        persist(ctx, obj)
        Journal.append(ctx, "ports", null, "claim " + owner + " -> " + port)
        return port
    }

    @Synchronized
    fun release(ctx: Context, owner: String) {
        val obj = root(ctx)
        if (obj.has(owner)) {
            obj.remove(owner)
            persist(ctx, obj)
            Journal.append(ctx, "ports", null, "release " + owner)
        }
    }
}

package lobos.services.supply

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File

object InstallGuard {
    private val lock = Any()

    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }

    fun freeBytes(dir: File): Long {
        val anchor = dir.takeIf { it.isDirectory } ?: dir.parentFile ?: dir
        return runCatching { StatFs(anchor.absolutePath).availableBytes }.getOrDefault(-1L)
    }

    fun ensureSpace(dir: File, needBytes: Long): String? {
        if (needBytes <= 0L) return null
        val free = freeBytes(dir)
        if (free < 0L) return null
        if (free >= needBytes) return null
        return "磁盘空间不足：这次需要约 " + human(needBytes) + "，当前可用 " + human(free) +
            "。先清理空间或移除不用的组件再试。"
    }

    fun human(bytes: Long): String {
        if (bytes < 0) return "未知"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1.0) return "不到 1MB"
        if (mb < 1024.0) return String.format("%.1fMB", mb)
        return String.format("%.2fGB", mb / 1024.0)
    }

    private fun ledgerFile(ctx: Context): File =
        File(lobos.kernel.layout.SystemDirs.etc(ctx), "installed.json")

    private fun readLedger(ctx: Context): JSONObject {
        val f = ledgerFile(ctx)
        if (!f.isFile) return JSONObject()
        return runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeLedger(ctx: Context, obj: JSONObject) {
        val f = ledgerFile(ctx)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString(2))
            if (!tmp.renameTo(f)) {
                f.writeText(obj.toString(2))
                tmp.delete()
            }
        }
    }

    fun record(
        context: Context,
        id: String,
        version: String,
        bytes: Long,
        source: String,
        level: String,
    ) {
        if (id.isBlank()) return
        val ledger = readLedger(context)
        val row = JSONObject().apply {
            put("version", version)
            put("bytes", bytes)
            put("source", source)
            put("level", level)
            put("installedAt", System.currentTimeMillis())
        }
        ledger.put(id, row)
        writeLedger(context, ledger)
    }

    fun forget(ctx: Context, id: String) {
        if (id.isBlank()) return
        val ledger = readLedger(ctx)
        if (!ledger.has(id)) return
        ledger.remove(id)
        writeLedger(ctx, ledger)
    }

    fun read(ctx: Context, id: String): JSONObject? =
        readLedger(ctx).optJSONObject(id)

    fun all(ctx: Context): Map<String, JSONObject> {
        val ledger = readLedger(ctx)
        val out = LinkedHashMap<String, JSONObject>()
        for (k in ledger.keys()) {
            val v = ledger.optJSONObject(k) ?: continue
            out[k] = v
        }
        return out
    }
}
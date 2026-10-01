package lobos.capability

import android.content.Context
import lobos.permissions.PermissionCatalog
import org.json.JSONObject
import java.io.File

object PermissionLedger {

    private fun file(ctx: Context) = File(File(ctx.filesDir, "os"), "permission-ledger.json")

    fun readAll(ctx: Context): Map<String, SilentAttempt> = runCatching {
        val f = file(ctx)
        if (!f.exists()) return emptyMap()
        val root = JSONObject(f.readText())
        val out = LinkedHashMap<String, SilentAttempt>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (PermissionCatalog.byId(id) == null) continue
            val entry = root.optJSONObject(id) ?: continue
            val outcome = AttemptOutcome.values().firstOrNull { it.name == entry.optString("outcome") }
                ?: continue
            out[id] = SilentAttempt(outcome, entry.optLong("atMs", 0L), entry.optString("detail"))
        }
        out
    }.getOrDefault(emptyMap())

    @Synchronized
    fun record(ctx: Context, id: String, outcome: AttemptOutcome, detail: String) {
        runCatching {
            val f = file(ctx)
            val root = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            root.put(
                id,
                JSONObject()
                    .put("outcome", outcome.name)
                    .put("atMs", System.currentTimeMillis())
                    .put("detail", detail.take(300)),
            )
            f.parentFile?.mkdirs()
            lobos.os.StateFiles.writeAtomic(f, root.toString())
        }
    }
}

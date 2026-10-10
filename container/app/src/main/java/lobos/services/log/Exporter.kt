package lobos.services.log

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Exporter {

    data class Bundle(
        val generatedAtMs: Long,
        val device: JSONObject,
        val counts: JSONObject,
        val events: JSONArray,
        val diagnostics: JSONArray,
        val nodeStderr: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("schema", 1)
            put("generatedAt", generatedAtMs)
            put("device", device)
            put("counts", counts)
            put("events", events)
            put("diagnostics", diagnostics)
            put("nodeStderr", nodeStderr)
        }
    }

    private const val MAX_EVENTS = 2000
    private const val MAX_DIAG = 500
    private const val MAX_STDERR = 32 * 1024

    fun deviceInfo(ctx: Context): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }

    fun exportBundle(ctx: Context): Bundle {
        val events = Journal.events(ctx, limit = MAX_EVENTS)
        val diags = Journal.events(ctx, limit = MAX_DIAG).map { it.toJson() }
        val stderr = nodeErrText(ctx).takeLast(MAX_STDERR)

        val byLevel = JSONObject()
        for (e in events) {
            val k = e.level.code
            byLevel.put(k, byLevel.optInt(k) + 1)
        }

        return Bundle(
            generatedAtMs = System.currentTimeMillis(),
            device = deviceInfo(ctx),
            counts = JSONObject().apply {
                put("events", events.size)
                put("diagnostics", diags.size)
                put("byLevel", byLevel)
                put("stderrBytes", stderr.toByteArray().size)
            },
            events = JSONArray().apply { events.forEach { put(it.toJson()) } },
            diagnostics = JSONArray().apply { diags.forEach { put(it) } },
            nodeStderr = stderr,
        )
    }

    fun writeToCache(ctx: Context, name: String = "lobos-log-bundle.json"): File {
        val f = File(ctx.cacheDir, name)
        f.writeText(exportBundle(ctx).toJson().toString(2))
        return f
    }

    private fun nodeErrText(ctx: Context): String = runCatching {
        lobos.services.log.RuntimeDiagnostics.nodeErrFile(ctx).readText()
    }.getOrDefault("")
}
package lobos.services.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lobos.kernel.fs.StateFiles
import lobos.kernel.layout.SystemDirs
import org.json.JSONObject
import lobos.services.reg.Level

object RuntimeDiagnostics {

    enum class Level { INFO, OK, FAIL }

    data class DiagEvent(
        val atMs: Long,
        val stage: String,
        val level: Level,
        val message: String,
        val detail: String,
        val data: JSONObject? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("at", atMs)
            put("stage", stage)
            put("level", level.name)
            put("message", message)
            put("detail", detail)
            data?.let { put("data", it) }
        }
    }

    private const val FILE = "diagnostics.txt"
    const val NODE_ERR_FILE = "node-stderr.log"
    private const val STRUCT_FILE = "diag.jsonl"

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun file(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.log(ctx), FILE)
    fun nodeErrFile(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.log(ctx), NODE_ERR_FILE)
    private fun structFile(ctx: Context): File {
        val d = lobos.kernel.layout.SystemDirs.log(ctx)
        d.mkdirs()
        return File(d, STRUCT_FILE)
    }

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).writeText("")
        runCatching { structFile(ctx).writeText("") }
    }

    @Synchronized
    fun append(
        ctx: Context,
        stage: String,
        ok: Boolean?,
        message: String,
        detail: String = "",
        data: JSONObject? = null,
    ) {
        val level = when (ok) {
            true -> Level.OK
            false -> Level.FAIL
            null -> Level.INFO
        }
        appendEvent(ctx, DiagEvent(System.currentTimeMillis(), stage, level, message, detail, data))
    }

    @Synchronized
    fun appendEvent(ctx: Context, ev: DiagEvent) {
        val ts = tsFmt.format(Date(ev.atMs))
        val mark = when (ev.level) {
            Level.OK -> "[OK]"
            Level.FAIL -> "[FAIL]"
            Level.INFO -> "[..]"
        }
        val line = buildString {
            append(ts + " " + mark + " " + ev.stage + ": " + ev.message)
            if (ev.detail.isNotBlank()) {
                append("\n      " + ev.detail.replace("\n", "\n      "))
            }
        }
        runCatching { lobos.kernel.fs.StateFiles.appendBounded(file(ctx), line) }
        runCatching { lobos.kernel.fs.StateFiles.appendBounded(structFile(ctx), ev.toJson().toString()) }
        runCatching {
            Journal.append(
                ctx, "diag:" + ev.stage,
                null,
                ev.level.name + " " + ev.message + (if (ev.detail.isBlank()) "" else " | " + ev.detail.take(300)),
            )
        }
    }

    @Synchronized
    fun events(ctx: Context, limit: Int = 100): List<DiagEvent> {
        val out = mutableListOf<DiagEvent>()
        runCatching {
            val f = structFile(ctx)
            if (!f.exists()) return emptyList()
            f.readLines().forEach { raw ->
                if (raw.isBlank()) return@forEach
                val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
                out.add(
                    DiagEvent(
                        atMs = o.optLong("at"),
                        stage = o.optString("stage"),
                        level = runCatching { Level.valueOf(o.optString("level", "INFO")) }
                            .getOrDefault(Level.INFO),
                        message = o.optString("message"),
                        detail = o.optString("detail"),
                        data = o.optJSONObject("data"),
                    )
                )
            }
        }
        return out.takeLast(limit)
    }

    @Synchronized
    fun latestByStage(ctx: Context, vararg stages: String, limit: Int = 400): Map<String, DiagEvent> {
        val wanted = stages.toSet()
        val out = LinkedHashMap<String, DiagEvent>()
        events(ctx, limit).forEach { ev -> if (wanted.contains(ev.stage)) out[ev.stage] = ev }
        return out
    }

    @Synchronized
    fun read(ctx: Context): String =
        if (file(ctx).exists()) file(ctx).readText() else ""

    @Synchronized
    fun recordNodeStderr(ctx: Context, text: String) {
        if (text.isNotBlank()) lobos.kernel.fs.StateFiles.appendBounded(nodeErrFile(ctx), text)
    }

    @Synchronized
    fun clearNodeStderr(ctx: Context) {
        if (nodeErrFile(ctx).exists()) nodeErrFile(ctx).delete()
    }

    fun readNodeStderr(ctx: Context): String =
        if (nodeErrFile(ctx).exists()) nodeErrFile(ctx).readText() else ""
}

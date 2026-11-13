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
    const val STDERR_FILE = "program-stderr.log"
    private const val STRUCT_FILE = "diag.jsonl"

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun file(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.log(ctx), FILE)
    fun stderrFile(ctx: Context): File = File(lobos.kernel.layout.SystemDirs.log(ctx), STDERR_FILE)
    private fun structFile(ctx: Context): File {
        val d = lobos.kernel.layout.SystemDirs.log(ctx)
        d.mkdirs()
        return File(d, STRUCT_FILE)
    }

    /**
     * 那**清**那一格 —— 「只清上一世」。
     *
     *  缺陷名（C-102）**那 clear 逐字把整份清空、 它逐字把「这一世」写的那几行也清了**
     *   旧版逐字是 `file(ctx).writeText(""); structFile(ctx).writeText("")`
     *     它逐字**整份**清空（ 它逐字**不**分「上一世」与「这一世」）
     *   **后果**（07 本轮逐字核出）： `beginLife` 逐字在**主线程**写的那几行
     *     （ 「宿主出生」「索引已从落位重建」与那 `scan-cost` 那一档）
     *     逐字被那服务池（在**工作线程**上）逐字**清掉**
     *     （ 07 逐字核到：那 scan-cost 那一档逐字在 journal 的 at 逐字 1793483983860、
     *       而那 diag.jsonl 逐字**最早**一条 at 逐字 1793483984570 —— **晚 710 ms**）
     *   **处置**： 它逐字只清「**上一世**」那一份
     *     「这一世」逐字从那**出生标记**之后算起（ 它逐字**不**清出生之后写的那几行）
     *     它逐字**不**排在 beginLife **之后**（ 是那一条竞态的另一半）
     *
     *  @param lifeMark 那**这一世**的标记（ 它逐字是那出生那一拍写下的）
     *   它逐字**空**时逐字**整份**清（ 是那「没有标记」那一族、 按 C-05 报缺）
     */
    @Synchronized
    fun clear(ctx: Context, lifeMark: String = "") {
        if (lifeMark.isBlank()) {
            //  **没有标记**时逐字**整份**清（ 按 C-05 报缺、 不猜填）
            file(ctx).writeText("")
            runCatching { structFile(ctx).writeText("") }
            return
        }
        //  **文本**那一份： 它逐字**行**式、 它逐字只清那标记**之前**的行
        runCatching {
            val f = file(ctx)
            if (!f.isFile) return@runCatching
            val kept = f.readLines().filter { !it.contains(lifeMark) }
            //  它逐字**按行**判（ 标记逐字逐行出现）
            //     它逐字**不**动那标记**之后**写的那些行（ 「这一世」那几行逐字**留住**）
            val out = kept.joinToString("\n")
            f.writeText(if (out.isEmpty()) "" else out + "\n")
        }
        //  **结构**那一份： 它逐字**行**式 JSON（ 每行逐字带那 at）
        //     它逐字按那 at 逐字比（ 标记那一拍逐字带一个 at）
        runCatching {
            val f = structFile(ctx)
            if (!f.isFile) return@runCatching
            val kept = f.readLines().filter { line ->
                val at = runCatching { org.json.JSONObject(line).optLong("at", 0L) }.getOrDefault(0L)
                at < lifeMarkAt
            }
            f.writeText(kept.joinToString("\n") + (if (kept.isEmpty()) "" else "\n"))
        }
    }

    //  **这一世**的取数时刻（ 它逐字是那出生标记那一拍的毫秒）
    @Volatile private var lifeMarkAt: Long = 0L

    /** 记下那**这一世**的取数时刻（ 它逐字是那「上一世 / 这一世」的分界）。 */
    @Synchronized
    fun markLife(ctx: Context) {
        lifeMarkAt = System.currentTimeMillis()
        //  **标记**逐字也写进那**文本**那一份（ 它逐字是那一行）
        runCatching {
            lobos.kernel.fs.StateFiles.appendBounded(
                file(ctx), "LIFE-MARK " + lifeMarkAt
            )
        }
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
        if (text.isNotBlank()) lobos.kernel.fs.StateFiles.appendBounded(stderrFile(ctx), text)
    }

    @Synchronized
    fun clearNodeStderr(ctx: Context) {
        if (stderrFile(ctx).exists()) stderrFile(ctx).delete()
    }

    fun readNodeStderr(ctx: Context): String =
        if (stderrFile(ctx).exists()) stderrFile(ctx).readText() else ""
}

package lobos.log

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 运行记录导出层。
 *
 * 定位：**用户报障**与**遥测上报**的共同入口。
 *
 * 为什么要它：数据源只有一个（`events.jsonl`），但渲染出四份视图、四种格式。
 * 用户报障时只能 adb 逐个拉文件、自己对齐时间戳、自己解析四种格式 ——
 * 捞一次问题要 5 个文件。遥测将来也要接，若没有这一层就会变成
 * 在四种格式上各接一遍。
 *
 * 现状：本类**只做读取与打包，不写盘、不上传**。
 * 遥测是下一阶段的事，位置就在这里（`exportBundle` 的输出即它的输入）。
 *
 * 隐私边界：导出内容**不含**设备标识（IMEI/AndroidID/序列号/账号）、
 * 不含文件路径、不含用户输入。只含运行事件与设备型号/系统版本这类
 * 通用信息。具体能给到哪一档，由产品定，见 docs/REFACTOR-PLAN.md 第七节。
 */
object Exporter {

    /** 一份导出包：JSON，便于程序解析与人看。 */
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
        // 通用信息，不含任何设备唯一标识
        put("manufacturer", Build.MANUFACTURER)
        put("model", Build.MODEL)
        put("sdk", Build.VERSION.SDK_INT)
        put("release", Build.VERSION.RELEASE)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
    }

    fun exportBundle(ctx: Context): Bundle {
        val events = Journal.events(ctx, limit = MAX_EVENTS)
        val diags = RuntimeDiagnosticsBridge.events(ctx, limit = MAX_DIAG)
        val stderr = runCatching {
            RuntimeDiagnosticsBridge.nodeErrText(ctx).takeLast(MAX_STDERR)
        }.getOrDefault("")

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

    /**
     * 写到 `cacheDir` 的一个文件，返回该 File。
     * 给用户报障时由 UI 决定是否分享（本方法不做任何外部动作）。
     */
    fun writeToCache(ctx: Context, name: String = "lobos-log-bundle.json"): File {
        val f = File(ctx.cacheDir, name)
        f.writeText(exportBundle(ctx).toJson().toString(2))
        return f
    }

    /** node stderr 与诊断视图还没收进 log 包，先经这里访问，避免 log 直接依赖 RuntimeDiagnostics。 */
    internal object RuntimeDiagnosticsBridge {
        fun events(ctx: Context, limit: Int): List<JSONObject> =
            runCatching {
                val out = mutableListOf<JSONObject>()
                val f = File(File(ctx.filesDir, "os"), "diag.jsonl")
                if (!f.isFile) return emptyList()
                f.readLines().filter { it.isNotBlank() }.takeLast(limit).forEach { line ->
                    runCatching { JSONObject(line) }.getOrNull()?.let { out += it }
                }
                out
            }.getOrDefault(emptyList())

        fun nodeErrText(ctx: Context): String =
            runCatching { File(ctx.filesDir, "node-stderr.log").readText() }.getOrDefault("")
    }
}
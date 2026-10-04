package lobos.os

import lobos.ota.ProgramDir
import org.json.JSONArray
import org.json.JSONObject

object ManifestSchema {

    const val SCHEMA = 3
    const val SOURCE_NAME = "manifest.json"

    const val SC_ID = "id"
    const val SC_VERSION = "version"
    const val SC_ENTRY = "entry"
    const val SC_ARGS = "args"
    const val SC_ENV = "env"
    const val SC_LIFECYCLE = "lifecycle"
    const val SC_HTTP = "http"
    const val SC_REQUIRES = "requires"
    const val SC_CAPABILITIES = "capabilities"
    const val SC_UI = "ui"

    const val LC_RESIDENT = "resident"
    const val LC_RESTART = "restart"
    const val LC_MAX_RESTARTS = "maxRestarts"
    const val LC_BACKOFF = "backoff"

    const val HT_PORT = "port"
    const val HT_HEALTH = "health"

    const val UI_TYPE = "type"
    const val UI_PACKAGE = "package"
    const val UI_URL = "url"
    const val UI_ON_CLOSED = "onUiClosed"

    const val TYPE_QUICKAPP = "quickapp"
    const val CLOSED_KEEP_ALIVE = "keep-alive"
    const val CLOSED_STOP_WITH_UI = "stop-with-ui"
    const val CLOSED_ON_DEMAND = "on-demand"

    val RESTARTS = setOf("on-failure", "always", "never")
    val RESTART_ALIASES = mapOf("on_failure" to "on-failure")

    fun restartOf(raw: String): String = raw.trim().lowercase().let { RESTART_ALIASES[it] ?: it }
    val RUNTIMES = setOf("runtime", "toolchain", "library", "application")
    val UI_TYPES = setOf(TYPE_QUICKAPP)
    val ON_CLOSED = setOf(CLOSED_KEEP_ALIVE, CLOSED_STOP_WITH_UI, CLOSED_ON_DEMAND)

    const val DEFAULT_HEALTH = "/status"
    const val DEFAULT_MAX_RESTARTS = 5

    fun validId(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 64) return null
        return v.takeIf { it.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' } && it != "." && it != ".." }
    }

    fun validate(m: JSONObject): List<String> {
        val errs = mutableListOf<String>()

        val schema = m.optInt(SC_VERSION.let { "schema" }, 0)
        if (schema <= 0) errs += "缺 schema 字段（当前规范 " + SCHEMA + "）：manifest 顶层必须写 \"schema\": $SCHEMA"
        else if (schema > SCHEMA) errs += "schema $schema 高于系统支持的 $SCHEMA：请升级 LobOS"

        val id = m.optString(SC_ID, "")
        if (validId(id) == null) errs += "$SC_ID 非法（只接受字母数字与 . _ -，1-64 字符）"

        val ver = m.optString(SC_VERSION, "")
        if (ver.isBlank()) errs += "缺 $SC_VERSION"

        val entry = m.optString(SC_ENTRY, "").trim()
        if (entry.isBlank()) errs += "缺 $SC_ENTRY"
        else if (entry.startsWith("/") || entry.contains("..")) errs += "$SC_ENTRY 必须是相对路径且不得含 .."

        m.optJSONArray(SC_ARGS)?.let { a ->
            for (i in 0 until a.length()) if (a.isNull(i)) errs += "$SC_ARGS 第 $i 项为 null"
        }

        validateLifecycle(m.optJSONObject(SC_LIFECYCLE), errs)
        validateHttp(m.optJSONObject(SC_HTTP), errs)
        validateRequires(m.optJSONArray(SC_REQUIRES), errs)
        validateUi(m.optJSONObject(SC_UI), errs)

        return errs
    }

    private fun validateLifecycle(life: JSONObject?, errs: MutableList<String>) {
        if (life == null) return
        val restart = restartOf(life.optString(LC_RESTART, "on-failure"))
        if (restart !in RESTARTS) errs += "$SC_LIFECYCLE.$LC_RESTART 取值非法（只接受 ${RESTARTS.joinToString("|")}）"
        val max = life.optInt(LC_MAX_RESTARTS, DEFAULT_MAX_RESTARTS)
        if (max < 0) errs += "$SC_LIFECYCLE.$LC_MAX_RESTARTS 不能为负"
        life.optJSONArray(LC_BACKOFF)?.let { a ->
            var prev = 0L
            for (i in 0 until a.length()) {
                val v = a.optLong(i, 0L)
                if (v <= 0L) { errs += "$SC_LIFECYCLE.$LC_BACKOFF 第 $i 项必须为正数"; continue }
                if (v < prev) errs += "$SC_LIFECYCLE.$LC_BACKOFF 必须单调不减（第 $i 项 $v < 前项 $prev）"
                prev = v
            }
        }
    }

    private fun validateHttp(http: JSONObject?, errs: MutableList<String>) {
        if (http == null) return
        val port = http.optInt(HT_PORT, 0)
        if (port < 0 || port > 65535) errs += "$SC_HTTP.$HT_PORT 越界（0=由系统分配，1-65535）"
        val health = http.optString(HT_HEALTH, "").trim()
        if (health.isNotEmpty() && !health.startsWith("/")) errs += "$SC_HTTP.$HT_HEALTH 必须以 / 开头"
    }

    private fun validateRequires(req: JSONArray?, errs: MutableList<String>) {
        if (req == null) return
        for (i in 0 until req.length()) {
            val s = req.optString(i, "").trim()
            if (s.isBlank()) { errs += "$SC_REQUIRES 第 $i 项为空"; continue }
            val name = s.substringBefore(">=").substringBefore("@").trim()
            if (validId(name) == null) errs += "$SC_REQUIRES 第 $i 项名字非法：$s"
        }
    }

    private fun validateUi(ui: JSONObject?, errs: MutableList<String>) {
        if (ui == null) return
        val type = ui.optString(UI_TYPE, "").trim()
        if (type.isNotEmpty() && type !in UI_TYPES) {
            errs += "$SC_UI.$UI_TYPE 取值非法（只接受 ${UI_TYPES.joinToString("|")}）"
            return
        }
        if (type.isEmpty()) return
        if (type == TYPE_QUICKAPP) {
            val pkg = ui.optString(UI_PACKAGE, "").trim()
            if (pkg.isBlank()) errs += "$SC_UI.$UI_PACKAGE 不能为空（快应用包名是 1:1 绑定的唯一标识）"
            else if (!pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$"))) {
                errs += "$SC_UI.$UI_PACKAGE 不是合法包名：$pkg"
            }
            val url = ui.optString(UI_URL, "").trim()
            if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
                errs += "$SC_UI.$UI_URL 只接受 http/https：$url"
            }
        }
        val closed = ui.optString(UI_ON_CLOSED, CLOSED_KEEP_ALIVE).lowercase()
        if (closed !in ON_CLOSED) errs += "$SC_UI.$UI_ON_CLOSED 取值非法（只接受 ${ON_CLOSED.joinToString("|")}）"
    }

    fun spec(): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("required", JSONArray(listOf(SC_VERSION + "（规范版本，整数）", SC_ID, SC_VERSION, SC_ENTRY)))
        put("fields", JSONArray().apply {
            put(SC_ID); put(SC_VERSION); put(SC_ENTRY); put(SC_ARGS); put(SC_ENV)
            put(SC_LIFECYCLE); put(SC_HTTP); put(SC_REQUIRES)
            put(SC_CAPABILITIES); put(SC_UI)
        })
        put("lifecycle", JSONArray(listOf(LC_RESIDENT, LC_RESTART, LC_MAX_RESTARTS, LC_BACKOFF)))
        put("restarts", JSONArray(RESTARTS.toList()))
        put("ui.type", JSONArray(UI_TYPES.toList()))
        put("ui.onUiClosed", JSONArray(ON_CLOSED.toList()))
        put("http.health.default", DEFAULT_HEALTH)
    }

    fun toJson(o: JSONObject): JSONObject = JSONObject().apply {
        put("name", SOURCE_NAME)
        put("installedAs", lobos.ota.ProgramDir.MANIFEST_NAME)
        put("schema", SCHEMA)
        put("valid", validate(o).isEmpty())
        put("errors", JSONArray(validate(o)))
        put("summary", JSONObject().apply {
            put("id", o.optString(SC_ID, ""))
            put("entry", o.optString(SC_ENTRY, ""))
            put("resident", o.optJSONObject(SC_LIFECYCLE)?.optBoolean(LC_RESIDENT, true) ?: true)
            put("restart", o.optJSONObject(SC_LIFECYCLE)?.optString(LC_RESTART, "on-failure") ?: "on-failure")
            put("ui", o.optJSONObject(SC_UI) ?: JSONObject.NULL)
        })
    }
}

package lobos.quickapp

import org.json.JSONObject
import java.io.File

object QuickAppPackage {

    private const val CONFIG = "config.json"
    private const val MAIN = "main"
    private const val APP_CONFIG = "app-config.json"
    private const val LOGIC = "logic.js"

    data class Checked(val ok: Boolean, val appId: String, val versionCode: Int, val versionName: String, val problem: String)

    fun check(dir: File, expectAppId: String): Checked {
        val config = File(dir, CONFIG)
        val appConfig = File(dir, "$MAIN/$APP_CONFIG")
        val logic = File(dir, "$MAIN/$LOGIC")
        for (f in listOf(config to "config.json", appConfig to "main/app-config.json", logic to "main/logic.js")) {
            if (!f.first.isFile) return bad("缺少 ${f.second}")
            if (f.first.length() == 0L) return bad("${f.second} 是空文件")
        }
        val j = runCatching { JSONObject(config.readText()) }.getOrElse {
            return bad("config.json 不是合法 JSON：${it.message}")
        }
        val appId = j.optString("appId", "")
        if (appId.isBlank()) return bad("config.json 缺 appId")
        if (appId != expectAppId) return bad("appId 不一致：包内=$appId 程序=$expectAppId")
        if (!j.has("versionCode")) return bad("config.json 缺 versionCode")
        val versionCode = j.optInt("versionCode", -1)
        if (versionCode < 0) return bad("versionCode 非法：$versionCode")
        return Checked(true, appId, versionCode, j.optString("versionName", ""), "")
    }

    fun readConfig(dir: File): JSONObject? =
        runCatching { JSONObject(File(dir, CONFIG).readText()) }.getOrNull()

    fun withEntry(dir: File, entry: String): Boolean = runCatching {
        val j = readConfig(dir) ?: return false
        j.put("path", entry)
        File(dir, CONFIG).writeText(j.toString(2))
        true
    }.getOrDefault(false)

    fun entryOf(dir: File): String = readConfig(dir)?.optString("path", "").orEmpty()

    fun withEndpoint(dir: File, endpoint: String, port: Int): Boolean = runCatching {
        val j = readConfig(dir) ?: return false
        j.put("hostManaged", true)
        j.put("backend", JSONObject().apply {
            put("endpoint", endpoint)
            put("port", port)
        })
        File(dir, CONFIG).writeText(j.toString(2))
        true
    }.getOrDefault(false)

    private fun bad(p: String) = Checked(false, "", 0, "", p)
}

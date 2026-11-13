package lobos.services.supply

import org.json.JSONArray
import org.json.JSONObject

object ManifestWriter {

    fun write(dir: java.io.File, meta: JSONObject?, version: String, role: String): Boolean =
        runCatching {
            java.io.File(dir, lobos.services.supply.ProgramDir.MANIFEST_NAME).writeText(build(meta, version, role))
        }.isSuccess

    fun build(meta: JSONObject?, version: String, role: String): String {
        val m = JSONObject()
        m.put("schema", meta?.optInt("schema", 1) ?: 1)
        m.put("id", meta?.optString("id", "") ?: "")
        m.put("version", version)
        m.put("entry", meta?.optString("entry", "") ?: "")
        m.put("role", role)
        m.put("essential", meta?.optBoolean("essential", false) ?: false)
        m.put("requires", meta?.optJSONArray("requires") ?: JSONArray())

        m.put("lifecycle", JSONObject().put("resident", true))
        meta?.optString("source", "")?.takeIf { it.isNotBlank() }?.let { m.put("source", it) }
        meta?.optString("sourceSha256", "")?.takeIf { it.isNotBlank() }?.let { m.put("sourceSha256", it) }
        return m.toString(2)
    }
}
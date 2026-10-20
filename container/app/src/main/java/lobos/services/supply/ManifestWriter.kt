package lobos.services.supply

import org.json.JSONArray
import org.json.JSONObject

/** 落位目录里那份单子的唯一生成处 —— 按 ManifestSchema 的键名写，字段值来自 meta 或落位事实。
 *  运行时不再把 component-meta.json 原样抄成第二份（那是「同一个东西两个名字」的来源）。
 *  component-meta.json 仍是打包内部产物（描述这件怎么打的），落在落位目录里供装配与自检读。
 */
object ManifestWriter {

    // C-102：本单子里的 role 必须是**生产时声明的**值（构建期写进 meta 的那一格）。
    // 调用方今天传的是 PieceScan.roleFromDisk(...)（运行期现读 ELF 头），
    // 那一条已列入「需要别人跟着改的清单」（ProgramInstallPipeline.kt:186、PreinstalledPieces.kt:153）。
    // 这里只落盘，不再替调用方判断形态。
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
        meta?.optString("source", "")?.takeIf { it.isNotBlank() }?.let { m.put("source", it) }
        meta?.optString("sourceSha256", "")?.takeIf { it.isNotBlank() }?.let { m.put("sourceSha256", it) }
        return m.toString(2)
    }
}
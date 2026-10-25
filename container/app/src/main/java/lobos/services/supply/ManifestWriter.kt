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
        //  ★ 缺陷名（C-102）**同一件事在两条路径上有两个默认值，而落位那一条取到了 false 那个**
        //    —— 而「装上来就常驻」（=> 而 `lifecycle.resident` 那一格）今天**缺**。
        //
        //  而**缺了它为什么等于默认否掉**：
        //    · 读那一侧：ProgramRegistry.kt:146 逐字是
        //      `resident = life?.optBoolean("resident", true) ?: true`
        //      => 而而**缺省是 true**（=> 而那是「默认常驻」那一问）；
        //    · 写那一侧：ProgramDir.kt:169 逐字是 `val resident = spec?.resident == true`，
        //      而而落位那一支的 spec 是 **null**（=> 而 :163 逐字 `val spec = if (landed) null else …`）
        //      => 而而 **`null?.resident == true` 是 false**（=> 而那是「装上来就常驻」那一问）；
        //      => 而而那正是 journal 逐字「resident=false」那一条。
        //    => 而按 C-135 那**读那一问与写那一问是两问**（=> 而「那一格不存在时取到什么」vs「装那一趟它该是什么」），
        //      而按 01 那一件 S116 第一节的裁定：**「默认常驻」= true（已核）、
        //      「装上来就常驻」= true（本件裁定）**。
        //
        //  => 而按 C-104（=> 而「同一格有两条生成路径」那一族）：
        //    · **设备上被读的那一份**是落位侧那一份（=> 而 ProgramRegistry.kt:100 逐字是
        //      `val life = json.optJSONObject("lifecycle")`，=> 而那个 json 读的是
        //      ProgramDir.MANIFEST_NAME = program-manifest.json）；
        //    · 而**构建期那一份**（component-meta.json）今天**带不过来**
        //      （=> 而上面那一串 m.put 里没有 lifecycle 那一格）。
        //    => 而这一格**该写在设备上被读的那一份**（=> 而落位侧那一份），=> 而而它今天缺。
        //
        //  => 而按 01 与 04 那一族的口径「启停意图由安装与用户决定」：
        //    => 而「装上来就常驻」**是安装那一侧的决定**，=> 而而「用户后来停掉它」是**另一格**
        //      （=> 而那是 `desired` 那一列，=> 而它归用户与运行期）。
        //    => 而这一格**必须显式写**，=> 而而**不许靠缺省**（=> 而缺省那一格今天两边不一致）。
        m.put("lifecycle", JSONObject().put("resident", true))
        meta?.optString("source", "")?.takeIf { it.isNotBlank() }?.let { m.put("source", it) }
        meta?.optString("sourceSha256", "")?.takeIf { it.isNotBlank() }?.let { m.put("sourceSha256", it) }
        return m.toString(2)
    }
}
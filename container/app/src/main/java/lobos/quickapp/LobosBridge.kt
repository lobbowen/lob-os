package lobos.quickapp

import android.content.Context
import org.json.JSONObject
import java.io.File
import lobos.os.ProgramIndex
import lobos.os.ProgramManager
import lobos.ota.ProgramDir

object LobosBridge {

    private const val PONG = "pong"

    fun handle(ctx: Context, event: String, data: JSONObject?, reply: (JSONObject) -> Unit) {
        when (event) {
            "ping" -> reply(JSONObject().apply { put("ok", true); put("echo", PONG) })
            "capabilities" -> reply(capabilities())
            "backendEndpoint" -> reply(backendEndpoint(ctx, data))
            "desktopIcon.add" -> desktopAdd(ctx, data, reply)
            "desktopIcon.remove" -> desktopRemove(ctx, data, reply)
            "desktopIcon.state" -> desktopState(ctx, data, reply)
            else -> reply(JSONObject().apply { put("ok", false); put("error", "unknown event: $event") })
        }
    }

    private fun capabilities(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("runtime", "lobos")
        put("hostApis", listOf(
            "ping", "capabilities", "backendEndpoint",
            "desktopIcon.add", "desktopIcon.remove", "desktopIcon.state",
        ))
    }

    private fun backendEndpoint(ctx: Context, data: JSONObject?): JSONObject {
        val id = data?.optString("id", "").orEmpty()
        val endpoint = if (id.isBlank()) "" else {
            QuickAppPackage.readConfig(dirOf(ctx, id).quickAppDir())
                ?.optJSONObject("backend")?.optString("endpoint", "").orEmpty()
        }
        return JSONObject().apply { put("ok", true); put("endpoint", endpoint) }
    }

    private fun desktopAdd(ctx: Context, data: JSONObject?, reply: (JSONObject) -> Unit) {
        val id = data?.optString("id", "").orEmpty()
        if (id.isBlank()) return reply(err("id 不能为空"))
        if (ProgramIndex.get(ctx, id) == null) return reply(err("程序不在索引里: $id"))
        val ui = uiOf(ctx, id)
        val label = data?.optString("label", "").orEmpty().ifBlank { ui.name.ifBlank { id } }
        val icon = data?.optString("icon", "").orEmpty().ifBlank { ui.icon }
        if (label.isBlank()) return reply(err("快应用没有可显示的名字：$id"))
        DesktopIcons.request(ctx, id, label, ProgramManager.stateDirOf(ctx, id), icon) { st ->
            reply(JSONObject().apply {
                put("ok", true); put("state", st.wire); put("label", st.label)
            })
        }
    }

    private fun desktopRemove(ctx: Context, data: JSONObject?, reply: (JSONObject) -> Unit) {
        val id = data?.optString("id", "").orEmpty()
        if (id.isBlank()) return reply(err("id 不能为空"))
        DesktopIcons.withdraw(ctx, id) { ok ->
            DesktopIcons.state(ctx, id) { st ->
                reply(JSONObject().apply {
                    put("ok", ok); put("state", st.wire); put("label", st.label)
                })
            }
        }
    }

    private fun desktopState(ctx: Context, data: JSONObject?, reply: (JSONObject) -> Unit) {
        val id = data?.optString("id", "").orEmpty()
        if (id.isBlank()) return reply(err("id 不能为空"))
        DesktopIcons.state(ctx, id) { st ->
            reply(JSONObject().apply { put("ok", true); put("state", st.wire); put("label", st.label) })
        }
    }

    private fun uiOf(ctx: Context, id: String): Ui {
        val root = ProgramManager.stateDirOf(ctx, id)
        val version = runCatching { ProgramManager.currentVersion(ctx, id) }.getOrNull()
            ?: return Ui("", "")
        val mf = File(File(root, version), ProgramDir.MANIFEST_NAME)
        if (!mf.isFile) return Ui("", "")
        val o = runCatching { JSONObject(mf.readText()) }.getOrNull() ?: return Ui("", "")
        val ui = o.optJSONObject("ui") ?: return Ui("", "")
        return Ui(ui.optString("name", ""), ui.optString("icon", ""))
    }

    private fun dirOf(ctx: Context, id: String): ProgramDir = ProgramManager.dirOf(ctx, id)

    private data class Ui(val name: String, val icon: String)

    private fun err(msg: String) = JSONObject().apply { put("ok", false); put("error", msg) }
}

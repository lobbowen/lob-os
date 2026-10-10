package lobos.services.app

import android.content.Context
import java.io.File
import lobos.services.log.Journal
import lobos.services.supply.ProgramDir
import lobos.services.reg.ProgramManager

object QuickAppBinder {

    fun bindIfQuickApp(context: Context, programId: String, quickAppDir: File) {
        if (!quickAppDir.isDirectory) return
        if (!QuickAppRegistry.isQuickApp(context, programId)) return
        val port = ProgramManager.resolveHttpPort(context, programId, 0)
        if (port <= 0) {
            Journal.note(
                context, "quickapp", false, "端口段已满，快应用前端未注入后端地址",
                "id=" + programId,
            )
            return
        }
        if (!QuickAppPackage.withEndpoint(quickAppDir, "http://127.0.0.1:" + port, port)) {
            Journal.note(context, "quickapp", false, "无法把后端地址写进前端 config.json", "id=" + programId)
            return
        }
        val registered = QuickAppRegistry.register(context, programId)
        Journal.note(
            context, "quickapp", registered,
            "快应用已配对：端口=$port 前端=" + quickAppDir.absolutePath,
            "id=" + programId,
        )
        loadIntoDimina(context, programId, quickAppDir, port, uiEntryOf(context, programId))
    }

    private fun uiEntryOf(context: Context, programId: String): String {
        val dir = ProgramManager.stateDirOf(context, programId)
        val version = ProgramManager.currentVersion(context, programId) ?: return ""
        val mf = File(File(dir, version), ProgramDir.MANIFEST_NAME)
        if (!mf.isFile) return ""
        val ui = runCatching { org.json.JSONObject(mf.readText()).optJSONObject("ui") }.getOrNull()
        return ui?.optString("entry", "")?.trim().orEmpty()
    }

    private fun loadIntoDimina(context: Context, programId: String, quickAppDir: File, port: Int, entry: String) {
        if (!QuickAppHost.ready()) {
            Journal.note(
                context, "quickapp", false,
                "快应用运行时尚未就绪，等下次 reconcile 再装入 dimina",
                "id=" + programId,
            )
            return
        }
        QuickAppHost.install(programId, quickAppDir, port, entry) { r ->
            r.onSuccess {
                Journal.note(
                    context, "quickapp", true,
                    "前端已装入 dimina：可打开了",
                    "id=" + programId + " port=" + port,
                )
            }.onFailure {
                Journal.note(
                    context, "quickapp", false, "前端装入 dimina 失败：装得上但打不开",
                    "id=" + programId + " " + (it.message ?: it.javaClass.simpleName),
                )
            }
        }
    }
}

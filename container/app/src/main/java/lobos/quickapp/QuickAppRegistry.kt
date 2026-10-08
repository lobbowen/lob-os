package lobos.quickapp

import android.content.Context
import java.io.File
import lobos.os.UnitEntry
import lobos.os.ProgramDir
import lobos.os.ProgramIndex
import lobos.os.ProgramManager
import org.json.JSONObject

object QuickAppRegistry {

    private const val TAG = "lobos.quickapp"

    fun isQuickApp(ctx: Context, id: String): Boolean = ProgramManager.stateDirOf(ctx, id).let { dir ->
        ProgramManager.currentVersion(ctx, id)?.let { v ->
            val manifest = File(File(dir, v), ProgramDir.MANIFEST_NAME)
            if (manifest.isFile) {
                runCatching { JSONObject(manifest.readText()) }.getOrNull()
                    ?.optJSONObject("ui")?.optString("type", "") == "quickapp"
            } else {
                false
            }
        } == true
    }

    fun register(ctx: Context, id: String): Boolean {
        if (!isQuickApp(ctx, id)) return false
        val port = ProgramManager.resolveHttpPort(ctx, id, 0)
        if (port <= 0) {
            android.util.Log.e(TAG, "端口段已满，无法登记快应用: $id")
            return false
        }
        val ui = uiOf(ctx, id)
        val frontend = ProgramManager.stateDirOf(ctx, id).let { File(it, "quickapp") }
        ProgramIndex.mutate(ctx, id) { e ->
            e.edited(
                uiPackage = ui.pkg,
                uiName = ui.name,
                uiIcon = ui.icon,
                onUiClosed = ui.onClosed,
                httpPort = port,
                httpHealth = ui.health,
            )
        }
        android.util.Log.i(
            TAG,
            "快应用已登记: $id 端口=$port 名字=${ui.name} 图标=${ui.icon} 前端=${frontend.absolutePath}",
        )
        return true
    }

    fun listed(ctx: Context): List<UnitEntry> =
        ProgramIndex.all(ctx).filter { it.level == lobos.os.Level.PROGRAM && it.uiPackage.isNotBlank() }

    private fun uiOf(ctx: Context, id: String): Ui {
        val root = ProgramManager.stateDirOf(ctx, id)
        val version = ProgramManager.currentVersion(ctx, id) ?: return Ui("", "", "", "", "")
        val manifest = File(File(root, version), ProgramDir.MANIFEST_NAME)
        if (!manifest.isFile) return Ui("", "", "", "", "")
        val o = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return Ui("", "", "", "", "")
        val ui = o.optJSONObject("ui") ?: return Ui("", "", "", "", "")
        val http = o.optJSONObject("http")
        return Ui(
            name = ui.optString("name", ""),
            icon = ui.optString("icon", ""),
            pkg = ui.optString("package", ""),
            onClosed = ui.optString("onUiClosed", ""),
            health = http?.optString("health", "").orEmpty().ifBlank { "/status" },
        )
    }

    data class Ui(
        val name: String,
        val icon: String,
        val pkg: String,
        val onClosed: String,
        val health: String,
    )
}

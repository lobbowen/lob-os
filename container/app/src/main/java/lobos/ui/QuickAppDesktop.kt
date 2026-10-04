package lobos.ui

import android.content.Context
import android.graphics.drawable.Drawable
import lobos.os.ProgramIndex
import org.json.JSONObject

object QuickAppDesktop {

    private const val MANIFEST = "program-manifest.json"

    fun sync(ctx: Context, id: String): String {
        val entry = ProgramIndex.get(ctx, id)
            ?: return remove(ctx, id).let { "not-installed" }
        val mf = readManifest(ctx, entry.stateDir)
        if (mf == null) return "no-manifest"
        val ui = mf.optJSONObject("ui")
        if (ui == null) return "not-a-quickapp"
        if (ui.optString("type", "") != "quickapp") return "not-a-quickapp"
        val icon = QuickAppIcons.fromFile(iconFile(entry.stateDir, ui))
            ?: QuickAppIcons.fromBase64(ui.optString("iconBase64", ""))
            ?: return "no-icon"
        val label = ui.optString("name", "").ifBlank { entry.id }
        val already = QuickAppIcons.has(ctx, id)
        val ok = QuickAppIcons.add(ctx, id, label, icon, launchIntent(ctx, id))
        return when {
            already -> "already-pinned"
            ok -> "pinned"
            else -> "pin-refused"
        }
    }

    fun remove(ctx: Context, id: String): String =
        if (QuickAppIcons.remove(ctx, id)) "unpinned" else "not-pinned"

    fun status(ctx: Context, id: String): JSONObject = JSONObject().apply {
        put("id", id)
        put("pinned", QuickAppIcons.has(ctx, id))
        put("shortcutSupported", QuickAppIcons.isSupported(ctx))
        put("pinnedCount", QuickAppIcons.all(ctx).size)
    }

    fun launchIntent(ctx: Context, id: String) =
        QuickAppIcons.launchIntent(ctx, QuickAppActivity::class.java, id)

    private fun readManifest(ctx: Context, stateDir: String): JSONObject? {
        val f = java.io.File(stateDir, MANIFEST)
        if (!f.isFile) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun iconFile(stateDir: String, ui: JSONObject): java.io.File? {
        val rel = ui.optString("icon", "").trim()
        if (rel.isEmpty()) return null
        val f = java.io.File(stateDir, rel)
        return f.takeIf { it.isFile }
    }
}

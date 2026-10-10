package lobos.services.app

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object DesktopIcons {

    private const val TAG = "lobos.quickapp"
    private const val PREFIX = "qa_"

    private val io = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "lobos-desktop-icon").apply { isDaemon = true }
    }

    enum class State(val wire: String, val label: String) {
        ADDED("added", "已添加"),
        NOT_ADDED("not_added", "未添加"),
        UNSUPPORTED("unsupported", "此桌面不支持添加"),
    }

    fun isSupported(ctx: Context): Boolean {
        val mgr = manager(ctx) ?: return false
        return runCatching { mgr.isRequestPinShortcutSupported }.getOrDefault(false)
    }

    fun state(ctx: Context, id: String, done: (State) -> Unit) {
        worker.execute {
            val st = computeState(ctx, id)
            io.post { done(st) }
        }
    }

    fun request(ctx: Context, id: String, label: String, programRoot: File, iconPath: String, done: (State) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val mgr = manager(app)
            if (mgr == null || !runCatching { mgr.isRequestPinShortcutSupported }.getOrDefault(false)) {
                io.post { done(State.UNSUPPORTED) }
                return@execute
            }
            if (isPinned(mgr, id)) {
                io.post { done(State.ADDED) }
                return@execute
            }
            val bmp = loadIcon(programRoot, iconPath)
            if (bmp == null) Log.w(TAG, "程序未带可用图标，桌面入口将用默认图标: $id")
            val builder = ShortcutInfo.Builder(app, PREFIX + id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntent(QuickAppLaunchActivity.intent(app, id))
            if (bmp != null) {
                builder.setIcon(android.graphics.drawable.Icon.createWithBitmap(bmp))
            }
            val ok = runCatching { mgr.requestPinShortcut(builder.build(), null) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "launcher 拒绝了固定请求: $id")
            val st = computeState(app, id)
            io.post { done(st) }
        }
    }

    fun withdraw(ctx: Context, id: String, done: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        worker.execute { io.post { done(withdrawNow(app, id)) } }
    }

    fun withdrawNow(ctx: Context, id: String): Boolean {
        val mgr = manager(ctx) ?: return false
        if (!isPinned(mgr, id)) return true
        val ok = runCatching { mgr.disableShortcuts(listOf(PREFIX + id)) }.isSuccess
        if (!ok) Log.w(TAG, "停用桌面入口失败: $id")
        return ok
    }

    private fun computeState(ctx: Context, id: String): State {
        val mgr = manager(ctx) ?: return State.UNSUPPORTED
        if (!runCatching { mgr.isRequestPinShortcutSupported }.getOrDefault(false)) return State.UNSUPPORTED
        return if (isPinned(mgr, id)) State.ADDED else State.NOT_ADDED
    }

    private fun isPinned(mgr: ShortcutManager, id: String): Boolean =
        runCatching { mgr.pinnedShortcuts }.getOrNull()?.any { it.id == PREFIX + id } == true

    private fun manager(ctx: Context) =
        ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager

    private fun loadIcon(programRoot: File, iconPath: String): Bitmap? {
        if (iconPath.isBlank()) return null
        val f = File(programRoot, iconPath)
        if (!f.isFile || f.length() == 0L) return null
        return decode(f.readBytes()) ?: decodeFile(f)
    }

    private fun decode(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    private fun decodeFile(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
}

package lobos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.File

object QuickAppIcons {

    const val EXTRA_ID = "lobos.quickapp.id"
    private const val PREFIX = "qa_"

    private fun key(id: String): String = PREFIX + id

    fun isSupported(ctx: Context): Boolean =
        ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager != null

    fun has(ctx: Context, id: String): Boolean = pinned(ctx, id) != null

    fun pinned(ctx: Context, id: String): ShortcutInfo? {
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return null
        return runCatching { mgr.getPinnedShortcuts() }.getOrNull()?.firstOrNull { it.id == key(id) }
    }

    fun all(ctx: Context): List<ShortcutInfo> {
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return emptyList()
        return runCatching { mgr.getPinnedShortcuts() }.getOrNull().orEmpty().filter { it.id.startsWith(PREFIX) }
    }

    fun remove(ctx: Context, id: String): Boolean {
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return false
        if (pinned(ctx, id) == null) return true
        return runCatching { mgr.removeDynamicShortcuts(listOf(key(id))) }.isSuccess
    }

    @SuppressLint("NewApi")
    fun add(ctx: Context, id: String, label: String, icon: Drawable?, launch: Intent): Boolean {
        val mgr = ctx.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return false
        val bmp = icon?.let { rasterize(it) } ?: return false
        val info = ShortcutInfo.Builder(ctx, key(id))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(androidx.core.content.pm.ShortcutIconCompat.makeWithBitmap(bmp))
            .setIntent(launch)
            .build()
        return runCatching { mgr.requestPinShortcut(info, null) }.getOrDefault(false)
    }

    fun launchIntent(ctx: Context, host: Class<*>, id: String): Intent =
        Intent(ctx, host).putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    fun idOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }

    fun fromFile(file: File?): Drawable? {
        if (file == null || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return BitmapDrawable.fromBitmap(decode(bytes), null)
    }

    fun fromBase64(b64: String?): Drawable? {
        val s = b64?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull() ?: return null
        return BitmapDrawable.fromBitmap(decode(bytes), null)
    }

    private fun decode(bytes: ByteArray): Bitmap =
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("icon bytes are not a bitmap")

    private fun rasterize(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 192
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 192
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }
}

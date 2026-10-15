package lobos.kernel.compat

import android.content.Context
import java.io.File

object PieceInspect {

    private var ok = false

    private fun bind(): Boolean {
        if (ok) return true
        ok = try {
            System.loadLibrary("lobosposix")
            true
        } catch (_: Throwable) {
            false
        }
        return ok
    }

    fun needed(ctx: Context, libName: String): String {
        if (!bind()) return "垫片未装上"
        val f: File = Compat.lib(ctx, libName) ?: return "系统库里没有 $libName"
        if (!f.isFile) return "$libName 不是普通文件"
        if (!f.canRead()) return "$libName 读不了"
        val r = runCatching { nativeNeeded(f.absolutePath) }
        r.exceptionOrNull()?.let { return "抛=" + it.javaClass.simpleName }
        val v = r.getOrNull() ?: return "返回空"
        return if (v.isBlank()) "空串" else v
    }

    private external fun nativeNeeded(path: String): String
}

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
        return runCatching { nativeNeeded(f.absolutePath) }
            .getOrElse { "读失败：" + (it.message ?: it.javaClass.simpleName) }
    }

    private external fun nativeNeeded(path: String): String
}

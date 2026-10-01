package lobos.os

import android.content.Context
import java.io.File

object PathGuard {

    fun integrityRoots(ctx: Context): List<File> = listOf(
        File(ctx.filesDir, "os"),
        File(ctx.filesDir, "sys"),
    )

    fun rejection(ctx: Context, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (!path.startsWith("/")) return "只接受绝对路径（拒绝相对路径绕过）"
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return "路径不可解析"
        for (root in integrityRoots(ctx)) {
            val canon = runCatching { root.canonicalFile }.getOrNull() ?: continue
            if (target == canon || target.path.startsWith(canon.path + "/")) {
                return "系统状态受完整性保护（S3）：" + canon.path
            }
        }
        return null
    }
}

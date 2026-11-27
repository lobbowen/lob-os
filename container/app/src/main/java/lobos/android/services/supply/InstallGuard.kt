package lobos.android.services.supply

import android.os.StatFs
import java.io.File

object InstallGuard {
    private val lock = Any()

    fun <T> serialized(block: () -> T): T = synchronized(lock) { block() }

    fun freeBytes(dir: File): Long {
        val anchor = dir.takeIf { it.isDirectory } ?: dir.parentFile ?: dir
        return runCatching { StatFs(anchor.absolutePath).availableBytes }.getOrDefault(-1L)
    }

    fun ensureSpace(dir: File, needBytes: Long): String? {
        if (needBytes <= 0L) return null
        val free = freeBytes(dir)
        if (free < 0L) return null
        if (free >= needBytes) return null
        return "磁盘空间不足：这次需要约 " + human(needBytes) + "，当前可用 " + human(free) +
            "。先清理空间或移除不用的组件再试。"
    }

    fun human(bytes: Long): String {
        if (bytes < 0) return "未知"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb < 1.0) return "不到 1MB"
        if (mb < 1024.0) return String.format("%.1fMB", mb)
        return String.format("%.2fGB", mb / 1024.0)
    }
}
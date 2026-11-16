package lobos.services.supply

import android.content.Context
import java.io.File

object Cleanup {
    data class Item(
        val kind: String,
        val label: String,
        val bytes: Long,
        val paths: List<File>,
    )

    fun scan(ctx: Context): List<Item> {
        val items = ArrayList<Item>()

        val parts = ArrayList<File>()
        runCatching {
            ctx.cacheDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part") || f.name.contains(".pkg.zip")) parts.add(f)
            }
        }
        if (parts.isNotEmpty()) {
            items.add(Item("downloads", "下载残留", parts.sumOf { f -> runCatching { f.length() }.getOrDefault(0L) }, parts))
        }

        val oldVers = ArrayList<File>()
        val opt = runCatching { lobos.kernel.layout.SystemDirs.opt(ctx) }.getOrNull()
        if (opt != null && opt.isDirectory) {
            opt.listFiles()?.forEach { piece ->
                if (!piece.isDirectory) return@forEach
                val vers = piece.listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }
                    ?: return@forEach
                for (v in vers.drop(1)) oldVers.add(v)
            }
        }
        if (oldVers.isNotEmpty()) {
            items.add(Item("old-versions", "旧版本组件", oldVers.sumOf { dirSize(it) }, oldVers))
        }

        val cacheDirs = ArrayList<File>()
        runCatching {
            ctx.cacheDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "image_cache") cacheDirs.add(f)
            }
        }
        if (cacheDirs.isNotEmpty()) {
            items.add(Item("cache", "缓存", cacheDirs.sumOf { dirSize(it) }, cacheDirs))
        }

        return items.filter { it.bytes > 0 }
    }

    fun reclaimable(ctx: Context): Long = scan(ctx).sumOf { it.bytes }

    fun run(ctx: Context): Pair<Long, Int> {
        var freed = 0L
        var n = 0
        for (item in scan(ctx)) {
            for (f in item.paths) {
                val size = if (f.isDirectory) dirSize(f) else runCatching { f.length() }.getOrDefault(0L)
                if (runCatching { f.deleteRecursively() }.getOrDefault(false) || !f.exists()) {
                    freed += size
                    n++
                }
            }
        }
        return Pair(freed, n)
    }

    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (isSymlink(dir)) return 0L
        if (dir.isFile) return runCatching { dir.length() }.getOrDefault(0L)
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                if (isSymlink(k)) continue
                if (k.isDirectory) stack.addLast(k) else total += runCatching { k.length() }.getOrDefault(0L)
            }
        }
        return total
    }

    private fun isSymlink(f: File): Boolean = runCatching {
        val st = android.system.Os.lstat(f.absolutePath)
        (st.st_mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
    }.getOrDefault(false)
}
package lobos.kernel.fs

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import lobos.kernel.layout.SystemDirs

object StateFiles {

    const val SCHEMA_KEY = "schema"
    private const val LEDGER_DIR = "run"
    private const val FAILED_FILE = "write-failures.log"

    fun writeAtomic(file: File, text: String): Boolean {
        val dir = file.parentFile ?: return false
        dir.mkdirs()
        val tmp = File(dir, file.name + TMP_INFIX + android.os.Process.myPid())
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }

            var renamed = true
            var errno = 0
            try {
                Os.rename(tmp.absolutePath, file.absolutePath)
            } catch (e: android.system.ErrnoException) {
                renamed = false
                errno = e.errno
            }
            if (!renamed) {

                val targetKind = when {
                    file.isDirectory -> "目标是目录"
                    file.isFile -> "目标是真文件"
                    java.nio.file.Files.isSymbolicLink(file.toPath()) -> "目标是软链"
                    file.exists() -> "目标存在但不是普通文件"
                    else -> "目标不存在"
                }
                tmp.delete()
                note(
                    dir,
                    "原子落位失败（rename 失败）: " + file.name + "（写完了、而没落上）" +
                        " errno=" + errno + " " + errnoName(errno) +
                        " 目标=" + targetKind
                )
                false
            } else {
                fsyncDir(dir)
                true
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            note(dir, "原子落位异常: " + file.name + " " + e::class.java.simpleName + ": " + (e.message ?: ""))
            false
        }
    }

    fun writeJson(file: File, obj: JSONObject): Boolean = try {
        if (!obj.has(SCHEMA_KEY)) obj.put(SCHEMA_KEY, 1)
        writeAtomic(file, obj.toString(2))
    } catch (e: Throwable) {
        note(file.parentFile ?: return false, "JSON 落盘异常: " + file.name + " " + e::class.java.simpleName + ": " + (e.message ?: ""))
        false
    }

    private fun errnoName(errno: Int): String = when (errno) {
        1 -> "EPERM"
        2 -> "ENOENT"
        13 -> "EACCES"
        17 -> "EEXIST"
        20 -> "ENOTDIR"
        21 -> "EISDIR"
        22 -> "EINVAL"
        28 -> "ENOSPC"
        30 -> "EROFS"
        36 -> "ENAMETOOLONG"
        39 -> "ENOTEMPTY"
        else -> if (errno < 0) "errno 不可得" else "errno=$errno"
    }

    private fun note(dir: File, detail: String) {
        runCatching {
            val f = File(dir, FAILED_FILE)
            if (!dir.isDirectory) dir.mkdirs()
            val prev = if (f.isFile) f.readText() else ""
            val lines = (prev + detail + "\n").split("\n").filter { it.isNotBlank() }

            FileOutputStream(f).use { it.write((lines.takeLast(50).joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)) }
        }
    }

    fun writeFailures(ctx: android.content.Context): List<String> =
        runCatching {
            val f = File(SystemDirs.log(ctx), FAILED_FILE)
            if (!f.isFile) emptyList() else f.readText().split("\n").filter { it.isNotBlank() }
        }.getOrDefault(emptyList())

    fun writeFailureCount(ctx: android.content.Context): Int = writeFailures(ctx).size

    fun readJson(file: File): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

    @Synchronized

    fun appendBounded(file: File, line: String, maxBytes: Long = 512 * 1024L, keepLines: Int = 500) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        try {

            val lineBytes = line.toByteArray(Charsets.UTF_8)
            val size = if (file.isFile) file.length() else 0L
            if (size + lineBytes.size + 1 <= maxBytes) {
                FileOutputStream(file, true).use { out ->
                    out.write(lineBytes)
                    out.write("\n".toByteArray(Charsets.UTF_8))
                }
                return
            }

            val kept = tailLines(file, keepLines, maxBytes)
            val out = StringBuilder()
            for (l in kept.lines) out.append(l).append('\n')
            if (kept.droppedOversizedCount > 0) {
                out.append("丢弃了一条超大记录（").append(kept.droppedOversizedBytes)
                    .append(" 字节）\n")
            }
            out.append(line).append('\n')
            writeAtomic(file, out.toString())
        } catch (_: Throwable) { }
    }

    private class TailResult(
        val lines: List<String>,
        val droppedOversizedBytes: Long,
        val droppedOversizedCount: Int,
    )

    private fun tailLines(file: File, keepLines: Int, maxBytes: Long): TailResult {
        if (!file.isFile) return TailResult(emptyList(), 0L, 0)
        val kept = mutableListOf<String>()
        var totalBytes = 0L
        var droppedOversizedBytes = 0L
        var droppedOversizedCount = 0
        java.io.RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            val chunk = ByteArray(64 * 1024)
            val newlines = ArrayList<Long>()
            var scanEnd = fileLen
            while (scanEnd > 0 && newlines.size <= keepLines) {
                val n = minOf(chunk.size.toLong(), scanEnd).toInt()
                val from = scanEnd - n
                raf.seek(from)
                raf.readFully(chunk, 0, n)
                var i = n - 1
                while (i >= 0) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        newlines.add(from + i)
                        if (newlines.size > keepLines) break
                    }
                    i--
                }
                scanEnd = from
            }
            val newestFirst = mutableListOf<Pair<Long, Long>>()
            val lastNewline = if (newlines.isEmpty()) -1L else newlines[0]
            if (lastNewline >= 0 && lastNewline < fileLen - 1) {
                newestFirst.add(lastNewline + 1 to fileLen)
            }
            val usable = if (newlines.size > keepLines) keepLines else newlines.size
            for (k in 0 until usable) {
                val lineEnd = newlines[k]
                val lineStart = if (k + 1 < newlines.size) newlines[k + 1] + 1 else 0L
                newestFirst.add(lineStart to lineEnd)
            }
            for (r in newestFirst) {
                if (kept.size >= keepLines) break
                val len = (r.second - r.first).toInt()
                if (len <= 0) continue
                val buf = ByteArray(len)
                raf.seek(r.first)
                raf.readFully(buf)
                val b = len.toLong()
                if (b > maxBytes) {
                    droppedOversizedBytes += b
                    droppedOversizedCount++
                    continue
                }
                if (totalBytes + b > maxBytes) continue
                val text = String(buf, Charsets.UTF_8)
                if (text.isBlank()) continue
                kept.add(text)
                totalBytes += b
            }
        }
        kept.reverse()
        return TailResult(kept, droppedOversizedBytes, droppedOversizedCount)
    }

    fun fsyncDir(dir: File) {
        runCatching {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }
    }

    fun cleanTemps(ctx: android.content.Context, minAgeMs: Long): List<String> {
        val out = mutableListOf<String>()
        val now = System.currentTimeMillis()
        fun sweep(d: File) {
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (f.isDirectory) continue
                if (!f.name.contains(TMP_INFIX)) continue
                if (now - f.lastModified() < minAgeMs) continue
                if (f.delete()) out.add(f.absolutePath)
            }
        }
        sweep(SystemDirs.libvar(ctx))
        sweep(SystemDirs.log(ctx))
        sweep(SystemDirs.run(ctx))
        sweep(SystemDirs.etc(ctx))
        sweep(SystemDirs.opt(ctx))
        sweep(ctx.filesDir)

        for (sub in arrayOf("usr/lib", "usr/bin")) {
            val d = File(ctx.filesDir, sub)
            val links = d.listFiles()?.filter { java.nio.file.Files.isSymbolicLink(it.toPath()) } ?: continue
            for (l in links) {
                val alive = runCatching {
                    val t = File(java.nio.file.Files.readSymbolicLink(l.toPath()).toString())
                    t.exists()
                }.getOrDefault(false)
                if (!alive && l.delete()) out.add("断链 " + l.absolutePath)
            }
        }
        return out
    }

    fun cleanParts(ctx: android.content.Context, minAgeMs: Long): List<String> {
        val out = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val kids = ctx.cacheDir.listFiles() ?: return out
        for (f in kids) {
            if (!f.isFile) continue
            val name = f.name
            if (!name.endsWith(".part") && !name.contains(".part-")) continue
            if (now - f.lastModified() < minAgeMs) continue
            if (f.delete()) out.add(f.absolutePath)
        }
        return out
    }

    private const val TMP_INFIX = ".tmp-"
}

package lobos.os

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object StateFiles {

    const val SCHEMA_KEY = "schema"
    private const val LEDGER_DIR = "os"
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
            if (!tmp.renameTo(file)) {
                tmp.delete()
                note("原子落位失败（rename 失败）: " + file.name)
                false
            } else {
                fsyncDir(dir)
                true
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            note("原子落位异常: " + file.name + " " + e::class.java.simpleName + ": " + (e.message ?: ""))
            false
        }
    }

    fun writeJson(file: File, obj: JSONObject): Boolean = try {
        if (!obj.has(SCHEMA_KEY)) obj.put(SCHEMA_KEY, 1)
        writeAtomic(file, obj.toString(2))
    } catch (e: Throwable) {
        note("JSON 落盘异常: " + file.name + " " + e::class.java.simpleName + ": " + (e.message ?: ""))
        false
    }

    private fun note(detail: String) {
        runCatching {
            val f = File(File(LEDGER_DIR, ""), FAILED_FILE)
            f.parentFile?.mkdirs()
            val prev = if (f.isFile) f.readText() else ""
            val lines = (prev + detail + "\n").split("\n").filter { it.isNotBlank() }
            val keep = lines.takeLast(50)
            writeAtomic(f, keep.joinToString("\n") + "\n")
        }
    }

    fun writeFailures(ctx: android.content.Context): List<String> =
        runCatching {
            val f = File(File(ctx.filesDir, LEDGER_DIR), FAILED_FILE)
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
            val prev = if (file.isFile) file.readText() else ""
            val next = (prev + line + "\n")
            if (next.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                writeAtomic(file, next)
                return
            }
            val lines = next.split("\n").filter { it.isNotBlank() }
            if (lines.size <= keepLines) {
                writeAtomic(file, lines.joinToString("\n") + "\n")
                return
            }
            writeAtomic(file, lines.takeLast(keepLines).joinToString("\n") + "\n")
        } catch (_: Throwable) { }
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
        sweep(File(ctx.filesDir, "os"))
        sweep(File(ctx.filesDir, "os/journal"))
        sweep(File(ctx.filesDir, "supervisor"))
        sweep(ProgramRegistry.programRoot(ctx))
        sweep(ctx.filesDir)
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

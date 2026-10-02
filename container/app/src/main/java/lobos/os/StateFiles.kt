package lobos.os

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object StateFiles {

    const val SCHEMA_KEY = "schema"

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
                false
            } else {
                fsyncDir(dir)
                true
            }
        } catch (_: Throwable) {
            try { tmp.delete() } catch (_: Throwable) {}
            false
        }
    }

    fun writeJson(file: File, obj: JSONObject): Boolean = try {
        if (!obj.has(SCHEMA_KEY)) obj.put(SCHEMA_KEY, 1)
        writeAtomic(file, obj.toString(2))
        true
    } catch (_: Throwable) {
        false
    }

    fun readJson(file: File): JSONObject? =
        runCatching { JSONObject(file.readText()) }.getOrNull()

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
        sweep(File(ctx.filesDir, "programs"))
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

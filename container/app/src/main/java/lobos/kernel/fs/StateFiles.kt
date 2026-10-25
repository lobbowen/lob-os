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
            if (!tmp.renameTo(file)) {
                tmp.delete()
                note(dir, "原子落位失败（rename 失败）: " + file.name)
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

    private fun note(dir: File, detail: String) {
        runCatching {
            val f = File(dir, FAILED_FILE)
            if (!dir.isDirectory) dir.mkdirs()
            val prev = if (f.isFile) f.readText() else ""
            val lines = (prev + detail + "\n").split("\n").filter { it.isNotBlank() }
            //  缺陷名（C-102）**该截断的那一格逐字只写不截** —— => 而「读」那一半逐字读的是**全部**、
            //    => 而「写」那一半逐字要的是**最后 50 行**，=> 而那个「截断」的**前提**是这一行**覆盖**整个文件。
            //    => 而这一行原来逐字是 FileOutputStream(f, true)（=> 而那是**追加**）、
            //    => 而**追加**的那 50 行逐字是**加在旧文件后面**的、=> 而而旧的那部分**原样留着**，
            //    => 而而那个「只留最后 50 行」**从来没有落进文件**、=> 而而文件**一直涨**
            //    （=> 而那个增长模型逐字是 N -> 2N-1 行：=> 而 07 核到设备侧那两份逐字是 163 行/9943 字节与 15 行/780 字节）。
            //    => 而处置（=> 而 lead 核过那是**外科手术式改动**那一族、=> 而按 C-220 **不是**内容稿）：
            //       把 true 去掉、=> 而改成**覆盖**、=> 而而那一格**真的落到文件上**，
            //       => 而而文件逐字从「2N-1 行」变成「**最多 50 行**」。
            //    => 而「读」那一半**不用改**（=> 而 :53 逐字读全文、=> 而而那正是截断之后要读的那一份）。
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
        sweep(SystemDirs.libvar(ctx))
        sweep(SystemDirs.log(ctx))
        sweep(SystemDirs.run(ctx))
        sweep(SystemDirs.etc(ctx))
        sweep(SystemDirs.opt(ctx))
        sweep(ctx.filesDir)
        // 共享区（usr/）下那些**派生物软链**：它们是「某一刻建出来的」，
        // 而建链那一处**只建不删**（PrefixProvisioner.provision 逐条 linkTo，
        // 而它没有「这一条不该存在」的分支）=> 而 sweep 的五个根里**没有 usr/**
        // => 所以一条指向「按形态已经不存在的那一份」的链会永远留着。
        //  真机读数（07，2026-10-21）：usr/lib/libbusybox.so 还在（mtime 10-20 01:58），
        //   而 busybox 的 files[] 19 项全是 role=program、lib/ 下 0 条
        //   => 那一条指向的路径**按形态根本不该存在**，而它是断链。
        //  而判别（04 今天那条）：清它的那一格扫的是哪棵树；
        //    而系统里可能落位的那几棵都在它范围内吗 ——  不在  有一类落位结果永远不会被清掉。
        //  现在：扫 usr/ 下那两处的**符号链接**，删掉**指向不存在目标**的那些
        //   （ 只删断链， 不碰实体文件、 也不碰指向有效目标的链）。
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

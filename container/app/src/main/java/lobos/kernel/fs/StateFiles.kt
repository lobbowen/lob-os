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
            //  缺陷名（C-102）**那一格把「换名成功」当成了「写完」的标记** —— 而实际上
            //    「写完」的标记是 tmp 的内容齐（=> 而上面这一段跑完就说明内容齐），
            //    而「换名成功」的标记是目标文件那一份变了（=> 而那逐字是 renameTo 的结果）；
            //    而这两件事逐字是**两件事**，=> 而而这一族逐字**只**把第二格记了下来
            //    （=> 而只有「rename 失败」那一支写了 note、=> 而「换名没成」那一条**没有**任何痕迹）。
            //    => 而那后果是这一族逐字分不出「写完了但没落上」与「压根没写完」。
            //    => 而 04 裁的是**在这一族内部记**（=> 而而**不要**让那 24 处调用方各接一次）。
            //    => 而按 C-105：=> 而「进程死在写与换名之间」那一格**缺字段**（=> 而那落在上面那一段
            //       与这一行之间、=> 而那**没有任何一行代码**在那儿跑、=> 而它逐字**记不住**）
            //       => 而而那**要**设备侧那一格。
            //  缺陷名（C-102）**换名那一步为什么不失败** —— 而实际上 File.renameTo 逐字只返 Boolean、
            //    => 而而它**不**带 errno、=> 而而那一族逐字**记不下**「为什么失败」那一格。
            //    => 而按 C-103 先答「它为什么有两边」：=> 而那两边是「仓内那一族**记不着的**」
            //       （=> 而那逐字是 errno、=> 而那**要**系统那一族）与「仓内那一族**记着的**」
            //       （=> 而那逐字是「写完了、而没落上」那半句）；
            //    => 而而原来那一句只记了**后者**、=> 而而**前者**逐字**没有**任何痕迹。
            //    => 而这里逐字改用 android.system.Os.rename 那一族（=> 而它是同一个 POSIX rename）、
            //       => 而而它逐字抛 ErrnoException、=> 而而那一族逐字**带** errno、=> 而而 errno 那一格**能**取到。
            var renamed = true
            var errno = 0
            try {
                Os.rename(tmp.absolutePath, file.absolutePath)
            } catch (e: android.system.ErrnoException) {
                renamed = false
                errno = e.errno
            }
            if (!renamed) {
                //  缺陷名（C-102）**那一次失败的是哪一种** —— 而实际上设备侧逐字能造出来的失败**只有一种**
                //    （=> 而那逐字是「目标是非空目录」），=> 而而另两种逐字是「**没失败**」：
                //      只读文件（chmod 400）=> 而那逐字是「换名逐字改的是**目录项**、=> 而那逐字由**那个目录**的写权限管」；
                //      悬空软链（ln -s 到不存在的目标）=> 而那逐字是「换名逐字把软链**换掉**了」。
                //    => 而而原来那一句逐字**分辨不了**这几种、=> 而而它逐字只说了「rename 失败」。
                //    => 而这里逐字把「目标那一份是什么」记下来、=> 而而它逐字是**能分辨**那几种的那一格。
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

    /**
     * 缺陷名（C-102）**为什么失败没有 errno** —— 而实际上 errno 那一格逐字是**能**取的
     * （=> 而 android.system.Os.errno 那一族逐字给的是上一次系统调用留下的 errno），
     * => 而而原来那一句只记了「写完了、而没落上」那半句、=> 而而**errno 那一格逐字没有**任何痕迹。
     * => 而按 C-103 那两边是「仓内那一族**记不着的**」与「仓内那一族**记着的**」，
     * => 而而这一族逐字把**能记的那一边**补上、=> 而而它逐字**不**替 errno 那一族说话
     * （=> 而 errno 的**含义**逐字要 errnoName 那一族逐字给出）。
     */
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
    //  缺陷名（C-102）**那追加**那一族逐字**每一**行**都**把**那**整个**的**文件**逐字**读**了**再**写**回**去**。
    //    => 而那**成因**：=> 而那 appendBounded 逐字**先**读**那**整个**的**文件**（=> 而而那**它**逐字**是**那 file.readText()）、
    //      => 而而那**它**逐字**再**把**那整个**的文件**写**回**去**（=> 而而那**它**逐字**是**那 writeAtomic、=> 而而那**它**逐字**带**那**一次** fsync）。
    //      => 而而那**它**们**逐字是**那**两**件**事**：「那**读**的是**什么**」与「那**写**的是**什么**」）。
    //    => 而而那**后果**逐字 = 而那**代价**逐字**随**那**文件**多大**逐字**涨**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**代价**是**什么**」与「那**它**随**什么**涨**」）。
    //      => 而那**探针**那一族逐字**每一**项**都**写**那**三**处、=> 而而那**它**们**逐字是**那**两**件**事**：「那**写**的是**什么**」与「那**每**一**项**的**代价**是**什么**」）。
    //    => 而那**处置**：=> 而那**它**逐字**只**在**那**文件**太大**的时候**才**去**裁**它（=> 而而那**它**们**逐字是**那**两**件**事**）。
    fun appendBounded(file: File, line: String, maxBytes: Long = 512 * 1024L, keepLines: Int = 500) {
        val dir = file.parentFile ?: return
        dir.mkdirs()
        try {
            //  => 而那**常**那一档逐字**直接**追加**（=> 而而那**它**逐字**不**读**那**整个**的**文件**、
            //      => 而而那**它**逐字**不**重写**那**整个**的**文件**、=> 而而那**它**们**逐字是**那**两**件**事**）。
            val size = if (file.isFile) file.length() else 0L
            if (size + line.toByteArray(Charsets.UTF_8).size + 1 <= maxBytes) {
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write("\n".toByteArray(Charsets.UTF_8))
                }
                return
            }
            //  => 而那**太大**的**时候**才**去**裁**它（=> 而而那**它**逐字**才**读**那**整个**的**文件**）。
            val prev = if (file.isFile) file.readText() else ""
            val next = (prev + line + "\n")
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

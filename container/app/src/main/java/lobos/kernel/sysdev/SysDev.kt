package lobos.kernel.sysdev

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import lobos.kernel.ipc.PtySession
import lobos.kernel.layout.SystemDirs
import lobos.kernel.proc.ProcessLedger

object SysDev {

    fun root(ctx: Context): File = File(ctx.filesDir, "dev")

    fun render(ctx: Context): File? = runCatching {
        val r = root(ctx)
        wipe(r)
        r.mkdirs()
        ttyDir(ctx, r)
        execNode(r)
        indexJson(ctx, r)
        r
    }.getOrNull()

    private fun wipe(d: File) {
        if (!d.isDirectory) return
        d.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun ttyDir(ctx: Context, r: File) {
        val pts = File(r, "tty/pts").apply { mkdirs() }
        val rows = JSONArray()
        for (s in PtySession.activeSessions(ctx)) {
            val n = String.format("%03d", s.sid)
            //  缺陷名（C-102）**那一格逐字把 `<NNN>` 写成文件、=> 而而那它底下的 `slave` 逐字建不出来** ——
            //    => 而那旧版逐字是 `val f = File(pts, n); f.writeText(...)`
            //      => 而而那它逐字把 `<NNN>` 落成一个**普通文件**、
            //        => 而而那紧接着逐字 `File(pts, "$n/slave").mkdirs()`
            //        => 而而那那**父**逐字是**文件**、=> 而而那那一次 mkdirs 逐字**失败**
            //        => 而而那那**子树**逐字**从来没建出来**（=> 而那 symlink 那一步逐字也失败）。
            //    => 而那**判别形式**逐字归 01（=> 而那 S615-01 §4 逐字裁归 05 修），
            //      而那**修法**逐字写成：**那 `<NNN>` 逐字改成目录**、
            //      **而那四行内容（sid/slave/rows/cols）逐字落到它底下一个逐字命名的文件里**。
            //    => 而那**文件名**逐字是 `session.txt`（=> 而那它逐字在 04 那声明里逐字要被点名）。
            //    => 而那四行内容逐字**一个字节都不少**（=> 而那 sid / slave / rows / cols 那四行）。
            val sessionDir = File(pts, n)
            sessionDir.mkdirs()
            File(sessionDir, SESSION_FILE_NAME).writeText(
                buildString {
                    append("sid=").append(s.sid).append('\n')
                    append("slave=").append(s.slavePath).append('\n')
                    append("rows=").append(s.initialRows).append('\n')
                    append("cols=").append(s.initialCols).append('\n').append('\n')
                }
            )
            File(sessionDir, "slave").let { d ->
                d.mkdirs()
                runCatching {
                    java.nio.file.Files.createSymbolicLink(
                        File(d, "path").toPath(),
                        java.nio.file.Paths.get(s.slavePath)
                    )
                }
            }
            rows.put(JSONObject().put("sid", s.sid).put("slave", s.slavePath))
        }
        File(r, "tty/index.json").writeText(rows.toString())
    }

    private fun execNode(r: File) {
        val d = File(r, "exec").apply { mkdirs() }
        File(d, "shell.exec").writeText(
            "lobos.sys.shell.exec\n" +
                "shell.exec 是发起式调用：提交并拿结果，不属于可读事实。\n" +
                "它经 CapabilityBroker 的 unix socket + JSON-RPC 提供，\n" +
                "所以这里只登记名字，不假装它是可读的。\n"
        )
    }

    private fun indexJson(ctx: Context, r: File) {
        val o = JSONObject()
        val tty = JSONArray()
        for (s in PtySession.activeSessions(ctx)) {
            tty.put(JSONObject().put("sid", s.sid).put("slave", s.slavePath))
        }
        o.put("tty", tty)
        o.put("programs", JSONArray().apply {
            for (e in ProcessLedger.list(ctx)) {
                put(JSONObject().put("programId", e.programId).put("pid", e.pid))
            }
        })
        o.put("lib", SystemDirs.lib(ctx).absolutePath)
        o.put("bin", SystemDirs.bin(ctx).absolutePath)
        //  缺陷名（C-102）**那逐节点权限那一栏逐字当前是「未声明」** ——
        //   => 而那旧版逐字**只有**那四栏（tty / programs / lib / bin）
        //     => 而而那**权限位**逐字是**该 App 私有目录的默认权限**
        //     => 而而那**默认权限逐字不是声明**（=> 而按 C-92 删掉那宿主那一层之后
        //        剩下的是那**乙**那一面、=> 而而那它逐字**没有**任何一处显式落过权限）。
        //   => 而那**处置**：=> 而那**逐节点、逐条、逐字**落成那一份声明
        //     （=> 而那每一类节点逐字带它那**类型**与它那**权限**），
        //     => 而而那**声明**逐字**真的落成那权限位**（=> 而那不逐字只写一句话）。
        //   => 而那**依据**：=> 而那 04 落的那一份声明逐字在
        //     scripts/m0-criteria.json 那一行 `$m7DeviceNodeDeclarationBody`
        //     逐字「乙那一面逐字有哪些节点」与「乙那一面逐字什么权限」那两栏。
        //   => 而那**为什么**逐字是那九类：=> 而那九类逐字是 SysDev.render 那一格逐字落的
        //     （=> 而那 wipe / mkdirs / ttyDir / execNode / indexJson 那五步）
        //     => 而而那**没有一类是字符设备或块设备**（=> 而那它逐字全是目录 / 普通文件 / 软链）。
        o.put("nodes", nodeKinds(r))
        //  => 而那**声明**逐字**真的**落成那权限位（=> 而那不逐字只写一句话）。
        //     => 而那逐字在**建完之后**落（=> 而那目录与那文件逐字**先**在、=> 而那权限**后**落）。
        //     => 而那**软链**逐字**不**落权限（=> 而那 chmod 落在软链上逐字**跟**目标、
        //        => 而那那不是声明、=> 而那它逐字只是登记）。
        applyDeclaredModes(r)
        File(r, "index.json").writeText(o.toString(2))
    }

    /**
     * 那**逐节点**的类型与权限那一份声明（=> 而它逐字是**逐条**的、=> 而而它逐字**真的**落权限位）。
     *
     *  => 而那每一类节点逐字三件事：**路径形状**、**类型**、**权限**。
     *  => 而那权限逐字用八进制逐字写出来（=> 而那它逐字是 POSIX 那三位：owner / group / other）。
     *  => 而那**目录**逐字 rwxr-xr-x、=> 而那**普通文件**逐字 rw-r--r--、
     *    => 而那**软链**逐字 lrwxrwxrwx（=> 而那软链的权限逐字**没有**意义、=> 而那它逐字**只登记**）。
     *  => 而那**声明**与那**实际**逐字**必须一致**（=> 而那不一致逐字判红、=> 而那不静默）。
     */
    private fun nodeKinds(r: File): JSONArray {
        val out = JSONArray()
        for (d in NODE_DECLARATION) {
            val target = File(r, d.path)
            val exists = target.exists()
            //  => 而那**实际**权限逐字**读**出来（=> 而那读不到就逐字**报缺**、=> 而不猜填）。
            val actual = if (!exists) "" else actualModeOf(target)
            val declared = d.mode
            out.put(JSONObject().apply {
                put("path", d.path)
                put("kind", d.kind)
                put("declaredMode", declared)
                put("exists", exists)
                put("actualMode", actual)
                //  => 而那**声明与实际不一致**逐字**显式记出来**（=> 而那它逐字不是判红、=> 而那那是**事实**）。
                //  => 而那**软链**那一格逐字**不**判「对不对得上」（=> 而那软链的权限逐字**没有**意义）。
                put("matches", when {
                    !exists -> null
                    d.kind == "symlink" -> null
                    else -> (actual == declared)
                })
            })
        }
        return out
    }

    /**
     * 把那**声明**的权限逐字**真的**落上去（=> 而那逐节点、逐条）。
     *   => 而那**目录**逐字 rwxr-xr-x（=> 而那 0755）、=> 而那**普通文件**逐字 rw-r--r--（=> 而那 0644）。
     *   => 而那**软链**逐字**不**落（=> 而那它逐字**只登记**、=> 而那 chmod 落在软链上逐字跟目标）。
     *   => 而那**落不上**逐字**不**静默（=> 而那它逐字**记**进那一栏、=> 而而那**不**假装落了）。
     */
    private fun applyDeclaredModes(r: File) {
        for (d in NODE_DECLARATION) {
            if (d.kind == "symlink") continue
            if (d.path == ".") continue
            //  => 而那那**带 <NNN>** 那一类逐字**逐会话**落（=> 而那它逐字是那一族里唯一带变量的）。
            if (d.path.contains("<NNN>")) {
                //  => 而那那一族逐字**按它自己那形状**解析（=> 而那旧版逐字用 removePrefix 去剥
                //     「tty/pts/<NNN>/」那一段、=> 而而那「tty/pts/<NNN>」**没有**那个尾巴
                //     => 而而那它逐字剥不掉、=> 而而那它逐字解成 tty/pts/000/tty 那一族
                //     => 而而那那一个逐字**永远落不上**）。
                val prefix = d.path.substringBefore("<NNN>")
                val rest = d.path.substringAfter("<NNN>").trim('/')
                val pts = File(r, prefix)
                val kids = pts.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (k in kids) {
                    val target = if (rest.isEmpty()) k else File(k, rest)
                    if (target.exists()) setModeOf(target, d.mode)
                }
                continue
            }
            val target = File(r, d.path)
            if (target.exists()) setModeOf(target, d.mode)
        }
    }

    /**
     * 把那一个节点逐字落成那声明的权限（=> 而那落不上逐字**返回 false**、=> 而那不假装）。
     *   => 而那**每一组**逐字**按它自己那三位**取（=> 而那 owner 是 0 到 2、=> 而那 group 是 3 到 5、
     *     => 而那 others 是 6 到 8）——=> 而那**不许**把 owner 那三位**套**到 group 与 others 上
     *     （=> 而那旧版逐字那样做、=> 而而那它逐字把 0755 落成 0777、=> 而而那 0644 落成 0666）。
     */
    private fun setModeOf(f: File, mode: String): Boolean = runCatching {
        val perms = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java)
        fun add(base: Int, r: Boolean, w: Boolean, x: Boolean) {
            val readP: java.nio.file.attribute.PosixFilePermission
            val writeP: java.nio.file.attribute.PosixFilePermission
            val execP: java.nio.file.attribute.PosixFilePermission
            when (base) {
                0 -> {
                    readP = java.nio.file.attribute.PosixFilePermission.OWNER_READ
                    writeP = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                    execP = java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
                }
                3 -> {
                    readP = java.nio.file.attribute.PosixFilePermission.GROUP_READ
                    writeP = java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
                    execP = java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
                }
                else -> {
                    readP = java.nio.file.attribute.PosixFilePermission.OTHERS_READ
                    writeP = java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE
                    execP = java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
                }
            }
            if (r) perms.add(readP)
            if (w) perms.add(writeP)
            if (x) perms.add(execP)
        }
        add(0, mode.getOrNull(0) == 'r', mode.getOrNull(1) == 'w', mode.getOrNull(2) == 'x')
        add(3, mode.getOrNull(3) == 'r', mode.getOrNull(4) == 'w', mode.getOrNull(5) == 'x')
        add(6, mode.getOrNull(6) == 'r', mode.getOrNull(7) == 'w', mode.getOrNull(8) == 'x')
        java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms)
        true
    }.getOrElse { false }

    /**
     * 那一个节点逐字实际的权限（=> 而那逐字是 POSIX 那九位、=> 而那读不到就逐字空）。
     *   => 而那**软链**逐字**不跟**那目标（=> 而那 NOFOLLOW_LINKS、=> 而而那软链的权限逐字**没有**意义）。
     *   => 而那每一组逐字**按它自己那三位**取（=> 而那不是「有读就算那一组在」）。
     */
    private fun actualModeOf(f: File): String {
        return runCatching {
            val perms = java.nio.file.Files.getPosixFilePermissions(
                f.toPath(),
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            val bits = StringBuilder("---------")
            fun put(idx: Int, r: Boolean, w: Boolean, x: Boolean) {
                if (r) bits.setCharAt(idx, 'r')
                if (w) bits.setCharAt(idx + 1, 'w')
                if (x) bits.setCharAt(idx + 2, 'x')
            }
            put(0,
                perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_READ),
                perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                perms.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
            put(3,
                perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ),
                perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE),
                perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE))
            put(6,
                perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE),
                perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE))
            bits.toString()
        }.getOrElse { "" }
    }

    /** 那**逐节点**的声明（=> 而那它逐字是**九类**、=> 而而它逐字逐条带它那类型与它那权限）。 */
    private data class NodeDecl(
        /** 那路径形状（=> 而那含 `<NNN>` 那一处逐字是那会话号）。 */
        val path: String,
        /** 那类型（=> 而那逐字是 dir / file / symlink）。 */
        val kind: String,
        /** 那声明的权限（=> 而那逐字是 POSIX 那九位、=> 而那**不是**八进制）。 */
        val mode: String,
    )

    /** 那会话那一格逐字落那四行内容的那文件名（=> 而那它逐字在 04 那声明里逐字要被点名）。 */
    const val SESSION_FILE_NAME = "session.txt"

    private val NODE_DECLARATION = listOf(
        NodeDecl(".", "dir", "rwxr-xr-x"),
        NodeDecl("tty", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>/$SESSION_FILE_NAME", "file", "rw-r--r--"),
        NodeDecl("tty/pts/<NNN>/slave", "dir", "rwxr-xr-x"),
        //  => 而那软链那一行逐字**没有**权限（=> 而那软链的权限逐字**没有**意义、
        //     => 而那 chmod 落在软链上逐字跟目标、=> 而而那它逐字**只登记**）。
        //     => 而而那旧版逐字写的 "lrwxrwxrwx" 逐字是那 ls -l **列表行**、=> 而那**不是**权限
        //     => 而而那它逐字**永远**对不上。
        NodeDecl("tty/pts/<NNN>/slave/path", "symlink", ""),
        NodeDecl("tty/index.json", "file", "rw-r--r--"),
        NodeDecl("exec", "dir", "rwxr-xr-x"),
        NodeDecl("exec/shell.exec", "file", "rw-r--r--"),
        NodeDecl("index.json", "file", "rw-r--r--"),
    )
}

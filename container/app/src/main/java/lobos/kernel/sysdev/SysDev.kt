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

    fun render(ctx: Context): File? {
        val root = root(ctx)
        currentRoot = root

        val current = verifyNodes(root)

        val sessionsNow = sessionSignature(ctx)
        val lastSig = lastRenderedSessionSignature
        if (current == null && root.isDirectory && sessionsNow == lastSig) {

            return root
        }
        val r = runCatching {
            wipe(root)
            root.mkdirs()
            ttyDir(ctx, root)
            execNode(root)
            indexJson(ctx, root)
            applyDeclaredModes(root)
            indexJson(ctx, root)
            root
        }.getOrNull() ?: return null
        lastRenderedSessionSignature = sessionsNow

        val bad = verifyNodes(r)
        if (bad != null) {
            val (path, fault) = bad
            val diag = "节点不合声明：" + path + " —— " + faultName(fault) +
                "（它逐字**不**只报「失败」、它逐字报出那一类是哪一类）"
            System.err.println(diag)
            runCatching {
                lobos.services.log.RuntimeDiagnostics.append(ctx, "sysdev", false, diag, "")
            }

            return null
        }
        return r
    }

    private fun wipe(d: File) {
        if (!d.isDirectory) return
        d.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun ttyDir(ctx: Context, r: File) {
        val pts = File(r, "tty/pts").apply { mkdirs() }
        val rows = JSONArray()
        for (s in PtySession.activeSessions(ctx)) {
            val n = String.format("%03d", s.sid)

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
        val d = File(r, "exec")

        if (!d.isDirectory && !d.mkdirs()) {
            throw IllegalStateException(
                "建不了那 exec 目录：" + d.absolutePath +
                    "（它逐字**不成**、按 C-05 报缺、**不**往下写）"
            )
        }

        val f = File(d, "shell.exec")
        if (f.isDirectory) {
            throw IllegalStateException(
                "那 shell.exec 逐字已经是一个目录：" + f.absolutePath +
                    "（是那「类型错」那一类、按 C-05 报缺）"
            )
        }
        f.writeText(
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

        o.put("nodes", nodeKinds(r, "index.json"))

        o.put("instances", instanceCounts(r))

        File(r, "index.json").writeText(o.toString(2))
    }

    private fun nodeKinds(r: File, selfPath: String): JSONArray {
        val out = JSONArray()
        for (d in NODE_DECLARATION) {

            if (d.path.contains("<NNN>")) {
                val prefix = d.path.substringBefore("<NNN>")
                val rest = d.path.substringAfter("<NNN>").trim('/')
                val pts = File(r, prefix)
                val kids = pts.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (k in kids) {
                    val target = if (rest.isEmpty()) k else File(k, rest)
                    val existsI = target.exists()
                    val actualI = if (!existsI) "" else actualModeOf(target)
                    out.put(JSONObject().apply {
                        put("path", d.path)

                        put("instance", k.name)
                        put("kind", d.kind)
                        put("declaredMode", d.mode)
                        put("self", false)
                        put("exists", existsI)
                        put("actualMode", actualI)
                        put("matches", when {
                            !existsI -> null
                            d.kind == "symlink" -> null
                            else -> (actualI == d.mode)
                        })
                    })
                }
                continue
            }
            val target = File(r, d.path)
            val exists = target.exists()

            val isSelf = d.path == selfPath

            val actual = if (!exists) "" else actualModeOf(target)
            val declared = d.mode
            out.put(JSONObject().apply {
                put("path", d.path)
                put("kind", d.kind)
                put("declaredMode", declared)

                put("self", isSelf)
                put("exists", if (isSelf) true else exists)
                put("actualMode", actual)

                put("matches", when {
                    isSelf -> null
                    !exists -> null
                    d.kind == "symlink" -> null
                    else -> (actual == declared)
                })
            })
        }
        return out
    }

    enum class NodeFault {

        MISSING,

        WRONG_TYPE,

        TOO_WIDE,
    }

    fun faultName(f: NodeFault): String = when (f) {
        NodeFault.MISSING -> "缺节点"
        NodeFault.WRONG_TYPE -> "类型错"
        NodeFault.TOO_WIDE -> "权限过宽"
    }

    data class Fault(val path: String, val fault: NodeFault, val instance: String? = null)

    fun verifyAllFaults(r: File): List<Fault> {
        val out = mutableListOf<Fault>()
        for (d in NODE_DECLARATION) {
            if (d.path == ".") {
                val actualKind = if (r.isDirectory) "dir" else "other"
                if (d.kind != actualKind) {
                    out.add(Fault(d.path, NodeFault.WRONG_TYPE))
                } else {
                    val m = actualModeOf(r)
                    if (m.isNotBlank() && d.mode.isNotBlank() && isWiderThan(m, d.mode)) {
                        out.add(Fault(d.path, NodeFault.TOO_WIDE))
                    }
                }
                continue
            }
            if (d.path.contains("<NNN>")) {
                val prefix = d.path.substringBefore("<NNN>")
                val rest = d.path.substringAfter("<NNN>").trim('/')
                val pts = File(r, prefix)
                val kids = pts.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (k in kids) {
                    val target = if (rest.isEmpty()) k else File(k, rest)
                    checkOneAll(d, target, k.name)?.let { out.add(it) }
                }
                continue
            }
            val target = File(r, d.path)
            checkOneAll(d, target, null)?.let { out.add(it) }
        }
        return out
    }

    private fun checkOneAll(d: NodeDecl, target: File, instance: String?): Fault? {
        if (!target.exists()) return Fault(d.path, NodeFault.MISSING, instance)
        if (d.kind == "symlink") {
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
                return Fault(d.path, NodeFault.WRONG_TYPE, instance)
            }
            return null
        }
        val wantDir = d.kind == "dir"
        if (target.isDirectory != wantDir) return Fault(d.path, NodeFault.WRONG_TYPE, instance)
        val actual = actualModeOf(target)
        if (actual.isBlank()) return null
        if (d.mode.isNotBlank() && isWiderThan(actual, d.mode)) {
            return Fault(d.path, NodeFault.TOO_WIDE, instance)
        }
        return null
    }

    fun verifyNodes(r: File): Pair<String, NodeFault>? {
        for (d in NODE_DECLARATION) {
            if (d.path == ".") {

                val actualKind = if (r.isDirectory) "dir" else "other"
                if (d.kind != actualKind) return d.path to NodeFault.WRONG_TYPE
                val m = actualModeOf(r)
                if (m.isNotBlank() && d.mode.isNotBlank() && isWiderThan(m, d.mode)) {
                    return d.path to NodeFault.TOO_WIDE
                }
                continue
            }
            if (d.path.contains("<NNN>")) {

                val prefix = d.path.substringBefore("<NNN>")
                val rest = d.path.substringAfter("<NNN>").trim('/')
                val pts = File(r, prefix)
                val kids = pts.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (k in kids) {
                    val target = if (rest.isEmpty()) k else File(k, rest)
                    val f = checkOne(d, target) ?: continue
                    return f
                }
                continue
            }
            val target = File(r, d.path)
            val f = checkOne(d, target)
            if (f != null) return f
        }
        return null
    }

    private fun checkOne(d: NodeDecl, target: File): Pair<String, NodeFault>? {

        if (!target.exists()) return d.path to NodeFault.MISSING
        if (d.kind == "symlink") {

            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) return d.path to NodeFault.WRONG_TYPE
            return null
        }

        val wantDir = d.kind == "dir"
        if (target.isDirectory != wantDir) return d.path to NodeFault.WRONG_TYPE

        val actual = actualModeOf(target)
        if (actual.isBlank()) {

            System.err.println(
                "那实际权限逐字读不到：" + d.path +
                    "（**读不到**逐字**不**等于那**合声明**、按 C-05 报缺）"
            )
            return null
        }
        if (d.mode.isNotBlank() && isWiderThan(actual, d.mode)) {

            return d.path to NodeFault.TOO_WIDE
        }
        return null
    }

    fun modeDiffOf(path: String): String {
        val r = rootOfPath(path) ?: return "（那实际权限逐字读不到）"
        val actual = actualModeOf(r)
        val declared = NODE_DECLARATION.firstOrNull { it.path == path }?.mode.orEmpty()
        return modeDiff(actual, declared)
    }

    private fun rootOfPath(path: String): File? {
        val root = currentRoot ?: return null
        return if (path == ".") root else File(root, path)
    }

    fun modeDiff(actual: String, declared: String): String {
        if (actual.isBlank()) return "（那实际权限逐字读不到）"
        if (declared.isBlank()) return "（那声明权限逐字没有）"
        if (actual.length < 9 || declared.length < 9) return "（那权限逐字形状不对）"
        val names = arrayOf("owner", "group", "others")
        val out = mutableListOf<String>()
        for (g in 0..2) {
            for (b in 0..2) {
                val ai = g * 3 + b
                val have = actual[ai] != '-'
                val want = declared[ai] != '-'
                if (have != want) {
                    val bit = arrayOf("r", "w", "x")[b]
                    out += names[g] + "/" + bit +
                        "（实际=" + (if (have) bit else "-") +
                        " 声明=" + (if (want) bit else "-") + "）"
                }
            }
        }
        return if (out.isEmpty()) "（那权限逐字**一样**）" else out.joinToString("、")
    }

    private fun isWiderThan(actual: String, declared: String): Boolean {
        if (actual.length < 9 || declared.length < 9) return false
        for (g in 0..2) {
            for (b in 0..2) {
                val ai = g * 3 + b
                val have = actual[ai] != '-'
                val want = declared[ai] != '-'
                if (have && !want) return true
            }
        }
        return false
    }

    private fun sessionSignature(ctx: Context): String =
        runCatching {
            PtySession.activeSessions(ctx).joinToString(";") { "${it.sid}:${it.slavePath}" }
        }.getOrDefault("")

    @Volatile private var lastRenderedSessionSignature: String? = null

    private fun instanceCounts(r: File): JSONObject {
        val o = JSONObject()

        val sessions = File(r, "tty/pts").listFiles()
            ?.filter { it.isDirectory }
            ?.size ?: 0
        o.put("tty/pts/<NNN>", sessions)
        for (d in NODE_DECLARATION) {
            if (d.path == "tty/pts/<NNN>") continue

            o.put(d.path, 1)
        }
        return o
    }

    private fun applyDeclaredModes(r: File) {
        for (d in NODE_DECLARATION) {
            if (d.kind == "symlink") continue

            if (d.path == ".") {
                if (!setModeOf(r, d.mode)) modeWriteFailed(d.path)
                continue
            }

            if (d.path.contains("<NNN>")) {

                val prefix = d.path.substringBefore("<NNN>")
                val rest = d.path.substringAfter("<NNN>").trim('/')
                val pts = File(r, prefix)
                val kids = pts.listFiles()?.sortedBy { it.name } ?: emptyList()
                for (k in kids) {
                    val target = if (rest.isEmpty()) k else File(k, rest)
                    if (target.exists() && !setModeOf(target, d.mode)) modeWriteFailed(d.path)
                }
                continue
            }
            val target = File(r, d.path)
            if (target.exists() && !setModeOf(target, d.mode)) modeWriteFailed(d.path)
        }
    }

    private fun modeWriteFailed(path: String) {
        System.err.println(
            "那权限逐字写不上去：" + path +
                "（**盘上**逐字**还是**那**原来**的那一档、按 C-05 报缺）"
        )
    }

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

    @Volatile private var currentRoot: File? = null

    private data class NodeDecl(

        val path: String,

        val kind: String,

        val mode: String,
    )

    const val SESSION_FILE_NAME = "session.txt"

    private val NODE_DECLARATION = listOf(
        NodeDecl(".", "dir", "rwxr-xr-x"),
        NodeDecl("tty", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>/$SESSION_FILE_NAME", "file", "rw-r--r--"),
        NodeDecl("tty/pts/<NNN>/slave", "dir", "rwxr-xr-x"),

        NodeDecl("tty/pts/<NNN>/slave/path", "symlink", ""),
        NodeDecl("tty/index.json", "file", "rw-r--r--"),
        NodeDecl("exec", "dir", "rwxr-xr-x"),
        NodeDecl("exec/shell.exec", "file", "rw-r--r--"),
        NodeDecl("index.json", "file", "rw-r--r--"),
    )
}

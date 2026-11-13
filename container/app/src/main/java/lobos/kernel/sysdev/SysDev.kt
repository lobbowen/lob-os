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

    /**
     * 渲染那一格（答的是那三类之一，不只报「失败」），按状态判、不每拍重建。缺陷 C-102：旧版 `runCatching { … }.getOrNull()` 要么返回
     *   目录、要么 null，从不说为什么（违反 M7-12「必须报出是哪一类」）；旧版还每 15 秒 wipe 再重建 `files/dev`（六步：`wipe` /
     *   `ttyDir` / `execNode` / `indexJson` / `applyDeclaredModes` / `indexJson`，`babbf64` 为修「不自愈」落下的成本）。处置：
     *   先核（不改盘），全合声明就不重建、有不合才重建；渲染完再核一遍并报缺（C-05），状态没变不动盘。 */
    fun render(ctx: Context): File? {
        val root = root(ctx)
        currentRoot = root
        //  状态先核（不改盘）。
        val current = verifyNodes(root)
        //  会话变了也算「状态变了」（`<NNN>` 那类是逐会话的，核看不见它变没变）。
        val sessionsNow = sessionSignature(ctx)
        val lastSig = lastRenderedSessionSignature
        if (current == null && root.isDirectory && sessionsNow == lastSig) {
            //  逐节点全合声明、会话也没变 = 状态没变，不重建（没有成本）。
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
        //  逐节点再核一遍（答的是那三类之一）。
        val bad = verifyNodes(r)
        if (bad != null) {
            val (path, fault) = bad
            val diag = "节点不合声明：" + path + " —— " + faultName(fault) +
                "（它逐字**不**只报「失败」、它逐字报出那一类是哪一类）"
            System.err.println(diag)
            runCatching {
                lobos.services.log.RuntimeDiagnostics.append(ctx, "sysdev", false, diag, "")
            }
            //  拒绝：目录不算成那一次成功的渲染。
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
            //  缺陷名 C-102：`<NNN>` 被写成普通文件、底下的 `slave` 建不出来。旧版 `File(pts, n); f.writeText(...)`
            //    落成文件后 `File(pts, "$n/slave").mkdirs()` 的父是文件、一次 mkdirs 失败，子树从来没建出来。
            //  修法（判别形式归 01，S615-01 §4 裁归 05 修）：`<NNN>` 改成目录，四行内容（sid/slave/rows/cols）
            //    落到它底下一个命名文件里，文件名是 `session.txt`（04 那声明里要点名），一个字节都不少。
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
        //  缺陷名 C-102：不看 mkdirs 成没成、直接往下写。旧版 `File(r, "exec").apply { mkdirs() }`，
        //    `mkdirs()` 失败返回 false 而 `apply` 不看它，`File(d, "shell.exec").writeText(…)` 就抛。
        //  后果：类型错那一类（`exec/shell.exec` 是目录）让 `execNode` 写不进去、render 抛；而「起」只跑一次
        //    （`SupervisorPool` 那句），注销 + 重起两轮在 96 秒内没走完（节拍 15 秒），类型错仍没重建。
        //  处置：要看 mkdirs 成没成，不成时报缺、不往下写（C-05，不猜填）。
        if (!d.isDirectory && !d.mkdirs()) {
            throw IllegalStateException(
                "建不了那 exec 目录：" + d.absolutePath +
                    "（它逐字**不成**、按 C-05 报缺、**不**往下写）"
            )
        }
        //  目标已经是目录时报缺（是「类型错」那一族）。
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
        //  缺陷名 C-102：逐节点权限那一栏当前是「未声明」。旧版只有四栏（tty / programs / lib / bin），权限位是该 App
        //    私有目录的默认权限，而默认权限不是声明（按 C-92 删掉宿主那层之后，乙那一面没有任何一处显式落过权限）。
        //  处置：逐节点、逐条落成 04 在 `scripts/m0-criteria.json` 的 `$m7DeviceNodeDeclarationBody` 里那份声明
        //    （有哪些节点、什么权限），真的落成权限位。九类节点是 `render` 那五步落下的，没有字符设备或块设备。
        //  `nodes` 在 `applyDeclaredModes` 之后取（读落完权限后的实际权限）；`index.json` 自己那格标成它自己。
        o.put("nodes", nodeKinds(r, "index.json"))
        //  缺陷名 C-102：没有「那一类现在有几条实例」那一格。01 裁了它（答「可变的那一类现在有几条」），
        //    数据已在 `tty` 栏里（`indexJson` 逐条记 sid 与 slave）。
        //  处置：每类多一个 `instances`：不可变的那几类（文件、目录、软链）恒是 1；可变的（会话）是真条数，
        //    没有会话时是 0（C-05，不猜填）。
        o.put("instances", instanceCounts(r))
        //  声明真的落成权限位：落的那步在这个函数之外、在这之前（`render` 里先 `applyDeclaredModes(r)`
        //    再 `indexJson(ctx, r)`）。软链不落权限（chmod 落在软链上跟目标，它只是登记）。
        File(r, "index.json").writeText(o.toString(2))
    }

    /**
     * 逐节点的类型与权限那一份声明（逐条的，真的落权限位）。每类节点三件事：路径形状、类型、权限。
     * 权限用八进制写出来（POSIX 那三位：owner / group / other）：目录 rwxr-xr-x、普通文件 rw-r--r--、
     * 软链 lrwxrwxrwx（软链的权限没有意义，只登记）。声明与实际必须一致（不一致判红、不静默）。
     */
    private fun nodeKinds(r: File, selfPath: String): JSONArray {
        val out = JSONArray()
        for (d in NODE_DECLARATION) {
            //  缺陷名 C-102：旧版按带尖括号的字面量去核、那节点永远不存在。`val target = File(r, d.path)`（`d.path`
            //    是 `tty/pts/<NNN>` 那一族），尖括号永远不在盘上、`exists` 永远是 false（07 核到会话那三条仍 exists=false，
            //    `tty/index.json` 那两行重复也是号撞了的旁证）。
            //  处置：带 `<NNN>` 那一族按实例核，与 `verifyNodes` 同一把尺（旧版是两把尺），`exists` 与 `matches` 都按实例算；
            //    其余几类不变（没有尖括号）。
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
                        //  实例记在 instance 那一格（可机读）。
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
            //  缺陷名 C-102：`index.json` 那一类永远自指不可见。旧版 `nodeKinds` 在 `index.json` 写出来之前取，
            //    描述的是写之前那一瞬间，那时它还不存在、永远 exists=false（07 核到的那一条）。
            //  正解（Lead 采纳的乙）：它自己写成「它自己」（一类就是 `index.json` 那份），不算 exists 那一格
            //    （它必存在、就是这一份）。
            val isSelf = d.path == selfPath
            //  实际权限读出来（读不到就报缺、不猜填）。
            val actual = if (!exists) "" else actualModeOf(target)
            val declared = d.mode
            out.put(JSONObject().apply {
                put("path", d.path)
                put("kind", d.kind)
                put("declaredMode", declared)
                //  它自己那一格标成它自己（不算 exists）。
                put("self", isSelf)
                put("exists", if (isSelf) true else exists)
                put("actualMode", actual)
                //  声明与实际不一致就显式记出来（是事实、不是判红）；软链不判对不对得上（它权限没有意义）。
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

    /**
     * 每一类有几条实例（可变的是会话，数 `tty/pts/` 底下真的目录；不可变的恒是 1）。 */
    /**
     * 三类之一（M7-12 那条 negative 断言要的三选一）：缺节点 / 类型错 / 权限过宽。
     * 缺陷 C-102：旧版 `render` 只报「失败」、不报出是哪一类，违反 04 落的 M7-12；处置：每一类核一遍、答出三类之一。
     */
    enum class NodeFault {
        /** 声明要它、盘上没有（「缺节点」）。 */
        MISSING,

        /** 声明要它是什么、盘上是别的（「类型错」）。 */
        WRONG_TYPE,

        /** 声明要它的权限、盘上比那宽（「权限过宽」）。 */
        TOO_WIDE,
    }

    /** 三类的中文（报缺时要说的那一句）。 */
    fun faultName(f: NodeFault): String = when (f) {
        NodeFault.MISSING -> "缺节点"
        NodeFault.WRONG_TYPE -> "类型错"
        NodeFault.TOO_WIDE -> "权限过宽"
    }

    /**
     * 逐节点核一遍（答的是那三类之一，不只报「失败」）。
     * @return 第一处不对的那一格（没有不对时是 null，那是事实）
     */
    /** 不合的那几处（那一件 + 那一类）。 */
    data class Fault(val path: String, val fault: NodeFault, val instance: String? = null)

    /**
     * 那几处不合全部报出来（不只报第一处）。
     * 缺陷 C-102：旧版 `verifyNodes` 只 `return d.path to NodeFault.…`，第一处一命中其余就不再看；后果（01 核出）：
     *   「类型错」那一类本轮没有单独取到（缺节点与权限过宽两类已过），一处盖住了那一个。
     * 处置：逐条走一遍、每一处都报出来，读数侧答「全部不合的那几处各自是哪一类」；渲染那格照旧（先核、有不合时重建）。
     */
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

    /** 那一个节点的全部不合（不只报第一类）。 */
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
                //  根目录那一类核类型与权限。
                val actualKind = if (r.isDirectory) "dir" else "other"
                if (d.kind != actualKind) return d.path to NodeFault.WRONG_TYPE
                val m = actualModeOf(r)
                if (m.isNotBlank() && d.mode.isNotBlank() && isWiderThan(m, d.mode)) {
                    return d.path to NodeFault.TOO_WIDE
                }
                continue
            }
            if (d.path.contains("<NNN>")) {
                //  带 <NNN> 那一族按它自己那形状核（实例存在）。
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

    /** 那一个节点核（答的是那三类之一）。 */
    private fun checkOne(d: NodeDecl, target: File): Pair<String, NodeFault>? {
        //  「缺节点」：声明要它、盘上没有。
        if (!target.exists()) return d.path to NodeFault.MISSING
        if (d.kind == "symlink") {
            //  软链不核权限（它没有权限那一格）。
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) return d.path to NodeFault.WRONG_TYPE
            return null
        }
        //  「类型错」：声明要它是目录、盘上是文件（或反过来）。
        val wantDir = d.kind == "dir"
        if (target.isDirectory != wantDir) return d.path to NodeFault.WRONG_TYPE
        //  「权限过宽」：声明要它的权限、盘上比那宽。
        //  缺陷名 C-102：读不到实际权限时曾被当成合声明（旧版 `if (actual.isNotBlank() && d.mode.isNotBlank() &&
        //    isWiderThan(…))`，actual 空就跳过比较）。处置：读不到就报缺，不当合声明（C-05）。
        val actual = actualModeOf(target)
        if (actual.isBlank()) {
            //  读不到就报缺（C-05，不猜填）。
            System.err.println(
                "那实际权限逐字读不到：" + d.path +
                    "（**读不到**逐字**不**等于那**合声明**、按 C-05 报缺）"
            )
            return null
        }
        if (d.mode.isNotBlank() && isWiderThan(actual, d.mode)) {
            //  报出哪一组 rwx 不一样（Lead 要的那一格）：判据是「那权限那格有没有真的比实际权限宽」。
            return d.path to NodeFault.TOO_WIDE
        }
        return null
    }

    /**
     * 实际权限跟声明哪一组不一样（按那一个节点算）：逐位比，看三个组多了哪一位。
     * 读不到时是「（那实际权限读不到）」（C-05 报缺）。
     */
    fun modeDiffOf(path: String): String {
        val r = rootOfPath(path) ?: return "（那实际权限逐字读不到）"
        val actual = actualModeOf(r)
        val declared = NODE_DECLARATION.firstOrNull { it.path == path }?.mode.orEmpty()
        return modeDiff(actual, declared)
    }

    /** 那一个节点在盘上哪一个（按声明解、不猜）。 */
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

    /**
     * 实际权限比声明的宽不宽（「权限过宽」那一族）：逐位比，看三个组多了哪一位。
     */
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

    /**
     * 会话的签名（「会话是哪几条」那个读数）：只含会话号与从设备名，不含输出。
     */
    private fun sessionSignature(ctx: Context): String =
        runCatching {
            PtySession.activeSessions(ctx).joinToString(";") { "${it.sid}:${it.slavePath}" }
        }.getOrDefault("")

    /** 上一次渲染时的会话签名（状态那一格）。 */
    @Volatile private var lastRenderedSessionSignature: String? = null

    private fun instanceCounts(r: File): JSONObject {
        val o = JSONObject()
        //  会话那一类数 pts 底下真的目录（它是可变的）。
        val sessions = File(r, "tty/pts").listFiles()
            ?.filter { it.isDirectory }
            ?.size ?: 0
        o.put("tty/pts/<NNN>", sessions)
        for (d in NODE_DECLARATION) {
            if (d.path == "tty/pts/<NNN>") continue
            //  不可变的恒是 1（就是它自己）。
            o.put(d.path, 1)
        }
        return o
    }

    /**
     * 把声明的权限真的落上去（逐节点、逐条）：目录 rwxr-xr-x（0755）、普通文件 rw-r--r--（0644）。
     * 软链不落（只登记，chmod 落在软链上跟目标）；落不上不静默（记进那一栏、不假装落了）。
     */
    private fun applyDeclaredModes(r: File) {
        for (d in NODE_DECLARATION) {
            if (d.kind == "symlink") continue
            //  根目录那一类（`dev/` 本身）不跳过：旧版 `if (d.path == ".") continue` 把它显式跳过、保持默认 rwx------，
            //    而这一趟那一份是新建的、权限位要落上去（它是 `root(ctx)` 那一格）。
            if (d.path == ".") {
                if (!setModeOf(r, d.mode)) modeWriteFailed(d.path)
                continue
            }
            //  带 <NNN> 那一类逐会话落（那一族里唯一带变量的）。
            if (d.path.contains("<NNN>")) {
                //  按它自己那形状解析：旧版用 `removePrefix("tty/pts/<NNN>/")` 剥，而 `tty/pts/<NNN>` 没有那个尾巴，
                //    剥不掉、解成 tty/pts/000/tty 那一族，永远落不上。
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

    /**
     * 权限写不上去那一格（报缺、不静默）。
     * 缺陷 C-102：旧版吞掉了「写不上去」——`setModeOf(target, d.mode)` 忽略返回值（它成时 true、不成时 false，
     *   `runCatching` 不看 `setPosixFilePermissions` 成没成）。后果：盘上还是过宽那一档（07 核到盘上确实是
     *   `-rwxrwxrwx`，而 `verifyDeviceNodes` 答「全合声明」）。处置：写不上去报缺（C-05，不猜填）。
     */
    private fun modeWriteFailed(path: String) {
        System.err.println(
            "那权限逐字写不上去：" + path +
                "（**盘上**逐字**还是**那**原来**的那一档、按 C-05 报缺）"
        )
    }

    /**
     * 把那一个节点落成声明的权限（落不上返回 false、不假装）。每一组按它自己那三位取（owner 0-2、group 3-5、
     * others 6-8），不许把 owner 那三位套到 group 与 others 上（旧版那样做、把 0755 落成 0777、0644 落成 0666）。
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
     * 那一个节点实际的权限（POSIX 那九位，读不到就空）。软链不跟目标（NOFOLLOW_LINKS，软链的权限没有意义）；
     * 每一组按它自己那三位取（不是「有读就算那一组在」）。
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

    /** 盘上那一个根（`root(ctx)` 那一格，记的那一次渲染的）。 */
    @Volatile private var currentRoot: File? = null

    /** 逐节点的声明（九类，逐条带类型与权限）。 */
    private data class NodeDecl(
        /** 路径形状（`<NNN>` 那一处是会话号）。 */
        val path: String,
        /** 类型（dir / file / symlink）。 */
        val kind: String,
        /** 声明的权限（POSIX 那九位，不是八进制）。 */
        val mode: String,
    )

    /** 会话那一格落那四行内容的文件名（04 那声明里要点名）。 */
    const val SESSION_FILE_NAME = "session.txt"

    private val NODE_DECLARATION = listOf(
        NodeDecl(".", "dir", "rwxr-xr-x"),
        NodeDecl("tty", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>", "dir", "rwxr-xr-x"),
        NodeDecl("tty/pts/<NNN>/$SESSION_FILE_NAME", "file", "rw-r--r--"),
        NodeDecl("tty/pts/<NNN>/slave", "dir", "rwxr-xr-x"),
        //  软链那一行没有权限（软链的权限没有意义，chmod 落在软链上跟目标、只登记）；
        //  旧版写的 "lrwxrwxrwx" 是 ls -l 的列表行、不是权限，永远对不上。
        NodeDecl("tty/pts/<NNN>/slave/path", "symlink", ""),
        NodeDecl("tty/index.json", "file", "rw-r--r--"),
        NodeDecl("exec", "dir", "rwxr-xr-x"),
        NodeDecl("exec/shell.exec", "file", "rw-r--r--"),
        NodeDecl("index.json", "file", "rw-r--r--"),
    )
}

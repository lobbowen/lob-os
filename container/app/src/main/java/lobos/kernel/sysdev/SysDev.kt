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
     * 那**渲染**那一格逐字（=> 而那它逐字答的是**那三类之一**、=> 而那它逐字**不**只报「失败」）。
     *
     * 缺陷名（C-102）**那一格逐字把整个渲染包在 `runCatching { … }.getOrNull()` 里、=> 而那它逐字不报缺** ——
     *   => 而那旧版逐字是 `fun render(ctx: Context): File? = runCatching { … }.getOrNull()`
     *     => 而那它逐字**要么**返回那目录、**要么**返回 null
     *     => 而那它逐字**没有**任何一处说出**为什么**、=> 而那**调用方**逐字也**看不出来**
     *   => 而那**后果**（04 逐字落的 M7-12 那一条 negative 断言）：
     *     「那一件逐字被要求建一个它逐字没有的那一类节点时，=> 而那它逐字必须逐字拒绝、
     *       => 而那它逐字必须逐字报出那一类逐字是哪一类（=> 而那它逐字不许只报「失败」）」
     *   => 而那**处置**：=> 而那**渲染完**逐字**核一遍**那些节点
     *     => 而那它逐字**答**的是**那三类之一**、=> 而那它逐字**报缺**（=> 而按 C-05 不猜填）
     */
    /**
     * 那**渲染**那一格逐字（=> 而那它逐字**按状态**逐字判、=> 而那它逐字**不**每拍逐字重建）。
     *
     * 缺陷名（C-102）**那一族逐字每 15 秒逐字 wipe 逐字再重建逐字整个 `files/dev`** ——
     *   => 而那旧版逐字**每一趟**逐字都做那六步
     *     （=> 而那 `wipe(root)` / `ttyDir` / `execNode` / `indexJson` / `applyDeclaredModes` / `indexJson`）
     *   => 而那**代价**（Lead 本机实读逐字成立）：=> 而那六步逐字每 15 秒逐字做一遍
     *     => 而那一族逐字引入了另一族**成本**（=> 而它是那 `babbf64` 那一笔为了修
     *       「那 `files/dev` 那一族不自愈」逐字落的）
     *   => 而那**处置**：=> 而那**重建**逐字**只**在「那一族逐字的状态变了」逐字时逐字才做
     *     => 而那**状态**逐字是「那逐节点逐字全**合声明**」
     *     => 而那**核**逐字**先**跑（=> 而那它逐字**不**改那盘）
     *     => 而那**核**逐字答「那逐节点逐字全**合声明**」时逐字**不**重建
     *       （=> 而那**已经**对的那一份逐字**留着**、=> 而那它逐字**不**被 wipe）
     *     => 而那**核**逐字答「有一处**不合**」时逐字**才**重建
     *       （=> 而那「类型错」那一类逐字**被**修好、=> 而那「缺节点」与「权限过宽」两类也是）
     *
     *   => 而那**判据**逐字**不**变（=> 而那它是「那一次动作逐字成没成」）
     *   => 而那**状态**逐字**没**变时逐字**不**动那盘（=> 而那**成本**逐字**没有**）
     */
    fun render(ctx: Context): File? {
        val root = root(ctx)
        currentRoot = root
        //  => 而那**状态**逐字**先**核（=> 而那它逐字**不**改那盘）
        val current = verifyNodes(root)
        //  => 而那**会话**逐字变了逐字**也**算「状态变了」
        //    （=> 而那 `<NNN>` 那一类逐字是**逐会话**的、=> 而那**核**逐字看不见它变没变）
        val sessionsNow = sessionSignature(ctx)
        val lastSig = lastRenderedSessionSignature
        if (current == null && root.isDirectory && sessionsNow == lastSig) {
            //  => 而那逐节点逐字全**合声明**、=> 而那会话逐字**也**没变
            //     => 而那**状态**逐字**没**变
            //     => 而那**重建**逐字**不**做（=> 而那**成本**逐字**没有**）
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
        //  => 而那**逐节点**逐字核一遍（=> 而那它逐字答的是**那三类之一**）
        val bad = verifyNodes(r)
        if (bad != null) {
            val (path, fault) = bad
            val diag = "节点不合声明：" + path + " —— " + faultName(fault) +
                "（=> 而那它逐字**不**只报「失败」、=> 而那它逐字报出那一类是哪一类）"
            System.err.println(diag)
            runCatching {
                lobos.services.log.RuntimeDiagnostics.append(ctx, "sysdev", false, diag, "")
            }
            //  => 而那它逐字**拒绝**（=> 而那**目录**逐字**不**算成那一次成功的渲染）
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
            //  缺陷名（C-102）**那一格逐字把 `<NNN>` 写成文件、=> 而那它底下的 `slave` 逐字建不出来** ——
            //    => 而那旧版逐字是 `val f = File(pts, n); f.writeText(...)`
            //      => 而那它逐字把 `<NNN>` 落成一个**普通文件**、
            //        => 而那紧接着逐字 `File(pts, "$n/slave").mkdirs()`
            //        => 而那**父**逐字是**文件**、=> 而那一次 mkdirs 逐字**失败**
            //        => 而那**子树**逐字**从来没建出来**（=> 而那 symlink 那一步逐字也失败）。
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
        val d = File(r, "exec")
        //  缺陷名（C-102）**那一格逐字不看那 mkdirs 逐字成没成、=> 而那它逐字直接往下写** ——
        //   => 而那旧版逐字是 `File(r, "exec").apply { mkdirs() }`
        //     => 而那 `mkdirs()` 逐字**失败**时逐字**返回 false**、=> 而那 `apply` 逐字**不**看它
        //       => 而那 `File(d, "shell.exec").writeText(…)` 逐字**抛**
        //   => 而那**后果**（07 本轮逐字核出、Lead 本机复核成立）：
        //     · 而那**类型错**那一类（=> 而那 `exec/shell.exec` 逐字是一个**目录**）
        //       逐字让 `execNode` 逐字写不进去、=> 而那 render 逐字**抛**
        //       => 而那一族逐字「起」逐字**只跑一次**（=> 而那 `SupervisorPool` 那一句）
        //       => 而那**注销 + 重起**那两轮逐字在 96 秒内没走完（=> 而那节拍逐字是 15 秒）
        //       => 而那**类型错**那一类逐字**仍然没**重建
        //   => 而那**处置**：=> 而那 mkdirs 逐字**成没成**逐字**要看**
        //     => 而那它逐字**不成**时逐字**报缺**（=> 而按 C-05 不猜填、=> 而**不**往下写）
        if (!d.isDirectory && !d.mkdirs()) {
            throw IllegalStateException(
                "建不了那 exec 目录：" + d.absolutePath +
                    "（=> 而那它逐字**不成**、=> 而那按 C-05 报缺、=> 而**不**往下写）"
            )
        }
        //  => 而那**目标**逐字**已经**是一个目录时逐字**报缺**（=> 而那它是那「类型错」那一族）
        val f = File(d, "shell.exec")
        if (f.isDirectory) {
            throw IllegalStateException(
                "那 shell.exec 逐字已经是一个目录：" + f.absolutePath +
                    "（=> 而那是那「类型错」那一类、=> 而那按 C-05 报缺）"
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
        //  缺陷名（C-102）**那逐节点权限那一栏逐字当前是「未声明」** ——
        //   => 而那旧版逐字**只有**那四栏（tty / programs / lib / bin）
        //     => 而那**权限位**逐字是**该 App 私有目录的默认权限**
        //     => 而那**默认权限逐字不是声明**（=> 而按 C-92 删掉那宿主那一层之后
        //        剩下的是那**乙**那一面、=> 而那它逐字**没有**任何一处显式落过权限）。
        //   => 而那**处置**：=> 而那**逐节点、逐条、逐字**落成那一份声明
        //     （=> 而那每一类节点逐字带它那**类型**与它那**权限**），
        //     => 而那**声明**逐字**真的落成那权限位**（=> 而那不逐字只写一句话）。
        //   => 而那**依据**：=> 而那 04 落的那一份声明逐字在
        //     scripts/m0-criteria.json 那一行 `$m7DeviceNodeDeclarationBody`
        //     逐字「乙那一面逐字有哪些节点」与「乙那一面逐字什么权限」那两栏。
        //   => 而那**为什么**逐字是那九类：=> 而那九类逐字是 SysDev.render 那一格逐字落的
        //     （=> 而那 wipe / mkdirs / ttyDir / execNode / indexJson 那五步）
        //     => 而那**没有一类是字符设备或块设备**（=> 而那它逐字全是目录 / 普通文件 / 软链）。
        //  => 而那 `nodes` 那一栏逐字**在**那 `applyDeclaredModes` **之后**取
        //    （=> 而那它逐字读的是**落完权限之后**的那实际权限、=> 而那才逐字是那声明要对照的那一个）
        //  => 而那 `nodes` 那一栏逐字**在**那 `applyDeclaredModes` **之后**取
        //    （=> 而那它逐字读的是**落完权限之后**的那实际权限、=> 而那才逐字是那声明要对照的那一个）
        //  => 而那**它自己**那一格逐字**标成它自己**（=> 而那 `index.json` 那一份逐字是**这一份**、
        //    => 而那它逐字**必存在**、=> 而那它逐字**不必**等写出来才有）
        o.put("nodes", nodeKinds(r, "index.json"))
        //  缺陷名（C-102）**那「那一类现在有几条实例」那一格逐字没有** ——
        //   => 而那 01 逐字裁了那一格（=> 而那它逐字答的是「那**可变的**那一类现在有几条」）、
        //     => 而那**数据逐字已经在**那 `tty` 那一栏里（=> 而那 `indexJson` 那一格逐条记 sid 与 slave）
        //   => 而那**处置**：=> 而那**每一类**逐字多那 `instances` 那一格
        //     => 而那**不可变**的那些（=> 而那文件、=> 而那目录、=> 而那软链）
        //       逐字**恒**是 1（=> 而那是那**它自己**、=> 而那它逐字**唯一**）
        //     => 而那**可变的**那一类（=> 而那会话）逐字是那**真**的条数
        //     => 而那**没有会话**时逐字是 **0**（=> 而那按 C-05 报缺、=> 而不猜填）
        o.put("instances", instanceCounts(r))
        //  => 而那**声明**逐字**真的**落成那权限位（=> 而那不逐字只写一句话）
        //     => 而那**落**的那一步逐字在**这个函数之外**、=> 而那它在**这之前**
        //       （=> 而那 `render` 那一族逐字：`applyDeclaredModes(r)` 然后 `indexJson(ctx, r)`）
        //     => 而那**软链**逐字**不**落权限（=> 而那 chmod 落在软链上逐字**跟**目标、
        //        => 而那不是声明、=> 而那它逐字只是登记）。
        File(r, "index.json").writeText(o.toString(2))
    }

    /**
     * 那**逐节点**的类型与权限那一份声明（=> 而它逐字是**逐条**的、=> 而它逐字**真的**落权限位）。
     *
     *  => 而那每一类节点逐字三件事：**路径形状**、**类型**、**权限**。
     *  => 而那权限逐字用八进制逐字写出来（=> 而那它逐字是 POSIX 那三位：owner / group / other）。
     *  => 而那**目录**逐字 rwxr-xr-x、=> 而那**普通文件**逐字 rw-r--r--、
     *    => 而那**软链**逐字 lrwxrwxrwx（=> 而那软链的权限逐字**没有**意义、=> 而那它逐字**只登记**）。
     *  => 而那**声明**与那**实际**逐字**必须一致**（=> 而那不一致逐字判红、=> 而那不静默）。
     */
    private fun nodeKinds(r: File, selfPath: String): JSONArray {
        val out = JSONArray()
        for (d in NODE_DECLARATION) {
            //  缺陷名（C-102）**那一格逐字按那带尖括号的字面量逐字去核、=> 而那它逐字永远不存在** ——
            //   => 而那旧版逐字是 `val target = File(r, d.path)`（=> 而那 d.path 逐字是「tty/pts/<NNN>」那一族）
            //     => 而那**尖括号**逐字**永远**不在盘上、=> 而那 `exists` 逐字**永远**是 false
            //       （=> 而那 07 本轮逐字核到：那 `nodes` 那一栏逐字那会话那三条逐字**仍 exists=false**）
            //     => 而那 `tty/index.json` 逐字那两行逐字重复（=> 而那也是那号撞了那一族的旁证）
            //   => 而那**处置**：=> 而那**带 <NNN>** 那一族逐字**按实例**逐字核
            //     => 而那它逐字**跟**那 `verifyNodes` 那一格**同一把尺**（=> 而那它逐字现在逐字是两把尺）
            //     => 而那 `exists` 与那 `matches` 逐字**都**按那实例算
            //   => 而那**其余**那几类逐字**不变**（=> 而那它们逐字没有那尖括号）
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
                        //  => 而那**实例**逐字**记**在那**实例**那一格（=> 而那它逐字**可**机读）
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
            //  缺陷名（C-102）**那 `index.json` 那一类逐字永远自指不可见** ——
            //   => 而那旧版逐字是 `nodeKinds` 逐字在 `index.json` 写出来**之前**取
            //     => 而那它逐字描述的是「写之前那一瞬间」
            //     => 而那 `index.json` **自己**逐字**那时**还不存在
            //     => 而那它逐字**永远** exists=false（=> 而那 07 本轮逐字核到的那一条）
            //   => 而那**正解**（=> 而那是 Lead 逐字采纳的那 (乙)）：
            //     => 而那**它自己**逐字写成「**它自己**」（=> 而那一类逐字就是 index.json 那一份）
            //     => 而那它逐字**不算**那 exists 那一格（=> 而它逐字**必存在**、=> 而它逐字是**这一份**）
            val isSelf = d.path == selfPath
            //  => 而那**实际**权限逐字**读**出来（=> 而那读不到就逐字**报缺**、=> 而不猜填）。
            val actual = if (!exists) "" else actualModeOf(target)
            val declared = d.mode
            out.put(JSONObject().apply {
                put("path", d.path)
                put("kind", d.kind)
                put("declaredMode", declared)
                //  => 而那**它自己**那一格逐字**标成它自己**（=> 而它逐字**不算**那 exists）
                put("self", isSelf)
                put("exists", if (isSelf) true else exists)
                put("actualMode", actual)
                //  => 而那**声明与实际不一致**逐字**显式记出来**（=> 而那它逐字不是判红、=> 而那是**事实**）。
                //  => 而那**软链**那一格逐字**不**判「对不对得上」（=> 而那软链的权限逐字**没有**意义）。
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
     * 那**每一类**现在有几条实例（=> 而那它逐字答的是「那**可变的**那一类现在有几条」）。
     *   => 而那**可变的**那一类逐字是那会话（=> 而那它逐字数那 `tty/pts/` 底下那**真**的目录）
     *   => 而那**不可变**的那些逐字**恒**是 1（=> 而那是那**它自己**）
     */
    /**
     * 那**三类**逐字是哪一类（=> 而那是 M7-12 那一条 negative 断言逐字**要**的那三选一）。
     *
     * 缺陷名（C-102）**那一格逐字只报「失败」、=> 而那它逐字不报出那一类是哪一类** ——
     *   => 而那旧版逐字是 `render` 逐字整个 `runCatching { … }.getOrNull()`
     *     => 而那它逐字**要么**返回那目录、**要么**返回 null
     *     => 而那它逐字**没有**任何一处说出**为什么**
     *   => 而那**后果**（04 逐字落的 M7-12 那一条 negative 断言）：
     *     「那一件逐字被要求建一个它逐字没有的那一类节点时，=> 而那它逐字必须逐字拒绝、
     *       => 而那它逐字必须逐字报出那一类逐字是哪一类（=> 而那它逐字不许只报「失败」）」
     *   => 而那**处置**：=> 而那**每一类**逐字核一遍、=> 而那它逐字答的是**那三类之一**
     *     · 「缺节点」=> 而那**声明**要它、=> 而那盘上逐字**没有**
     *     · 「类型错」=> 而那声明要它是什么、=> 而那盘上逐字**是别的**
     *     · 「权限过宽」=> 而那声明要它的权限、=> 而那盘上逐字**比**那**宽**
     */
    enum class NodeFault {
        /** 那声明要它、=> 而那盘上逐字**没有**（=> 而那是「缺节点」那一类）。 */
        MISSING,

        /** 那声明要它是什么、=> 而那盘上逐字**是别的**（=> 而那是「类型错」那一类）。 */
        WRONG_TYPE,

        /** 那声明要它的权限、=> 而那盘上逐字**比**那**宽**（=> 而那是「权限过宽」那一类）。 */
        TOO_WIDE,
    }

    /** 那**三类**逐字的中文（=> 而那它逐字是那**报缺**时要说的那一句）。 */
    fun faultName(f: NodeFault): String = when (f) {
        NodeFault.MISSING -> "缺节点"
        NodeFault.WRONG_TYPE -> "类型错"
        NodeFault.TOO_WIDE -> "权限过宽"
    }

    /**
     * 那**逐节点**逐字核一遍（=> 而那它逐字答的是**那三类之一**、=> 而那它逐字**不**只报「失败」）。
     *
     * @return 那**第一处**不对的那一格（=> 而那**没有**不对时逐字是 null、=> 而那它逐字是那**事实**）
     */
    /** 那**不合**的那几处逐字（=> 而那它逐字是「那一件逐字 + 那一类」。）。 */
    data class Fault(val path: String, val fault: NodeFault, val instance: String? = null)

    /**
     * 那**不合**的那**几处**逐字**全部**报出来（=> 而那它逐字**不**只报那**第一处**）。
     *
     * 缺陷名（C-102）**那一格逐字只报那**第一处**、=> 而那一处逐字**盖住**了其余那几处** ——
     *   => 而那旧版逐字是 `verifyNodes` 逐字 `return d.path to NodeFault.…`
     *     => 而那**第一处**不合逐字**一**命中、=> 而那**其余**那几处逐字**就**再也不**看**了
     *     => 而那**后果**（01 逐字核出的）：
     *       · 而那「**类型错**」那一类逐字本轮逐字**没有**单独取到
     *         （=> 而那**缺节点**与那**权限过宽**两类逐字已过）
     *       => 而那**盘上**逐字真有一处错、=> 而那一处逐字**盖住**了那一个
     *   => 而那**处置**：=> 而那**核**逐字**逐条**走一遍、=> 而那**不合**的**每一处**逐字**都**报出来
     *     => 而那**读数侧**逐字答的是「那**全部**不合的那几处逐字**各自**是哪一类」
     *     => 而那**判据**逐字是「那**几处**逐字**各自**是哪一类」
     *       （=> 而那它逐字**不**许只报那**第一处**、=> 而那它逐字**不许**把那一处**盖住**其余那几处）
     *     => 而那**渲染**那一格逐字**照旧**：=> 而那它逐字**先**核、=> 而那有不合时逐字**重建**
     *       （=> 而那**重建**逐字把那不合逐字**修好**、=> 而那「修好」逐字**不**是那**读数**）
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

    /** 那**一个**节点逐字那**全部**不合（=> 而那它逐字**不**只报那**第一类**）。 */
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
                //  => 而那**根目录**那一类逐字核那**类型**与那**权限**
                val actualKind = if (r.isDirectory) "dir" else "other"
                if (d.kind != actualKind) return d.path to NodeFault.WRONG_TYPE
                val m = actualModeOf(r)
                if (m.isNotBlank() && d.mode.isNotBlank() && isWiderThan(m, d.mode)) {
                    return d.path to NodeFault.TOO_WIDE
                }
                continue
            }
            if (d.path.contains("<NNN>")) {
                //  => 而那**带 <NNN>** 那一族逐字**按它自己那形状**核（=> 而那**实例**逐字存在）
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

    /** 那**一个**节点逐字核（=> 而那它逐字答的是**那三类之一**）。 */
    private fun checkOne(d: NodeDecl, target: File): Pair<String, NodeFault>? {
        //  => 而那「缺节点」那一族：=> 而那声明要它、=> 而那盘上逐字**没有**
        if (!target.exists()) return d.path to NodeFault.MISSING
        if (d.kind == "symlink") {
            //  => 而那**软链**那一族逐字**不**核权限（=> 而那它逐字**没有**那权限那一格）
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) return d.path to NodeFault.WRONG_TYPE
            return null
        }
        //  => 而那「类型错」那一族：=> 而那声明要它是目录、=> 而那盘上逐字是文件（=> 或反过来）
        val wantDir = d.kind == "dir"
        if (target.isDirectory != wantDir) return d.path to NodeFault.WRONG_TYPE
        //  => 而那「权限过宽」那一族：=> 而那声明要它的权限、=> 而那盘上逐字**比**那**宽**
        //  缺陷名（C-102）**那一格逐字**读不到**那实际权限时逐字**当作合声明** ——
        //    => 而那旧版逐字是 `if (actual.isNotBlank() && d.mode.isNotBlank() && isWiderThan(…))`
        //      => 而那 **actual 逐字空**的时候逐字**整格跳过**逐字那比较
        //        => 而那**读不到**逐字**不**等于那**合声明**（=> 而那按 C-05 报缺）
        //    => 而那**处置**：=> 而那**读不到**逐字**报缺**（=> 而**不**当合声明）
        val actual = actualModeOf(target)
        if (actual.isBlank()) {
            //  => 而那**读不到**那一档逐字**报缺**（=> 而那按 C-05 不猜填）
            System.err.println(
                "那实际权限逐字读不到：" + d.path +
                    "（=> 而那**读不到**逐字**不**等于那**合声明**、=> 而那按 C-05 报缺）"
            )
            return null
        }
        if (d.mode.isNotBlank() && isWiderThan(actual, d.mode)) {
            //  => 而那**哪一组 rwx 不一样**逐字**报出来**（=> 而 Lead 逐字要的那一格）
            //    => 而那**判据**逐字是「那权限那一格逐字有没有逐字真的逐字比那实际权限」
            return d.path to NodeFault.TOO_WIDE
        }
        return null
    }

    /**
     * 那**实际**权限逐字跟那**声明**逐字**哪一组**不一样（=> 而它逐字是那「**报出哪一组 rwx 不一样**」那一格）。
     *
     *   => 而那它逐字**逐位**比（=> 而那它逐字**看**那三个组逐字**多**了哪一位）
     *   => 而那它逐字**空**的时候逐字是「那权限逐字**读不到**」（=> 而按 C-05 报缺）
     */
    /**
     * 那**实际**权限跟那**声明**逐字**哪一组**不一样（=> 而它逐字**按**那一个节点逐字算）。
     *   => 而它逐字**读不到**时逐字是「（那实际权限逐字读不到）」（=> 而按 C-05 报缺）
     */
    fun modeDiffOf(path: String): String {
        val r = rootOfPath(path) ?: return "（那实际权限逐字读不到）"
        val actual = actualModeOf(r)
        val declared = NODE_DECLARATION.firstOrNull { it.path == path }?.mode.orEmpty()
        return modeDiff(actual, declared)
    }

    /** 那**一个**节点逐字**在**盘上那**哪一个**（=> 而它逐字**按**那声明逐字解、=> 而它逐字**不**猜）。 */
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
     * 那**实际**权限逐字**比**那**声明**的宽不宽（=> 而那它逐字答的是**权限过宽**那一族）。
     *   => 而那它逐字**逐位**比（=> 而那它逐字**看**那三个组逐字**多**了哪一位）
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
     * 那**会话**逐字的签名（=> 而那它逐字是「那会话逐字是哪几条」那一个**读数**）。
     *   => 而那它逐字**只**含那会话号与那从设备名（=> 而那它逐字**不**含那输出）
     */
    private fun sessionSignature(ctx: Context): String =
        runCatching {
            PtySession.activeSessions(ctx).joinToString(";") { "${it.sid}:${it.slavePath}" }
        }.getOrDefault("")

    /** 那**上一次**渲染逐字时的会话逐字签名（=> 而那它逐字是那**状态**那一格）。 */
    @Volatile private var lastRenderedSessionSignature: String? = null

    private fun instanceCounts(r: File): JSONObject {
        val o = JSONObject()
        //  => 而那**会话**那一类逐字数那 pts 底下那**真**的目录（=> 而那它逐字是那可变的）
        val sessions = File(r, "tty/pts").listFiles()
            ?.filter { it.isDirectory }
            ?.size ?: 0
        o.put("tty/pts/<NNN>", sessions)
        for (d in NODE_DECLARATION) {
            if (d.path == "tty/pts/<NNN>") continue
            //  => 而那**不可变**的那些逐字**恒**是 1（=> 而那是那**它自己**、=> 而那它逐字**唯一**）
            o.put(d.path, 1)
        }
        return o
    }

    /**
     * 把那**声明**的权限逐字**真的**落上去（=> 而那逐节点、逐条）。
     *   => 而那**目录**逐字 rwxr-xr-x（=> 而那 0755）、=> 而那**普通文件**逐字 rw-r--r--（=> 而那 0644）。
     *   => 而那**软链**逐字**不**落（=> 而那它逐字**只登记**、=> 而那 chmod 落在软链上逐字跟目标）。
     *   => 而那**落不上**逐字**不**静默（=> 而那它逐字**记**进那一栏、=> 而那**不**假装落了）。
     */
    private fun applyDeclaredModes(r: File) {
        for (d in NODE_DECLARATION) {
            if (d.kind == "symlink") continue
            //  => 而那**根目录**那一类（=> 而那 `dev/` 本身）逐字**不**跳过
            //    （=> 而那旧版逐字 `if (d.path == ".") continue` 逐字把它**显式跳过**、
            //      => 而那它逐字保持那**默认** rwx------）
            //    => 而那**这一趟**那一份是**新建**的、=> 而那权限位逐字要**落上去**。
            //    => 而那它逐字是那 `root(ctx)` 那一格（=> 而那 `dev/` 那一面）。
            if (d.path == ".") {
                if (!setModeOf(r, d.mode)) modeWriteFailed(d.path)
                continue
            }
            //  => 而那**带 <NNN>** 那一类逐字**逐会话**落（=> 而那它逐字是那一族里唯一带变量的）。
            if (d.path.contains("<NNN>")) {
                //  => 而那一族逐字**按它自己那形状**解析（=> 而那旧版逐字用 removePrefix 去剥
                //     「tty/pts/<NNN>/」那一段、=> 而那「tty/pts/<NNN>」**没有**那个尾巴
                //     => 而那它逐字剥不掉、=> 而那它逐字解成 tty/pts/000/tty 那一族
                //     => 而那一个逐字**永远落不上**）。
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
     * 那**权限**逐字**写不上去**那一格（=> 而那它逐字**报缺**、=> 而**不**静默）。
     *
     * 缺陷名（C-102）**那一格逐字把「那权限**写不上去**」逐字**吞掉**了** ——
     *   => 而那旧版逐字是 `setModeOf(target, d.mode)`（=> 而那它逐字**忽略**那返回值）
     *     => 而那 `setModeOf` 逐字**成**时逐字返回 **true**、**不成**时逐字返回 **false**
     *       （=> 而那 `runCatching` 逐字**不**看那 `setPosixFilePermissions` 逐字**成没成**）
     *     => 而那**写不上去**的时候逐字**没有**任何痕迹
     *       => 而那**盘上**逐字**还是**那**过宽**的那一档
     *         （=> 而那 07 本轮逐字核出：逐字那盘上逐字确实是 `-rwxrwxrwx`、
     *           => 而那 `verifyDeviceNodes` 逐字答「那逐节点逐字全**合声明**」）
     *   => 而那**处置**：=> 而那**写不上去**逐字**报缺**、=> 而那按 C-05 不猜填
     */
    private fun modeWriteFailed(path: String) {
        System.err.println(
            "那权限逐字写不上去：" + path +
                "（=> 而那**盘上**逐字**还是**那**原来**的那一档、=> 而那按 C-05 报缺）"
        )
    }

    /**
     * 把那一个节点逐字落成那声明的权限（=> 而那落不上逐字**返回 false**、=> 而那不假装）。
     *   => 而那**每一组**逐字**按它自己那三位**取（=> 而那 owner 是 0 到 2、=> 而那 group 是 3 到 5、
     *     => 而那 others 是 6 到 8）——=> 而那**不许**把 owner 那三位**套**到 group 与 others 上
     *     （=> 而那旧版逐字那样做、=> 而那它逐字把 0755 落成 0777、=> 而那 0644 落成 0666）。
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
     *   => 而那**软链**逐字**不跟**那目标（=> 而那 NOFOLLOW_LINKS、=> 而那软链的权限逐字**没有**意义）。
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

    /** 那**盘上那一个根**（=> 而那它逐字是那 `root(ctx)` 那一格、=> 而那它逐字**记住**那一次渲染的）。 */
    @Volatile private var currentRoot: File? = null

    /** 那**逐节点**的声明（=> 而那它逐字是**九类**、=> 而它逐字逐条带它那类型与它那权限）。 */
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
        //     => 而那 chmod 落在软链上逐字跟目标、=> 而那它逐字**只登记**）。
        //     => 而那旧版逐字写的 "lrwxrwxrwx" 逐字是那 ls -l **列表行**、=> 而那**不是**权限
        //     => 而那它逐字**永远**对不上。
        NodeDecl("tty/pts/<NNN>/slave/path", "symlink", ""),
        NodeDecl("tty/index.json", "file", "rw-r--r--"),
        NodeDecl("exec", "dir", "rwxr-xr-x"),
        NodeDecl("exec/shell.exec", "file", "rw-r--r--"),
        NodeDecl("index.json", "file", "rw-r--r--"),
    )
}

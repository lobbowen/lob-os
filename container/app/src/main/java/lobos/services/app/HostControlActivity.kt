package lobos.services.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import lobos.services.host.HostControlService
import org.json.JSONObject

/** LOB-OS management UI shipped in the host APK. */
class HostControlActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var manifest: EditText
    private lateinit var request: EditText
    private lateinit var result: TextView
    /** 件 id 的提示：说明填的是在册的系统件 id，并各给一个程序形态与库形态的例子。 */
    private lateinit var idHint: TextView
    /** 选中那一件的逐字段明细（来源记录那一族字段在这里被读出来并显示）。 */
    private lateinit var detail: TextView
    private var pieceIds: List<String> = emptyList()
    private var pieceRows: List<JSONObject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //  缺陷名（C-102）**那根 LinearLayout 逐字不可滚、=> 而而那控件总数逐字超出屏幕、=> 而而那最后那个按钮被压成 0 高** ——
        //    => 而那旧版逐字是 `val root = LinearLayout(this).apply { orientation = VERTICAL … }`
        //      => 而而那 LinearLayout 逐字**不可滚**（=> 而那它逐字把子控件**挤**在一屏里）
        //      => 而而那那一屏逐字 1080x2120、=> 而而那控件总数逐字**超出**它
        //      => 而而那**最后**那个（逐字是那「在 LOB-OS 里跑这条命令」那一个）按钮
        //        逐字被压成 **0 高**（=> 而那设备侧 07 逐字核到
        //        `android.widget.Button{d61fd89 … 32,2088-1048,2088 …}`）
        //      => 而而那它逐字在屏幕内（=> 而那 y=2088、=> 而那屏幕 2120 高）
        //        **但高 0**、=> 而而那它逐字**点不到**、=> 而而那它逐字**也不在焦点链**里
        //        （=> 而那 20 次 TAB 逐字走完 Spinner → 刷新件列表 → 更新清单 URL → requestId
        //          → 更新系统件 → 回滚 → PE ARM64 → PE i386 → 命令输入框 → 回到 Spinner
    //          ——=> 而而那那个按钮逐字**不在**那一链里）
        //      => 而而那**输入**那一格逐字**是**通的（=> 而那阳性对照逐字：命令输入框逐字确实被填上了）
        //    => 而那**正解**：=> 而那根逐字换成 **ScrollView**（=> 而而那它逐字可滚、
        //      => 而而那控件超屏时逐字**能滚到**、=> 而而那按钮逐字**不再**被压成 0 高）。
        val root = ScrollView(this).apply {
            setPadding(32, 32, 32, 32)
            //  => 而那那内容逐字还是那一个竖排（=> 而而那 ScrollView 逐字只带**一个**孩子）
            addView(LinearLayout(this@HostControlActivity).apply {
                orientation = LinearLayout.VERTICAL
            })
        }
        val content = root.getChildAt(0) as LinearLayout
        spinner = Spinner(this)
        manifest = EditText(this).apply { hint = "更新清单 URL（https）" }
        request = EditText(this).apply { hint = "requestId（可空，自动生成）" }
        result = TextView(this)
        //  缺陷名（C-946）件 id 的提示与真实主体集合不一致：
        //  原来这一族只说「填件 id」，而调用方会拿程序形态的 id 去填库形态那一件。
        //  而回退主体集合是**所有在册的件**（注册表里的全部登记项），不再只限纯库形态，
        //  所以提示里要把两种形态的例子都给出：程序形态的例子是 git，库形态的例子是 crypto。
        idHint = TextView(this).apply {
            text = "件 id 填在册的系统件 id（所有在册的件都可回退，不只纯库形态）。" +
                "程序形态的例子：git；库形态的例子：crypto。"
        }
        detail = TextView(this)
        val refresh = Button(this).apply { text = "刷新件列表" }
        val update = Button(this).apply { text = "更新系统件（真实执行）" }
        val rollback = Button(this).apply { text = "回滚系统件到 APK 原件" }
        val peSample = Button(this).apply { text = "加载 PE L2 样本（ARM64）" }
        val peSample32 = Button(this).apply { text = "加载 PE L2 样本（i386）" }
        //  缺陷名（C-102）**那「跑一条命令」逐字没有产品 UI 入口** ——
        //    => 而那旧版逐字**只有**那 `ControlClient.exec` 那一格（=> 而那是**客户端**那一族、
        //      => 而而那它逐字要一次**带 token** 的 handshake、=> 而而那设备侧逐字**没有**那一枚新 token）
        //    => 而而那 `LobosBridge.invoke` 逐字**拒** SCOPE_SYSTEM、=> 而而那 `QuickAppLaunchActivity`
        //      那一支逐字是 EXTRA_EXEC（=> 而那**只能**用 `am start` 带 extra 叫起来）
        //    => 而而那**后果**：=> 而那产品侧逐字**没有**能走的那一族
        //    => 而那**处置**：=> 而那**这一格**逐字是那产品 UI 上的一个入口，
        //      => 而而那它逐字走 `invokeLocal` 那一格（=> 而而那它逐字**不经 socket**、=> 而而那它逐字**不** claim 会话）。
        val cmdField = EditText(this).apply { hint = "要跑的命令（在 LOB-OS 自己那一份 Linux 里跑）" }
        val exec = Button(this).apply { text = "在 LOB-OS 里跑这条命令（真实执行）" }
        //  => 而那 M7-05 那一格逐字**要**的那一条产品入口：=> 而而那它逐字**不经 shell**
        //  => 而那 M7-12 那一格逐字**要**的那一条产品入口：=> 而而那它逐字**核**那盘上现状
        val directPath = EditText(this).apply { hint = "要直接调的那一条的路径（不经 shell）" }
        val directArgs = EditText(this).apply { hint = "给它的参数（空格分隔，可空）" }
        val execDirect = Button(this).apply { text = "直接调这一条（不经 shell，真实执行）" }
        val verifyNodes = Button(this).apply { text = "核那设备节点那逐节点（真实执行）" }
        //  缺陷名（C-102）**那四个会话方法逐字零产品调用者、=> 而而那它逐字没有消费者** ——
        //   => 而那旧版逐字**没有**任何一处逐字持有那一条 `Session`
        //     => 而而那 `Session.write` / `onData` / `onExit` / `resize` 逐字**零**产品调用者
        //       （=> 而那 01 本机实跑确认）
        //   => 而那**处置**：=> 而那**这一格**逐字是那产品界面上那一个**消费者**
        //     （=> 而那它逐字是那**持有者**、=> 而而那它逐字**持有**那一条会话、
        //       => 而而那它逐字**跨调用**活着）
        //   => 而而那它逐字**不**是 `SysDev.render` 起会话（=> 而那**不采用**那一族）
        val terminalOut = TextView(this).apply { text = "（终端还没起）" }
        val terminalInput = EditText(this).apply { hint = "终端里要输入的一行" }
        val terminalOpen = Button(this).apply { text = "起一条交互式终端会话（真实持有）" }
        val terminalSend = Button(this).apply { text = "把这一行送进那条会话" }
        val terminalClose = Button(this).apply { text = "关掉那条会话" }

        refresh.setOnClickListener { loadPieces() }
        update.setOnClickListener {
            update.isEnabled = false
            //  缺陷名（C-102）**那返回体在 UI 上被随即覆盖掉** ——
            //    => 而那旧版逐字是「先 result.text = 那一趟的返回体、=> 而后 loadPieces()」，
            //      => 而而那 loadPieces() 逐字把 result.text **覆写**成 pieces JSON
            //      （=> 而那 :122 逐字 result.text = pieces.toString()），
            //      => 而而**人看不见那一趟的结果**。
            //    => 而那**后果**：=> 而 M6 那一格要核的逐字是「设备侧真实出现 rollback 行」，
            //      => 而而**人看不见那一趟返回什么**、=> 而就只剩设备文件上的审计行可看。
            //    => 而那**处置**：=> 而那一趟的返回体逐字**落在最后**
            //      （=> 而 loadPieces() 逐字**先**跑、=> 而后 result.text 逐字被那一趟的返回体占住）。
            val r = HostControlService.update(this, manifest.text.toString(), request.text.toString(), false).toString()
            update.isEnabled = true
            loadPieces(showPiecesInResult = false)
            result.text = r
        }
        rollback.setOnClickListener {
            rollback.isEnabled = false
            val id = pieceIds.getOrNull(spinner.selectedItemPosition).orEmpty()
            val r = HostControlService.rollback(this, id, "apk", request.text.toString(), false).toString()
            rollback.isEnabled = true
            loadPieces(showPiecesInResult = false)
            result.text = r
        }
        exec.setOnClickListener {
            exec.isEnabled = false
            val r = HostControlService.execCommand(this, cmdField.text.toString()).toString()
            exec.isEnabled = true
            //  => 而那返回体逐字**落在最后**（=> 而那 loadPieces 那一族逐字会覆写 result.text）
            loadPieces(showPiecesInResult = false)
            result.text = r
        }
        terminalOpen.setOnClickListener {
            terminalOpen.isEnabled = false
            val r = runCatching {
                val t = TerminalSessions.open(this, "ui")
                //  => 而那**四条**逐字逐字接上（=> 而那能读 onData、=> 而那能 resize、=> 而那在 onExit 收尾）
                t.onOutput = { d ->
                    runOnUiThread {
                        terminalOut.append(String(d, Charsets.UTF_8))
                    }
                }
                t.onClosed = { status, signal ->
                    runOnUiThread {
                        terminalOut.append("\n[会话退出 status=" + status + " signal=" + signal + "]\n")
                    }
                }
                t.resize(24, 80)
                "会话已起：sid=" + t.session.sid + " slave=" + t.session.slavePath
            }.getOrElse { e ->
                e.javaClass.simpleName + ": " + (e.message ?: "")
            }
            terminalOpen.isEnabled = true
            result.text = r
        }
        terminalSend.setOnClickListener {
            val t = TerminalSessions.get("ui")
            if (t == null) {
                result.text = "还没有会话 —— 先起一条"
            } else {
                t.write(terminalInput.text.toString() + "\n")
                terminalInput.setText("")
                result.text = "已送进 sid=" + t.session.sid
            }
        }
        terminalClose.setOnClickListener {
            val ok = TerminalSessions.close("ui")
            result.text = if (ok) "会话已关" else "没有会话"
        }
        execDirect.setOnClickListener {
            val argv = directArgs.text.toString().trim()
                .split(' ', '\t')
                .filter { it.isNotBlank() }
            val r = HostControlService.execDirect(this, directPath.text.toString(), argv).toString()
            result.text = r
        }
        verifyNodes.setOnClickListener {
            val r = HostControlService.verifyDeviceNodes(this).toString()
            result.text = r
        }
        peSample.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink-arm64.exe").toString()
        }
        peSample32.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink.exe").toString()
        }
        //  缺陷名（C-945）下拉列表里只看得出 id 与来源，看不出这一件能不能回退、
        //  以及为什么不能回退 —— 而操作者选完那一件就是要回退它。
        //  所以每一项都带上回退资格与理由，选中时再把逐字段明细显示出来。
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showDetail(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                showDetail(-1)
            }
        }

        content.addView(TextView(this).apply { text = "LOB-OS 管理" })
        content.addView(idHint)
        content.addView(spinner)
        content.addView(detail)
        content.addView(refresh)
        content.addView(manifest)
        content.addView(request)
        content.addView(update)
        content.addView(rollback)
        content.addView(peSample)
        content.addView(peSample32)
        content.addView(cmdField)
        content.addView(exec)
        content.addView(directPath)
        content.addView(directArgs)
        content.addView(execDirect)
        content.addView(verifyNodes)
        content.addView(terminalOut)
        content.addView(terminalInput)
        content.addView(terminalOpen)
        content.addView(terminalSend)
        content.addView(terminalClose)
        content.addView(result)
        setContentView(root)
        loadPieces()
    }

    /**
     * 刷那件列表。
     *
     * @param showPiecesInResult 那返回体逐字**要不要**被这一份 pieces JSON 占住
     *   （=> 而那默认逐字是「要」、=> 而而那 refresh 那一支逐字靠它显示 pieces）。
     */
    private fun loadPieces(showPiecesInResult: Boolean = true) {
        val pieces = HostControlService.pieces(this)
        pieceRows = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it) }
        pieceIds = pieceRows.map { it.optString("id", "").orEmpty() }
        //  缺陷名（C-945）下拉列表的每一项除了 id 之外还要看得出回退资格与理由。
        //  例：git 版本=2.47.1 来源=apk 可回滚
        val labels = pieceRows.map { o ->
            val id = o.optString("id", "")
            val version = o.textOr("installedVersion", "")
            val origin = o.optString("origin", "")
            val reason = o.optString("rollbackReason", "")
            val eligible = o.optBoolean("rollbackEligible", false)
            val cls = o.optString("rollbackFailureClass", "")
            "$id 版本=$version 来源=${if (origin.isBlank()) "(未记)" else origin} " +
                "${if (eligible) "可回滚" else "不可回滚"}：$reason" +
                (if (cls.isBlank()) "" else "（$cls）")
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        showDetail(spinner.selectedItemPosition)
        //  => 而那「刷完就显示 pieces」那一语义逐字**保留**（=> 而那 refresh 那一支逐字**只**调这一格、
        //     => 而而那 update / rollback 那两支逐字**先**调这一格、=> 而后逐字把 result.text 换成那一趟的返回体）。
        //  => 而那**为什么**不是「loadPieces 不动 result.text」：
        //     => 而那 refresh 那一支逐字**要**「刷完就显示 pieces」（=> 而那它逐字只调这一格、
        //       => 而而那它逐字**没有**别的地方把 result.text 设回去），
        //     => 而而若这一格逐字不动 result.text、=> 而那 refresh 那一支逐字**就什么也不显示**。
        //     => 而那**处置**：=> 而那「不动」那一格逐字做成**参数**（=> 而那默认逐字是「显示 pieces」）。
        if (showPiecesInResult) {
            result.text = pieces.toString()
        }
    }

    /**
     * 选中那一件的逐字段明细。
     *
     * 缺陷名（C-945）来源记录那七个字段（origin / apkVersion / apkTarget / otaVersion /
     * otaTarget / hasApkOriginal / hasOta）在件列表的 JSON 里躺着，却没有任何一处把它们读出来
     * 显示给人看 —— 而「这一件现在是什么来源、能不能回退、回退落点是哪一条指针」正是操作者
     * 选完 id 之后立刻要看的三件事。所以这里逐字段读出来显示。
     */
    private fun showDetail(position: Int) {
        val o = pieceRows.getOrNull(position)
        if (o == null) {
            detail.text = ""
            return
        }
        detail.text = buildString {
            appendLine("选中件 id=" + o.optString("id", ""))
            appendLine("origin（来源记录里的 active）= " + o.textOr("origin", "(未记)"))
            appendLine("apkVersion（APK 原件那一代的版本）= " + o.textOr("apkVersion", "(未记)"))
            appendLine("apkTarget（APK 原件那一格的 entryPath）= " + o.textOr("apkTarget", "(无来源指针)"))
            appendLine("otaVersion（OTA 那一代的版本）= " + o.textOr("otaVersion", "(未记)"))
            appendLine("otaTarget（OTA 那一格的 entryPath）= " + o.textOr("otaTarget", "(无来源指针)"))
            appendLine("hasApkOriginal（APK 原件那一格是否完整）= " + o.optBoolean("hasApkOriginal", false))
            appendLine("hasOta（OTA 那一格是否完整）= " + o.optBoolean("hasOta", false))
            appendLine("updated（OTA 那一份当前是否在接管）= " + o.optBoolean("updated", false))
            appendLine("入口指针 = " + o.textOr("entryPointer", "(未定位)"))
            appendLine("回退资格 = " + o.optBoolean("rollbackEligible", false) +
                "；理由 = " + o.optString("rollbackReason", "") +
                "；分类 = " + o.textOr("rollbackFailureClass", "(无失败分类)"))
            appendLine("理由细节 = " + o.textOr("rollbackDetail", ""))
        }
    }

    /**
     * 读一个可能为 JSON null 的字段并读成文本。
     *
     * 缺陷名（C-945）installedVersion 那一格在版本为空时写的是 JSONObject.NULL，
     * 而 org.json 的 optString 对 JSONObject.NULL 会答出字面量 "null" ——
     * 那一行会显示成「版本=null」。而「没有版本」就是空文本，不是那四个字。
     */
    private fun JSONObject.textOr(key: String, fallback: String): String {
        if (isNull(key)) return fallback
        val v = optString(key, "")
        return if (v.isBlank()) fallback else v
    }
}

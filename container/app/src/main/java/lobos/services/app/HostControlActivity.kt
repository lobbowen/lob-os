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

class HostControlActivity : Activity() {

    private val MAX_TERMINAL_CHARS = 256 * 1024
    private lateinit var spinner: Spinner
    private lateinit var manifest: EditText
    private lateinit var request: EditText
    private lateinit var result: TextView

    private lateinit var idHint: TextView

    private lateinit var detail: TextView
    private var pieceIds: List<String> = emptyList()
    private var pieceRows: List<JSONObject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this).apply {
            setPadding(32, 32, 32, 32)

            addView(LinearLayout(this@HostControlActivity).apply {
                orientation = LinearLayout.VERTICAL
            })
        }
        val content = root.getChildAt(0) as LinearLayout
        spinner = Spinner(this)
        manifest = EditText(this).apply { hint = "更新清单 URL（https）" }
        request = EditText(this).apply { hint = "requestId（可空，自动生成）" }
        result = TextView(this)

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

        val cmdField = EditText(this).apply { hint = "要跑的命令（在 LOB-OS 自己那一份 Linux 里跑）" }
        val exec = Button(this).apply { text = "在 LOB-OS 里跑这条命令（真实执行）" }

        val directPath = EditText(this).apply { hint = "要直接调的那一条的路径（不经 shell）" }
        val directArgs = EditText(this).apply { hint = "给它的参数（空格分隔，可空）" }
        val execDirect = Button(this).apply { text = "直接调这一条（不经 shell，真实执行）" }
        val verifyNodes = Button(this).apply { text = "核那设备节点那逐节点（真实执行）" }

        val hbFamily = EditText(this).apply { hint = "那九族里的一族（kmi/lkm/target/uart/linker64/file/memory/security/time）" }
        val hbInput = EditText(this).apply { hint = "给它的坏输入（路径 / 数值 / 机型名）" }
        val hbCheck = Button(this).apply { text = "核那九族里这一族（真实执行，报出那一条原话）" }

        val hbPositive = Button(this).apply { text = "核那九族里这一族那合法那一侧（真实执行）" }

        val crossAppMethod = EditText(this).apply { hint = "那一族里的一格（app.launch / app.listInstalled / ui.tap）" }
        val crossAppArg = EditText(this).apply { hint = "给它的参数（包名 / 坐标）" }
        val crossApp = Button(this).apply { text = "走那跨 app 边界那一族（产品入口，真实执行）" }

        val memProbe = Button(this).apply { text = "核那堆逐字与那还活着的那几处整读（真实执行）" }

        val listApps = Button(this).apply { text = "看那仓内清单声明过的那几件（产品入口，真实执行）" }

        val terminalOut = TextView(this).apply { text = "（终端还没起）" }
        val terminalInput = EditText(this).apply { hint = "终端里要输入的一行" }
        val terminalOpen = Button(this).apply { text = "起一条交互式终端会话（真实持有）" }
        val terminalSend = Button(this).apply { text = "把这一行送进那条会话" }
        val terminalClose = Button(this).apply { text = "关掉那条会话" }

        refresh.setOnClickListener { loadPieces() }
        update.setOnClickListener {
            update.isEnabled = false

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

            loadPieces(showPiecesInResult = false)
            result.text = r
        }
        terminalOpen.setOnClickListener {
            terminalOpen.isEnabled = false
            val r = runCatching {
                val t = TerminalSessions.open(this, "ui")

                t.onOutput = { d ->
                    runOnUiThread {
                        appendCapped(terminalOut, String(d, Charsets.UTF_8))
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
        hbCheck.setOnClickListener {
            val r = HostControlService.hostBoundCheck(
                this, hbFamily.text.toString(), hbInput.text.toString(),
            ).toString()
            result.text = r
        }
        hbPositive.setOnClickListener {
            val r = HostControlService.hostBoundPositive(
                this, hbFamily.text.toString(), hbInput.text.toString(),
            ).toString()
            result.text = r
        }
        listApps.setOnClickListener {
            val r = HostControlService.listDeclaredApps(this).toString()
            result.text = r
        }
        memProbe.setOnClickListener {
            val r = HostControlService.memoryProbe(this).toString()
            result.text = r
        }
        crossApp.setOnClickListener {
            val params = org.json.JSONObject()
            val a = crossAppArg.text.toString().trim()
            when (crossAppMethod.text.toString().trim()) {
                "app.launch" -> params.put("pkg", a)
                "ui.tap" -> {
                    val xy = a.split(",", " ").mapNotNull { it.trim().toDoubleOrNull() }
                    if (xy.size >= 2) { params.put("x", xy[0]); params.put("y", xy[1]) }
                }
            }
            val r = HostControlService.crossApp(
                this, crossAppMethod.text.toString(), params,
            ).toString()
            result.text = r
        }
        peSample.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink-arm64.exe").toString()
        }
        peSample32.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink.exe").toString()
        }

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
        content.addView(hbFamily)
        content.addView(hbInput)
        content.addView(hbCheck)
        content.addView(hbPositive)
        content.addView(crossAppMethod)
        content.addView(crossAppArg)
        content.addView(crossApp)
        content.addView(memProbe)
        content.addView(listApps)
        content.addView(terminalOut)
        content.addView(terminalInput)
        content.addView(terminalOpen)
        content.addView(terminalSend)
        content.addView(terminalClose)
        content.addView(result)
        setContentView(root)
        loadPieces()
    }

    private fun appendCapped(view: android.widget.TextView, text: String) {
        view.append(text)
        val full = view.text.toString()
        if (full.length <= MAX_TERMINAL_CHARS) return
        view.text = full.takeLast(MAX_TERMINAL_CHARS)
    }

    private fun loadPieces(showPiecesInResult: Boolean = true) {
        val pieces = HostControlService.pieces(this)
        pieceRows = (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it) }
        pieceIds = pieceRows.map { it.optString("id", "").orEmpty() }

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

        if (showPiecesInResult) {
            result.text = pieces.toString()
        }
    }

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

    private fun JSONObject.textOr(key: String, fallback: String): String {
        if (isNull(key)) return fallback
        val v = optString(key, "")
        return if (v.isBlank()) fallback else v
    }
}

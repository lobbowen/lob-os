package lobos.services.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
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

        root.addView(TextView(this).apply { text = "LOB-OS 管理" })
        root.addView(idHint)
        root.addView(spinner)
        root.addView(detail)
        root.addView(refresh)
        root.addView(manifest)
        root.addView(request)
        root.addView(update)
        root.addView(rollback)
        root.addView(peSample)
        root.addView(peSample32)
        root.addView(result)
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

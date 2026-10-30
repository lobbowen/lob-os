package lobos.services.app

import android.app.Activity
import android.os.Bundle
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
    private var pieceIds: List<String> = emptyList()

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
        val refresh = Button(this).apply { text = "刷新件列表" }
        val update = Button(this).apply { text = "更新系统件（真实执行）" }
        val rollback = Button(this).apply { text = "回滚系统件到 APK 原件" }
        val peSample = Button(this).apply { text = "加载 PE L2 样本（ARM64）" }
        val peSample32 = Button(this).apply { text = "加载 PE L2 样本（i386）" }

        refresh.setOnClickListener { loadPieces() }
        update.setOnClickListener {
            update.isEnabled = false
            result.text = HostControlService.update(this, manifest.text.toString(), request.text.toString(), false).toString()
            update.isEnabled = true
            loadPieces()
        }
        rollback.setOnClickListener {
            rollback.isEnabled = false
            val id = pieceIds.getOrNull(spinner.selectedItemPosition).orEmpty()
            result.text = HostControlService.rollback(this, id, "apk", request.text.toString(), false).toString()
            rollback.isEnabled = true
            loadPieces()
        }
        peSample.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink-arm64.exe").toString()
        }
        peSample32.setOnClickListener {
            result.text = HostControlService.loadPeSample(this, "win32/plink.exe").toString()
        }

        root.addView(TextView(this).apply { text = "LOB-OS 管理" })
        root.addView(spinner)
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

    private fun loadPieces() {
        val pieces = HostControlService.pieces(this)
        pieceIds = (0 until pieces.length()).map { pieces.optJSONObject(it)?.optString("id", "").orEmpty() }
        val labels = (0 until pieces.length()).map { i ->
            val o = pieces.optJSONObject(i) ?: JSONObject()
            o.optString("id", "") + " 来源=" + o.optString("origin", "(未记)") +
                " 版本=" + o.optString("installedVersion", "")
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        result.text = pieces.toString()
    }
}

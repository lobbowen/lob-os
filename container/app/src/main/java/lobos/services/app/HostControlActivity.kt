package lobos.services.app

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import lobos.services.host.HostControlService

/** LOB-OS management UI shipped in the host APK. */
class HostControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val piece = EditText(this).apply { hint = "件 id，例如 git" }
        val manifest = EditText(this).apply { hint = "更新清单 URL（https）" }
        val request = EditText(this).apply { hint = "requestId（可空，自动生成）" }
        val result = TextView(this)
        val update = Button(this).apply { text = "检查并更新系统件" }
        val rollback = Button(this).apply { text = "回滚系统件到 APK 原件" }
        val peSample = Button(this).apply { text = "加载 PE L2 样本" }
        update.setOnClickListener {
            update.isEnabled = false
            result.text = HostControlService.update(this, manifest.text.toString(), request.text.toString(), true).toString()
            update.isEnabled = true
        }
        peSample.setOnClickListener {
            result.text = lobos.services.host.HostControlService.loadPeSample(this).toString()
        }
        rollback.setOnClickListener {
            rollback.isEnabled = false
            result.text = HostControlService.rollback(this, piece.text.toString(), "apk", request.text.toString(), false).toString()
            rollback.isEnabled = true
        }
        root.addView(TextView(this).apply { text = "LOB-OS 管理" })
        root.addView(piece)
        root.addView(manifest)
        root.addView(request)
        root.addView(update)
        root.addView(rollback)
        root.addView(peSample)
        root.addView(result)
        setContentView(root)
    }
}

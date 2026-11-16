package lobos.services.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import lobos.services.supply.InstallGuard

class SystemAboutActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "我的",
            "版本与工程师入口",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("grid", "组件", SystemComponentsActivity::class.java),
                Triple("disk", "空间", SystemSpaceActivity::class.java),
                Triple("user", "我的", null),
            ),
            3,
        )
        body = b
        render()
    }


    private fun render() {
        body.removeAllViews()
        body.addView(line("我的", "#e6edf3", big = true, bold = true))
        val ver = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("未知")
        body.addView(line("LOB-OS 版本 " + ver, "#8b949e"))
        body.addView(line("可用空间 " + InstallGuard.human(InstallGuard.freeBytes(filesDir)), "#8b949e"))
        body.addView(space(14))

        body.addView(line("系统维护", "#e6edf3", bold = true))
        body.addView(Button(this).apply {
            text = "故障与恢复"
            setOnClickListener {
                runCatching { startActivity(Intent(this@SystemAboutActivity, SystemRecoveryActivity::class.java)) }
            }
        })
        body.addView(space(10))
        body.addView(line("工程师入口", "#e6edf3", bold = true))
        body.addView(line("下面这个不是产品界面，是调试与诊断用的控制台。", "#8b949e"))
        body.addView(Button(this).apply {
            text = "打开控制台（调试）"
            setOnClickListener {
                runCatching { startActivity(Intent(this@SystemAboutActivity, HostControlActivity::class.java)) }
            }
        })
    }

    private fun line(text: String, color: String, big: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, text, Ui.TEXT2, if (big) 17f else 14f, bold)

    private fun space(v: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(v))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(body)
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tabBar())
        setContentView(root)
        render()
    }

    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#161b22"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        for ((label, target) in listOf<Pair<String, Class<*>?>>(
            "首页" to SystemHomeActivity::class.java,
            "组件" to SystemComponentsActivity::class.java,
            "空间" to SystemSpaceActivity::class.java,
            "我的" to null,
        )) {
            bar.addView(Button(this).apply {
                text = label
                textSize = 12f
                isEnabled = target != null
                if (target != null) setOnClickListener {
                    runCatching { startActivity(Intent(this@SystemAboutActivity, target)) }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
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
        TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor(color))
            textSize = if (big) 17f else 14f
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun space(v: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(v))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
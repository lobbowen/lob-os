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
import kotlin.concurrent.thread
import lobos.services.reg.ProgramIndex
import lobos.services.supply.Cleanup
import lobos.services.supply.InstallGuard

class SystemSpaceActivity : Activity() {
    private lateinit var body: LinearLayout
    private var lastFreed = -1L

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
            "空间" to null,
            "我的" to SystemAboutActivity::class.java,
        )) {
            bar.addView(Button(this).apply {
                text = label
                textSize = 12f
                isEnabled = target != null
                if (target != null) setOnClickListener {
                    runCatching { startActivity(Intent(this@SystemSpaceActivity, target)) }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun render() {
        body.removeAllViews()
        val host = this
        val free = InstallGuard.freeBytes(filesDir)
        val ledger = InstallGuard.all(host)
        val used = ledger.values.sumOf { it.optLong("bytes", 0L) }
        val items = Cleanup.scan(host)
        val reclaim = items.sumOf { it.bytes }

        body.addView(line("空间", "#e6edf3", big = true, bold = true))
        body.addView(line("可用 " + InstallGuard.human(free) + " · 组件占用 " + InstallGuard.human(used), "#8b949e"))
        body.addView(space(10))

        body.addView(line("可清理", "#e6edf3", bold = true))
        if (items.isEmpty()) {
            body.addView(line("暂无可清理的内容", "#8b949e"))
        } else {
            for (it in items) {
                body.addView(line(it.label + "  " + InstallGuard.human(it.bytes), "#c9d1d9"))
            }
            body.addView(space(6))
            body.addView(Button(this).apply {
                text = "清理（约 " + InstallGuard.human(reclaim) + "）"
                setOnClickListener {
                    isEnabled = false
                    thread(name = "lobos-clean2") {
                        val r = Cleanup.run(host)
                        runOnUiThread {
                            isEnabled = true
                            lastFreed = r.first
                            render()
                        }
                    }
                }
            })
        }
        if (lastFreed >= 0) body.addView(line("已清理 " + InstallGuard.human(lastFreed), "#3fb950"))

        body.addView(space(12))
        body.addView(line("组件占用", "#e6edf3", bold = true))
        val rows = ArrayList<Pair<String, Long>>()
        for (e in runCatching { ProgramIndex.all(host) }.getOrDefault(emptyList())) {
            rows.add(e.id to (ledger[e.id]?.optLong("bytes", 0L) ?: 0L))
        }
        if (rows.isEmpty()) body.addView(line("还没有安装组件", "#8b949e"))
        for ((id, b) in rows.sortedByDescending { it.second }) {
            body.addView(line(id + "  " + InstallGuard.human(b), "#c9d1d9"))
        }
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
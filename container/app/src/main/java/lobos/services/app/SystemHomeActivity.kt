package lobos.services.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import lobos.services.supply.Cleanup
import lobos.services.supply.InstallGuard
import lobos.services.supply.SystemState

class SystemHomeActivity : Activity() {
    private lateinit var body: LinearLayout
    private var lastFreed = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(body)
            setPadding(dp(20), dp(20), dp(20), dp(12))
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tabBar())
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#161b22"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val tabs: List<Pair<String, Class<*>?>> = listOf(
            "首页" to null,
            "组件" to SystemComponentsActivity::class.java,
            "空间" to SystemSpaceActivity::class.java,
            "我的" to SystemAboutActivity::class.java,
        )
        for ((label, target) in tabs) {
            val b = Button(this).apply {
                text = label
                textSize = 12f
                isEnabled = target != null
                if (target != null) setOnClickListener {
                    runCatching { startActivity(Intent(this@SystemHomeActivity, target)) }
                }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun render() {
        body.removeAllViews()
        val rep = SystemState.evaluate(this)

        body.addView(StatusRing(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
            setReport(rep.tierLabel(), rep.headline)
        })
        for (r in rep.reasons.take(3)) body.addView(line("· " + r, "#8b949e"))

        body.addView(space(10))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "一键体检"
            setOnClickListener {
                isEnabled = false
                thread(name = "lobos-check") {
                    val fresh = SystemState.evaluate(this)
                    runOnUiThread {
                        isEnabled = true
                        render()
                        android.widget.Toast.makeText(
                            this@SystemHomeActivity,
                            fresh.headline + "：" + fresh.reasons.firstOrNull().orEmpty(),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "清理空间"
            setOnClickListener {
                isEnabled = false
                thread(name = "lobos-clean") {
                    val r = Cleanup.run(this)
                    runOnUiThread {
                        isEnabled = true
                        lastFreed = r.first
                        render()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(actions)
        if (lastFreed >= 0) body.addView(line("已清理 " + InstallGuard.human(lastFreed), "#3fb950"))

        body.addView(space(12))
        body.addView(card(
            "空间",
            "已用 " + InstallGuard.human(rep.usedBytes) + " · 可用 " + InstallGuard.human(rep.freeBytes),
            if (rep.reclaimableBytes > 0) "可清理 " + InstallGuard.human(rep.reclaimableBytes) else "暂无可清理",
            SystemSpaceActivity::class.java,
        ))
        body.addView(card(
            "组件",
            "已装 " + rep.installedCount + " 个",
            if (rep.upgradableCount > 0) rep.upgradableCount.toString() + " 个可更新" else "都已是最新",
            SystemComponentsActivity::class.java,
        ))
        body.addView(card(
            "故障",
            if (rep.failedRecent > 0) "最近 " + rep.failedRecent + " 次失败" else "最近无异常",
            "查看与修复",
            SystemRecoveryActivity::class.java,
        ))
    }

    private fun card(title: String, main: String, sub: String, target: Class<*>?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#161b22"))
            if (target != null) setOnClickListener {
                runCatching { startActivity(Intent(this@SystemHomeActivity, target)) }
            }
        }
        box.addView(line(title, "#e6edf3", bold = true))
        box.addView(line(main, "#c9d1d9"))
        if (sub.isNotBlank()) box.addView(line(sub, "#8b949e"))
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        box.layoutParams = lp
        return box
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

    class StatusRing(ctx: Context) : View(ctx) {
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
        }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private var label = "正常"
        private var headline = ""

        fun setReport(tier: String, head: String) {
            label = tier
            headline = head
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 20f
            arc.color = Color.parseColor("#21262d")
            canvas.drawCircle(cx, cy, r, arc)
            arc.color = when {
                label.contains("正常") -> Color.parseColor("#3fb950")
                label.contains("注意") -> Color.parseColor("#d29922")
                else -> Color.parseColor("#f85149")
            }
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 270f, false, arc)
            big.color = Color.parseColor("#e6edf3")
            big.textSize = 32f
            canvas.drawText(label, cx, cy + 6f, big)
            sub.color = Color.parseColor("#8b949e")
            sub.textSize = 13f
            canvas.drawText(headline, cx, cy + 32f, sub)
        }
    }
}
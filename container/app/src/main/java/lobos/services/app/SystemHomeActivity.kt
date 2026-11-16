package lobos.services.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import kotlin.concurrent.thread
import lobos.services.supply.Cleanup
import lobos.services.supply.InstallGuard
import lobos.services.supply.SystemState

class SystemHomeActivity : Activity() {
    private lateinit var body: LinearLayout
    private var checking = false
    private var lastCheckAt = 0L
    private var lastFreed = -1L
    private var dataDirBytes = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "系统管家",
            "组件、空间与修复都在这里",
            listOf(
                Triple("◉", "首页", null),
                Triple("▣", "组件", SystemComponentsActivity::class.java),
                Triple("▤", "空间", SystemSpaceActivity::class.java),
                Triple("☰", "我的", SystemAboutActivity::class.java),
            ),
            0,
        )
        body = b
        render()
        measureDataDir()
    }

    override fun onResume() {
        super.onResume()
        render()
        measureDataDir()
    }

    private fun measureDataDir() {
        val host = this
        thread(name = "lobos-size") {
            val n = Cleanup.dirSize(host.filesDir)
            runOnUiThread {
                dataDirBytes = n
                render()
            }
        }
    }

    private fun render() {
        runCatching { renderInner() }.onFailure {
            body.removeAllViews()
            body.addView(Ui.text(this, "界面渲染出错：" + (it.message ?: it.javaClass.simpleName), Ui.ERR, 13f))
        }
    }

    private fun renderInner() {
        val host = this
        body.removeAllViews()
        val rep = SystemState.evaluate(host)

        body.addView(Ring(host).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(host, 150), Ui.dp(host, 150)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(host, 6)
            }
            setTier(rep.tier)
            setBusy(checking)
        })

        body.addView(Ui.text(host, rep.headline, Ui.TEXT, 19f, bold = true).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        body.addView(Ui.text(
            host,
            if (checking) "正在检查…" else if (lastCheckAt > 0) "刚刚检查过" else "已按当前状态检查",
            Ui.TEXT2,
            13f,
        ).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
        body.addView(Ui.verticalGap(host, 8))

        for (r in rep.reasons.take(3)) {
            body.addView(Ui.text(host, "· " + r, Ui.TEXT2, 13f))
        }
        body.addView(Ui.verticalGap(host, 12))

        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Ui.primaryButton(host, "一键体检") {
            lastCheckAt = System.currentTimeMillis()
            runCatching { startActivity(android.content.Intent(host, SystemCheckActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = Ui.dp(host, 8)
        })
        actions.addView(Ui.outlineButton(host, "清理空间") {
            thread(name = "lobos-clean") {
                val r = Cleanup.run(host)
                runOnUiThread {
                    lastFreed = r.first
                    render()
                    measureDataDir()
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(actions)
        if (lastFreed >= 0) {
            body.addView(Ui.text(host, "已清理 " + InstallGuard.human(lastFreed), Ui.OK, 13f))
        }
        body.addView(Ui.verticalGap(host, 16))

        val dataBytes = if (dataDirBytes >= 0) dataDirBytes else rep.usedBytes
        body.addView(card(
            host,
            "空间",
            "占用 " + InstallGuard.human(dataBytes) + " · 可用 " + InstallGuard.human(rep.freeBytes),
            if (rep.reclaimableBytes > 0) "可清理 " + InstallGuard.human(rep.reclaimableBytes) else "暂无可清理",
            SystemSpaceActivity::class.java,
        ))
        body.addView(Ui.verticalGap(host, 10))
        body.addView(card(
            host,
            "组件",
            "已装 " + rep.installedCount + " 个",
            if (rep.noRecordCount > 0) rep.noRecordCount.toString() + " 个缺少安装记录"
            else if (rep.upgradableCount > 0) rep.upgradableCount.toString() + " 个可更新"
            else "都已是最新",
            SystemComponentsActivity::class.java,
        ))
        body.addView(Ui.verticalGap(host, 10))
        body.addView(card(
            host,
            "故障与修复",
            if (rep.failedRecent > 0) "最近 " + rep.failedRecent + " 次失败记录" else "最近无异常",
            if (rep.tier == SystemState.Tier.REPAIR) "进入处理" else "查看详情",
            SystemRecoveryActivity::class.java,
        ))
    }

    private fun card(host: Activity, title: String, main: String, sub: String, target: Class<*>): View {
        val box = Ui.card(host) {
            runCatching { startActivity(android.content.Intent(host, target)) }
        }
        val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(Ui.text(host, title, Ui.TEXT, 15f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(host, "›", Ui.TEXT3, 20f))
        box.addView(head)
        box.addView(Ui.text(host, main, Ui.TEXT, 14f))
        if (sub.isNotBlank()) box.addView(Ui.text(host, sub, Ui.TEXT2, 12f))
        return box
    }

    class Ring(ctx: Context) : View(ctx) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.parseColor(Ui.SURFACE2)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
        }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private var tier: SystemState.Tier = SystemState.Tier.OK
        private var busy = false

        fun setTier(t: SystemState.Tier) {
            tier = t
            invalidate()
        }

        fun setBusy(b: Boolean) {
            busy = b
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 12f
            canvas.drawCircle(cx, cy, r, track)
            arc.color = Color.parseColor(
                when (tier) {
                    SystemState.Tier.OK -> Ui.OK
                    SystemState.Tier.ATTENTION -> Ui.WARN
                    SystemState.Tier.REPAIR -> Ui.ERR
                }
            )
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f, false, arc)
            val label = when {
                busy -> "检查中"
                tier == SystemState.Tier.OK -> "正常"
                tier == SystemState.Tier.ATTENTION -> "需注意"
                else -> "需处理"
            }
            big.color = Color.parseColor(Ui.TEXT)
            big.textSize = 28f
            canvas.drawText(label, cx, cy + 6f, big)
            small.color = Color.parseColor(Ui.TEXT2)
            small.textSize = 12f
            canvas.drawText(if (busy) "请稍候" else "系统自检", cx, cy + 28f, small)
        }
    }
}
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
import lobos.services.supervise.RuntimeState

class SystemHomeActivity : Activity() {
    private lateinit var body: LinearLayout
    private var lastFreed = -1L
    private var dataDirBytes = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "系统管家",
            "组件、空间与修复都在这里",
            listOf(
                Triple("home", "首页", null),
                Triple("grid", "组件", SystemComponentsActivity::class.java),
                Triple("disk", "空间", SystemSpaceActivity::class.java),
                Triple("user", "我的", SystemAboutActivity::class.java),
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

    private fun runLine(phase: RuntimeState.Phase): String = when (phase) {
        RuntimeState.Phase.RUNNING -> "系统正在运行，点环可以停止"
        RuntimeState.Phase.BOOTING -> "正在启动，请稍候"
        RuntimeState.Phase.STOPPED -> "系统已停止，点环可以启动"
        RuntimeState.Phase.FAILED -> "启动没有成功，点环可以重试"
        RuntimeState.Phase.UNKNOWN -> "点环可以启动系统"
    }

    private fun showPower(phase: RuntimeState.Phase) {
        val host = this
        val running = phase == RuntimeState.Phase.RUNNING || phase == RuntimeState.Phase.BOOTING
        val title = if (running) "停止系统" else "启动系统"
        val message = if (running) {
            "停止后所有组件都会停下，已安装的东西不会删除。"
        } else {
            "启动会按已安装的组件拉起系统运行环境。"
        }
        android.app.AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ ->
                val action = if (running) RuntimeState.Phase.STOPPED else RuntimeState.Phase.BOOTING
                RuntimeState.mark(action, if (running) "正在停止" else "正在启动")
                runCatching {
                    val svc = android.content.Intent(host, lobos.services.host.OsHostService::class.java)
                    svc.action = if (running) "lobos.action.STOP_RUNTIME" else "lobos.action.START_RUNTIME"
                    host.startService(svc)
                }
                host.runOnUiThread {
                    host.render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
        if (Ui.expanded(host)) body.addView(twoPane(host)) else body.addView(onePane(host))
    }

    private fun twoPane(host: Activity): View {
        val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        buildStatusPane(host, left)
        buildCards(host, right)
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = Ui.dp(host, 16)
        })
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun onePane(host: Activity): View {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        buildStatusPane(host, col)
        buildCards(host, col)
        return col
    }

    private fun buildStatusPane(host: Activity, into: LinearLayout) {
        val saved = body
        body = into
        renderStatus()
        body = saved
    }

    private fun buildCards(host: Activity, into: LinearLayout) {
        val saved = body
        body = into
        renderCards()
        body = saved
    }

    private fun renderStatus() {
        val host = this
        val rep = SystemState.evaluate(host)
        val rep = SystemState.evaluate(host)

        val phase = lobos.services.supervise.RuntimeState.phase
        body.addView(Ring(host).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(host, 168), Ui.dp(host, 168)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(host, 6)
            }
            setPhase(phase)
            setOnClickListener { showPower(phase) }
        })

        val stateDetail = lobos.services.supervise.RuntimeState.detail
        body.addView(Ui.text(host, runLine(phase), Ui.TEXT2, 15f).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
        if (stateDetail.isNotBlank()) {
            body.addView(Ui.text(host, stateDetail, Ui.TEXT3, 13f).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
        }
        body.addView(Ui.verticalGap(host, 12))
        val powerLabel = when (phase) {
            RuntimeState.Phase.RUNNING -> "停止系统"
            RuntimeState.Phase.BOOTING -> "启动中…"
            else -> "启动系统"
        }
        body.addView(Ui.primaryButton(host, powerLabel) {
            if (phase != RuntimeState.Phase.BOOTING) showPower(phase)
        })
        body.addView(Ui.verticalGap(host, 10))
        body.addView(Ui.verticalGap(host, 8))

        body.addView(Ui.verticalGap(host, 10))

        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Ui.primaryButton(host, "一键体检") {
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
    }

    private fun renderCards() {
        val host = this
        val rep = SystemState.evaluate(host)
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
            strokeWidth = 14f
            color = Color.parseColor(Ui.SURFACE2)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
        }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private var phase: RuntimeState.Phase = RuntimeState.Phase.UNKNOWN
        private var angle = 0f
        private val ticker = object : Runnable {
            override fun run() {
                angle = (angle + 6f) % 360f
                invalidate()
                if (phase == RuntimeState.Phase.BOOTING) postDelayed(this, 16L)
            }
        }

        fun setPhase(p: RuntimeState.Phase) {
            val wasBooting = phase == RuntimeState.Phase.BOOTING
            phase = p
            if (p == RuntimeState.Phase.BOOTING && !wasBooting) {
                isClickable = true
                setOnClickListener { }
                post(ticker)
            } else if (p != RuntimeState.Phase.BOOTING) {
                removeCallbacks(ticker)
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(ticker)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f - 16f
            canvas.drawCircle(cx, cy, r, track)
            arc.color = Color.parseColor(
                when (phase) {
                    RuntimeState.Phase.RUNNING -> Ui.OK
                    RuntimeState.Phase.BOOTING -> Ui.ACCENT
                    RuntimeState.Phase.STOPPED -> Ui.TEXT3
                    RuntimeState.Phase.FAILED -> Ui.ERR
                    RuntimeState.Phase.UNKNOWN -> Ui.TEXT3
                }
            )
            val sweep = when (phase) {
                RuntimeState.Phase.BOOTING -> 70f
                RuntimeState.Phase.STOPPED -> 0f
                else -> 360f
            }
            val start = if (phase == RuntimeState.Phase.BOOTING) angle - 90f else -90f
            if (sweep > 0f) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, false, arc)
            big.color = Color.parseColor(Ui.TEXT)
            big.textSize = 30f
            canvas.drawText(
                when (phase) {
                    RuntimeState.Phase.RUNNING -> "运行中"
                    RuntimeState.Phase.BOOTING -> "启动中"
                    RuntimeState.Phase.STOPPED -> "已停止"
                    RuntimeState.Phase.FAILED -> "异常"
                    RuntimeState.Phase.UNKNOWN -> "未启动"
                },
                cx, cy + 10f, big,
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

}

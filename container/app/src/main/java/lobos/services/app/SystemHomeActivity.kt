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
import lobos.services.host.OsInit
import lobos.services.host.OsPhase
import lobos.services.reg.ResidencyStatus

class SystemHomeActivity : Activity() {
    private lateinit var container: LinearLayout
    private var lastFreed = -1L
    private var dataDirBytes = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (body, _) = Ui.scaffold(
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
        container = body
        render()
        measureDataDir()
    }

    override fun onResume() {
        super.onResume()
        render()
        measureDataDir()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
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
            container.removeAllViews()
            container.addView(Ui.text(this, "界面出错：" + (it.message ?: it.javaClass.simpleName), Ui.ERR, 13f))
        }
    }

    private fun renderInner() {
        container.removeAllViews()
        val wide = Ui.expanded(this)
        val status = buildStatus(this)
        val cards = buildCards(this)
        if (wide) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = Ui.dp(this@SystemHomeActivity, 12)
            })
            row.addView(cards, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            container.addView(row)
        } else {
            container.addView(status)
            container.addView(cards)
        }
    }

    private fun buildStatus(host: Activity): LinearLayout {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val snap = OsInit.snapshot(host)
        val phase = snap.phase
        val rep = SystemState.evaluate(host)

        col.addView(Ring(host).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.dp(host, 150), Ui.dp(host, 150)).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                topMargin = Ui.dp(host, 4)
            }
            setPhase(phase)
            setOnClickListener { showPower(host, phase) }
        })

        val residency = runCatching {
            val f = java.io.File(lobos.kernel.layout.SystemDirs.run(host), "residency.json")
            if (f.isFile) org.json.JSONObject(f.readText()) else ResidencyStatus.snapshot()
        }.getOrNull()
        val line = Ui.text(host, runLine(phase), Ui.TEXT2, 14f).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        col.addView(line)
        val detail = buildString {
            snap.interrupted?.let { append(it) }
            residency?.let { r ->
                if (isNotEmpty()) append("\n")
                append(r.optInt("installedPrograms", 0)).append(" 个已装 · ")
                    .append(r.optInt("programsRunning", 0)).append(" 个在运行")
                if (r.optBoolean("frozen", false)) append(" · 已冻结")
            }
        }
        if (detail.isNotBlank()) {
            col.addView(Ui.text(host, detail, Ui.TEXT3, 11f).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            })
        }
        col.addView(Ui.verticalGap(host, 10))

        val powerLabel = when (phase) {
            OsPhase.RUNNING -> "停止系统"
            OsPhase.BOOTING -> "启动中…"
            else -> "启动系统"
        }
        col.addView(Ui.primaryButton(host, powerLabel) {
            if (phase != OsPhase.BOOTING) showPower(host, phase)
        })
        col.addView(Ui.verticalGap(host, 8))

        val actions = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Ui.primaryButton(host, "一键体检") {
            runCatching { startActivity(android.content.Intent(host, SystemCheckActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = Ui.dp(host, 8)
        })
        actions.addView(Ui.outlineButton(host, "清理空间") {
            thread(name = "lobos-clean") {
                val r = Cleanup.run(host)
                runOnUiThread { lastFreed = r.first; render() }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(actions)
        if (lastFreed >= 0) {
            col.addView(Ui.text(host, "已清理 " + InstallGuard.human(lastFreed), Ui.OK, 12f))
        }
        return col
    }

    private fun buildCards(host: Activity): LinearLayout {
        val col = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val rep = SystemState.evaluate(host)
        val bytes = if (dataDirBytes >= 0) dataDirBytes else rep.usedBytes
        col.addView(Ui.text(host, "概况", Ui.TEXT2, 13f, bold = true))
        col.addView(Ui.verticalGap(host, 6))
        col.addView(infoCard(host, "空间",
            "占用 " + InstallGuard.human(bytes) + " · 可用 " + InstallGuard.human(rep.freeBytes),
            if (rep.reclaimableBytes > 0) "可清理 " + InstallGuard.human(rep.reclaimableBytes) else "无需清理",
            SystemSpaceActivity::class.java))
        col.addView(Ui.verticalGap(host, 8))
        col.addView(infoCard(host, "组件",
            "已装 " + rep.installedCount + " 个",
            if (rep.upgradableCount > 0) rep.upgradableCount.toString() + " 个可更新"
            else "查看状态",
            SystemComponentsActivity::class.java))
        col.addView(Ui.verticalGap(host, 8))
        col.addView(infoCard(host, "故障与修复",
            if (rep.failedRecent > 0) "最近 24 小时 " + rep.failedRecent + " 次失败" else "最近无异常",
            if (rep.tier == SystemState.Tier.REPAIR) "进入处理" else "查看详情",
            SystemRecoveryActivity::class.java))
        return col
    }

    private fun infoCard(host: Activity, title: String, main: String, sub: String, target: Class<*>): View {
        val box = Ui.card(host) { runCatching { startActivity(android.content.Intent(host, target)) } }
        box.addView(Ui.text(host, title, Ui.TEXT, 15f, bold = true))
        box.addView(Ui.text(host, main, Ui.TEXT2, 13f))
        if (sub.isNotBlank()) box.addView(Ui.text(host, sub, Ui.TEXT3, 12f))
        return box
    }

    private fun runLine(phase: OsPhase): String = when (phase) {
        OsPhase.RUNNING -> "系统正在运行，点环可以停止"
        OsPhase.BOOTING -> "正在启动，请稍候"
        OsPhase.DEGRADED -> "系统已降级，点环可以重新启动"
        OsPhase.STOPPING -> "正在停止，请稍候"
    }

    private fun showPower(host: Activity, phase: OsPhase) {
        val running = phase == OsPhase.RUNNING || phase == OsPhase.BOOTING
        val title = if (running) "停止系统" else "启动系统"
        val message = if (running) "停止后所有组件都会停下，已安装的东西不会删除。"
        else "启动会按已安装的组件拉起系统运行环境。"
        android.app.AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(title) { _, _ ->
                runCatching {
                    val svc = android.content.Intent(host, lobos.services.host.OsHostService::class.java)
                    svc.action = if (running) lobos.services.supervise.InstanceHost.ACTION_STOP_RUNTIME
                                 else lobos.services.supervise.InstanceHost.ACTION_START_RUNTIME
                    host.startService(svc)
                }
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class Ring(ctx: Context) : View(ctx) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 12f; color = Color.parseColor(Ui.SURFACE2)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND
        }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        private var phase = OsPhase.BOOTING

        fun setPhase(p: OsPhase) { phase = p; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = minOf(width, height) / 2f - 12f
            canvas.drawCircle(cx, cy, r, track)
            arc.color = Color.parseColor(when (phase) {
                OsPhase.RUNNING -> Ui.OK
                OsPhase.BOOTING -> Ui.ACCENT
                OsPhase.DEGRADED -> Ui.WARN
                OsPhase.STOPPING -> Ui.TEXT3
            })
            val sweep = when (phase) {
                OsPhase.STOPPING -> 0f
                else -> 360f
            }
            if (sweep > 0f) canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, sweep, false, arc)
            big.color = Color.parseColor(Ui.TEXT)
            big.textSize = 30f
            canvas.drawText(phase.label, cx, cy + 10f, big)
        }
    }
}
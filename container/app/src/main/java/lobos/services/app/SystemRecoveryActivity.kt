package lobos.services.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread
import lobos.kernel.layout.LdConfig
import lobos.services.log.RuntimeDiagnostics
import lobos.services.reg.ProgramIndex
import lobos.services.supply.InstallGuard

class SystemRecoveryActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "故障与恢复"
            setTextColor(Color.parseColor("#e6edf3"))
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        val host = this
        body.removeAllViews()
        val f = Facts.of(this)
        body.addView(line(f.headline, if (f.ok) "#3fb950" else "#d29922", big = true, bold = true))
        body.addView(line(f.headlineWhy, "#8b949e"))
        body.addView(space())
        body.addView(line("系统件", "#e6edf3", bold = true))
        body.addView(line(f.piecesLine, "#8b949e"))
        body.addView(line("空间", "#e6edf3", bold = true))
        body.addView(line(f.spaceLine, "#8b949e"))
        body.addView(space())
        body.addView(line("最近一次失败", "#e6edf3", bold = true))
        body.addView(line(if (f.lastFail.isBlank()) "最近没有失败记录" else f.lastFail, "#8b949e"))
        body.addView(space())

        val fix = Button(this).apply {
            text = "一键修复（重建库链接）"
            setOnClickListener {
                isEnabled = false
                thread(name = "lobos-repair") {
                    runCatching { LdConfig.run(host) }
                    runOnUiThread {
                        isEnabled = true
                        render()
                    }
                }
            }
        }
        body.addView(fix)
        body.addView(line("修复只做一件可判定的事：按登记重建 usr/lib 的库链接。它不会改组件内容。", "#8b949e"))
    }

    private fun line(text: String, color: String, big: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, text, Ui.TEXT2, if (big) 17f else 14f, bold)

    private fun space(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class Facts(
        val ok: Boolean,
        val headline: String,
        val headlineWhy: String,
        val piecesLine: String,
        val spaceLine: String,
        val lastFail: String,
    ) {
        companion object {
            fun of(ctx: Context): Facts {
                val ledger = InstallGuard.all(ctx)
                val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
                val pieces = index.size
                val unknown = index.count { !ledger.containsKey(it.id) }
                val free = InstallGuard.freeBytes(ctx.filesDir)
                val ok = pieces > 0
                val headline = if (ok) "系统可运行" else "系统还不完整"
                val why = if (ok) {
                    "已登记 " + pieces + " 个组件" + (if (unknown > 0) "（其中 " + unknown + " 个没有安装记录）" else "")
                } else {
                    "还没有登记任何组件，先装基础组件"
                }
                val piecesLine = if (index.isEmpty()) "无" else
                    index.joinToString("、") { it.id + "@" + it.version }
                val spaceLine = "可用 " + InstallGuard.human(free)
                val lastFail = runCatching {
                    RuntimeDiagnostics.events(ctx, 200)
                        .filter { it.level == RuntimeDiagnostics.Level.FAIL }
                        .maxByOrNull { it.atMs }
                        ?.let { it.stage + "：" + it.message + (if (it.detail.isBlank()) "" else "；" + it.detail) }
                        .orEmpty()
                }.getOrDefault("")
                return Facts(ok, headline, why, piecesLine, spaceLine, lastFail)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }

}
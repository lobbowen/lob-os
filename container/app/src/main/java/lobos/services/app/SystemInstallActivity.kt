package lobos.services.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallProgress
import lobos.services.supply.InstallGuard
import lobos.services.supply.PackageInstaller

class SystemInstallActivity : Activity() {
    private lateinit var body: LinearLayout
    private var target: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "安装"
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
        body.removeAllViews()
        val plan = Plan.of(this, target)
        body.addView(line(plan.title, "#e6edf3", big = true, bold = true))
        body.addView(line(plan.summary, "#8b949e"))
        if (plan.depsLine.isNotBlank()) body.addView(line(plan.depsLine, "#d29922"))
        body.addView(line(plan.spaceLine, "#8b949e"))
        body.addView(space())

        val start = Button(this).apply {
            text = "开始安装"
            isEnabled = plan.canStart
            setOnClickListener { beginInstall(plan.name) }
        }
        body.addView(start)
        body.addView(space())

        val snap = InstallProgress.current()
        for (st in listOf(
            InstallProgress.Stage.DOWNLOAD,
            InstallProgress.Stage.VERIFY,
            InstallProgress.Stage.LAND,
            InstallProgress.Stage.LINK,
        )) {
            val state = when {
                snap.stage == InstallProgress.Stage.FAILED -> "未完成"
                snap.stage == InstallProgress.Stage.DONE -> "完成"
                snap.stage.ordinal > st.ordinal -> "完成"
                snap.stage == st -> "进行中"
                else -> "等待"
            }
            body.addView(line(InstallProgress.label(st) + "：" + state, "#e6edf3"))
        }
        if (snap.stage == InstallProgress.Stage.FAILED) {
            body.addView(line("失败原因：" + snap.detail, "#f85149"))
            body.addView(line("下一步：先解决上面这条原因，再点一次开始安装。", "#8b949e"))
        }
        if (snap.stage == InstallProgress.Stage.DONE) {
            body.addView(line("已安装：" + snap.id + " " + snap.detail, "#3fb950"))
        }
    }

    private fun beginInstall(name: String) {
        if (name.isBlank()) return
        thread(name = "lobos-install") {
            runCatching { PackageInstaller.install(this, name, null) }
                .onFailure { InstallProgress.failed(name, it.message ?: it.javaClass.simpleName) }
            runOnUiThread { render() }
        }
        render()
    }

    private fun line(text: String, color: String, big: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, text, Ui.TEXT2, if (big) 17f else 14f, bold)

    private fun space(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class Plan(
        val name: String,
        val title: String,
        val summary: String,
        val depsLine: String,
        val spaceLine: String,
        val canStart: Boolean,
    ) {
        companion object {
            fun of(ctx: Context, name: String): Plan {
                if (name.isBlank()) {
                    return Plan("", "没有选中要装的组件", "从组件列表里选一个再进来。", "", "", false)
                }
                val entry: JSONObject? = runCatching { CatalogClient.entryFor(ctx, name) }.getOrNull()
                if (entry == null) {
                    return Plan(name, name, "清单里没有这个组件。", "", "先刷新一次可用清单。", false)
                }
                val ver = entry.optString("version")
                val size = entry.optLong("size", 0L)
                val deps = entry.optJSONArray("deps")
                val req = entry.optJSONObject("requires")
                val reqName = req?.optString("name", "").orEmpty()
                val summary = buildString {
                    append("版本 ").append(if (ver.isBlank()) "未知" else ver)
                    append(" · ").append(if (size > 0) InstallGuard.human(size) else "大小未知")
                    if (deps != null && deps.length() > 0) {
                        append(" · 包含 ").append(deps.length()).append(" 个部件")
                    }
                }
                val depsLine = if (reqName.isNotBlank()) {
                    "需要先有 " + reqName + "；缺了会自动带上再装这个。"
                } else ""
                val need = if (size > 0) size * 3 else 0L
                val free = InstallGuard.freeBytes(ctx.filesDir)
                val spaceLine = if (need > 0 && free > 0) {
                    "需要约 " + InstallGuard.human(need) + " 可用空间，当前可用 " + InstallGuard.human(free)
                } else {
                    "空间未知"
                }
                val canStart = free <= 0 || need <= 0 || free >= need
                return Plan(name, name, summary, depsLine, spaceLine, canStart)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "pieceId"

        fun intentOf(ctx: Context, name: String): Intent =
            Intent(ctx, SystemInstallActivity::class.java).putExtra(EXTRA_NAME, name)
    }
}
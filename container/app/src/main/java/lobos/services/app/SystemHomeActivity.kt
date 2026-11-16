package lobos.services.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import lobos.services.reg.ProgramIndex
import lobos.services.supply.InstallGuard

class SystemHomeActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0d1117"))
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "系统状态"
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

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val facts = gather(this)
        body.addView(line(facts.verdict, if (facts.ok) "#3fb950" else "#d29922", big = true))
        body.addView(line(facts.verdictWhy, "#8b949e"))
        body.addView(gap())
        body.addView(card("已装组件", facts.installedCount.toString() + " 个"))
        body.addView(card("占用空间", InstallGuard.human(facts.usedBytes)))
        body.addView(card("位置", facts.root))
        body.addView(gap())
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(Button(this).apply {
            text = "组件"
            setOnClickListener {
                runCatching {
                    startActivity(android.content.Intent(this@SystemHomeActivity, SystemComponentsActivity::class.java))
                }
            }
        })
        body.addView(nav)
        body.addView(line("组件明细", "#e6edf3", big = true))
        if (facts.rows.isEmpty()) {
            body.addView(line("还没有安装任何组件", "#8b949e"))
        } else {
            for (r in facts.rows) body.addView(row(r))
        }
    }

    private fun row(r: Row): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#161b22"))
        }
        box.addView(line(r.id, "#e6edf3", bold = true))
        box.addView(line(r.detail, "#8b949e"))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        box.layoutParams = lp
        return box
    }

    private fun card(title: String, value: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#161b22"))
        }
        box.addView(line(title, "#8b949e"))
        box.addView(line(value, "#e6edf3", big = true))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
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

    private fun gap(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class Row(val id: String, val detail: String)

    data class Facts(
        val ok: Boolean,
        val verdict: String,
        val verdictWhy: String,
        val installedCount: Int,
        val usedBytes: Long,
        val root: String,
        val rows: List<Row>,
    )

    companion object {
        fun gather(ctx: Context): Facts {
            val ledger = InstallGuard.all(ctx)
            val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            val rows = LinkedHashMap<String, Row>()
            for (e in index) {
                val id = e.id
                val meta = ledger[id]
                val ver = meta?.optString("version")?.takeIf { it.isNotBlank() }
                    ?: e.version.takeIf { it.isNotBlank() }
                    ?: "未知版本"
                val bytes = meta?.optLong("bytes", 0L) ?: 0L
                val detail = "v" + ver + (if (bytes > 0) " · " + InstallGuard.human(bytes) else "")
                rows[id] = Row(id, detail)
            }
            for ((id, meta) in ledger) {
                if (rows.containsKey(id)) continue
                val ver = meta.optString("version").takeIf { it.isNotBlank() } ?: "未知版本"
                rows[id] = Row(id, "v" + ver + " · 未在索引里")
            }
            val used = rows.keys.sumOf { ledger[it]?.optLong("bytes", 0L) ?: 0L }
            val root = runCatching { lobos.kernel.layout.SystemDirs.opt(ctx).absolutePath }
                .getOrDefault(ctx.filesDir.absolutePath)
            val ok = rows.isNotEmpty()
            val verdict = if (ok) "运行正常" else "尚未安装组件"
            val why = if (ok) {
                "系统件与组件已登记 " + rows.size + " 个"
            } else {
                "先安装基础组件，系统才能提供服务"
            }
            return Facts(ok, verdict, why, rows.size, used, root, rows.values.toList())
        }
    }
}
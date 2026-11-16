package lobos.services.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import lobos.services.reg.ProgramIndex
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallGuard

class SystemComponentsActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "组件",
            "已安装与可安装",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("grid", "组件", null),
                Triple("disk", "空间", SystemSpaceActivity::class.java),
                Triple("user", "我的", SystemAboutActivity::class.java),
            ),
            1,
        )
        body = b
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val host = this
        body.removeAllViews()
        val facts = gather(host)

        header("已安装", facts.installed.size, Ui.OK)
        if (facts.installed.isEmpty()) {
            emptyLine("还没有安装任何组件。下面的可安装里挑一个开始。")
        } else {
            for (r in facts.installed) body.addView(row(host, r))
        }
        body.addView(Ui.verticalGap(host, 14))

        header("可安装", facts.available.size, Ui.ACCENT)
        if (facts.available.isEmpty()) {
            emptyLine("还没有取到可安装清单。连上网络后刷新一次清单。")
        } else {
            for (r in facts.available) body.addView(row(host, r))
        }
    }

    private fun header(title: String, count: Int, color: String) {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(Ui.text(this, title, color, 15f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(this, count.toString(), Ui.TEXT3, 13f))
        body.addView(head)
        body.addView(Ui.verticalGap(this, 6))
    }

    private fun emptyLine(text: String) {
        val box = Ui.card(this, null)
        box.addView(Ui.text(this, text, Ui.TEXT2, 13f))
        body.addView(box)
    }

    private fun row(host: Activity, r: Row): View {
        val box = Ui.card(host) {
            runCatching { startActivity(SystemComponentDetailActivity.intentOf(host, r.name)) }
        }
        val top = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Ui.icon(host, if (r.installed) Ui.Icon.GRID else Ui.Icon.DISK,
            if (r.installed) Ui.OK else Ui.ACCENT, 22))
        val mid = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(host, 10), 0, Ui.dp(host, 8), 0)
        }
        mid.addView(Ui.text(host, r.name, Ui.TEXT, 15f, bold = true))
        if (r.sub.isNotBlank()) mid.addView(Ui.text(host, r.sub, Ui.TEXT2, 12f))
        top.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.text(host, r.status, if (r.bad) Ui.WARN else Ui.TEXT3, 12f))
        top.addView(Ui.text(host, " ›", Ui.TEXT3, 18f))
        box.addView(top)
        return box
    }

    data class Row(
        val name: String,
        val sub: String,
        val status: String,
        val bad: Boolean,
        val installed: Boolean,
    )

    data class Facts(val installed: List<Row>, val available: List<Row>)

    companion object {
        fun gather(ctx: Context): Facts {
            val ledger = InstallGuard.all(ctx)
            val installed = ArrayList<Row>()
            val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            for (e in index) {
                val meta = ledger[e.id]
                val ver = meta?.optString("version")?.takeIf { it.isNotBlank() }
                    ?: e.version.takeIf { it.isNotBlank() } ?: "未知"
                val bytes = meta?.optLong("bytes", 0L) ?: 0L
                val sub = buildString {
                    append("v").append(ver)
                    if (bytes > 0) append(" · 已占 ").append(InstallGuard.human(bytes))
                }
                val noRecord = meta == null
                installed.add(Row(e.id, sub, if (noRecord) "缺记录" else "", noRecord, true))
            }
            for ((id, meta) in ledger) {
                if (index.any { it.id == id }) continue
                installed.add(Row(id, "v" + meta.optString("version", "未知"), "不在索引里", true, true))
            }
            val available = ArrayList<Row>()
            val entries: JSONArray = runCatching { CatalogClient.entries(ctx) }.getOrDefault(JSONArray())
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val name = e.optString("name")
                if (name.isBlank()) continue
                if (installed.any { it.name == name }) continue
                val ver = e.optString("version")
                val size = e.optLong("size", 0L)
                val req = e.optJSONObject("requires")?.optString("name", "").orEmpty()
                val sub = buildString {
                    append("v").append(if (ver.isBlank()) "未知" else ver)
                    if (size > 0) append(" · 下载 ").append(InstallGuard.human(size))
                    if (req.isNotBlank()) append(" · 需要 ").append(req)
                }
                val up = e.optBoolean("upgradable", false)
                val heavy = size >= 200L * 1024 * 1024
                val status = when {
                    heavy -> "体积很大"
                    up -> "可更新"
                    else -> ""
                }
                available.add(Row(name, sub, status, heavy || up, false))
            }
            return Facts(installed, available)
        }
    }
}
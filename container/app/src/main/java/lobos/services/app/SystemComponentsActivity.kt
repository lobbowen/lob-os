package lobos.services.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
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
                Triple("◉", "首页", SystemComponentsActivity::class.java),
                Triple("▣", "组件", null),
                Triple("▤", "空间", SystemSpaceActivity::class.java),
                Triple("☰", "我的", SystemAboutActivity::class.java),
            ),
            1,
        )
        body = b
        render()
    }


    private fun render() {
        body.removeAllViews()
        val facts = gather(this)
        section("已安装", facts.installed, "#3fb950")
        section("可安装", facts.available, "#58a6ff")
        if (facts.available.isEmpty()) {
            body.addView(line("还没有取到可安装清单。连上网络后刷新一次清单，这里就会出现可装的东西。", "#8b949e"))
        }
    }

    private fun section(title: String, rows: List<Row>, color: String) {
        body.addView(line(title + "（" + rows.size + "）", color, big = true, bold = true))
        if (rows.isEmpty()) {
            body.addView(line("没有", "#8b949e"))
        } else {
            for (r in rows) body.addView(card(r))
        }
        body.addView(space())
    }

    private fun card(r: Row): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#161b22"))
        }
        if (r.installable) {
            box.setOnClickListener {
                runCatching {
                    startActivity(SystemInstallActivity.intentOf(this, r.name))
                }
            }
        }
        box.addView(line(r.name + "  " + r.version, "#e6edf3", bold = true))
        if (r.detail.isNotBlank()) box.addView(line(r.detail, "#8b949e"))
        if (r.needs.isNotBlank()) box.addView(line(r.needs, "#d29922"))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        box.layoutParams = lp
        return box
    }

    private fun line(text: String, color: String, big: Boolean = false, bold: Boolean = false): TextView =
        Ui.text(this, text, Ui.TEXT2, if (big) 17f else 14f, bold)

    private fun space(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    data class Row(
        val name: String,
        val version: String,
        val detail: String,
        val needs: String,
        val installable: Boolean = false,
    )

    data class Facts(val installed: List<Row>, val available: List<Row>)

    companion object {
        fun gather(ctx: Context): Facts {
            val ledger = InstallGuard.all(ctx)
            val installed = LinkedHashMap<String, Row>()
            val index = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            for (e in index) {
                val meta = ledger[e.id]
                val ver = meta?.optString("version")?.takeIf { it.isNotBlank() }
                    ?: e.version.takeIf { it.isNotBlank() } ?: "未知"
                val bytes = meta?.optLong("bytes", 0L) ?: 0L
                val detail = buildString {
                    append("已安装")
                    if (bytes > 0) append(" · ").append(InstallGuard.human(bytes))
                    append(" · 路径 ").append(e.stateDir)
                }
                installed[e.id] = Row(e.id, "v" + ver, detail, "")
            }
            for ((id, meta) in ledger) {
                if (installed.containsKey(id)) continue
                installed[id] = Row(id, "v" + meta.optString("version", "未知"), "已安装（未在索引里）", "")
            }

            val available = ArrayList<Row>()
            val entries: JSONArray = runCatching { CatalogClient.entries(ctx) }.getOrDefault(JSONArray())
            for (i in 0 until entries.length()) {
                val e = entries.optJSONObject(i) ?: continue
                val name = e.optString("name")
                if (name.isBlank()) continue
                if (installed.containsKey(name)) continue
                val ver = e.optString("version")
                val size = e.optLong("size", 0L)
                val detail = buildString {
                    if (size > 0) append(InstallGuard.human(size)) else append("大小未知")
                    val deps = e.optJSONArray("deps")
                    if (deps != null && deps.length() > 0) {
                        append(" · 包含 ").append(deps.length()).append(" 个部件")
                    }
                }
                val needs = buildString {
                    val req = e.optJSONObject("requires")
                    val rn = req?.optString("name", "").orEmpty()
                    if (rn.isNotBlank()) append("需要先有 ").append(rn)
                    if (e.optBoolean("upgradable", false)) {
                        if (isNotEmpty()) append(" · ")
                        append("已装有新版")
                    }
                }
                available.add(Row(name, "v" + ver, detail, needs, installable = true))
            }
            return Facts(installed.values.toList(), available)
        }
    }
}
package lobos.services.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import lobos.services.reg.ProgramIndex
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallGuard

class SystemStoreActivity : Activity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "应用商店",
            "选装运行时与工具",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("store", "商店", null),
                Triple("grid", "组件", SystemComponentsActivity::class.java),
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
        val apps = gather(host)

        body.addView(Ui.text(host, "全部应用", Ui.TEXT, 15f, bold = true))
        body.addView(Ui.verticalGap(host, 8))
        if (apps.isEmpty()) {
            val box = Ui.card(host)
            box.addView(Ui.text(host, "还没有取到应用清单。下拉刷新或检查网络后重试。", Ui.TEXT2, 13f))
            body.addView(box)
            body.addView(Ui.verticalGap(host, 12))
            val refresh = android.widget.Button(host).apply {
                text = "刷新清单"
                setOnClickListener {
                    Thread { runCatching { CatalogClient.refresh(host, true) }; runOnUiThread { render() } }.start()
                }
            }
            Ui.restyleButtons(refresh)
            body.addView(refresh)
            return
        }
        for (app in apps) body.addView(row(host, app))
    }

    private fun row(host: Activity, app: App): View {
        val box = Ui.card(host) {
            runCatching {
                startActivity(SystemInstallActivity.intentOf(host, app.name))
            }
        }
        val top = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Ui.icon(host, Ui.Icon.SHOP, if (app.installed) Ui.OK else Ui.ACCENT, 22))
        val mid = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(host, 10), 0, Ui.dp(host, 8), 0)
        }
        mid.addView(Ui.text(host, app.name, Ui.TEXT, 15f, bold = true))
        mid.addView(Ui.text(host, app.sub, Ui.TEXT2, 12f))
        top.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.text(host, app.state, if (app.installed) Ui.OK else Ui.TEXT3, 12f))
        top.addView(Ui.text(host, " ›", Ui.TEXT3, 18f))
        box.addView(top)
        return box
    }

    data class App(val name: String, val sub: String, val state: String, val installed: Boolean)

    companion object {
        fun gather(ctx: Context): List<App> {
            val installed = runCatching { ProgramIndex.all(ctx).map { it.id } }.getOrDefault(emptyList()).toSet()
            val arr: JSONArray = runCatching { CatalogClient.entries(ctx) }.getOrDefault(JSONArray())
            val out = ArrayList<App>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name").ifBlank { e.optString("id") }
                if (name.isBlank()) continue
                val versions = e.optJSONArray("versions")
                val count = versions?.length() ?: 0
                val version = if (count > 0) versions?.optJSONObject(count - 1)?.optString("version", "") else e.optString("version", "")
                val size = e.optLong("size", 0L)
                val sub = buildString {
                    append("v").append(version.ifBlank { "?" })
                    if (count > 1) append(" · ").append(count).append(" 个版本")
                    if (size > 0) append(" · ").append(InstallGuard.human(size))
                }
                out.add(App(name, sub, if (installed.contains(name)) "已安装" else "安装", installed.contains(name)))
            }
            return out.sortedWith(compareBy({ it.installed }, { it.name }))
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        render()
    }
}

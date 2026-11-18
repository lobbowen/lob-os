package lobos.services.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread
import lobos.services.reg.ProgramIndex
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallProgress
import lobos.services.supply.InstallGuard
import lobos.services.supply.PackageInstaller

class SystemInstallActivity : Activity() {
    private lateinit var body: LinearLayout
    private var target: String = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val (b, _) = Ui.scaffold(
            this,
            target.ifBlank { "应用" },
            "版本与安装",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("store", "商店", SystemStoreActivity::class.java),
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
        val plan = Plan.of(host, target)

        val hero = Ui.card(host)
        hero.addView(Ui.icon(host, Ui.Icon.SHOP, Ui.ACCENT, 30))
        hero.addView(Ui.text(host, plan.title, Ui.TEXT, 19f, bold = true))
        hero.addView(Ui.text(host, plan.summary, Ui.TEXT2, 13f))
        body.addView(hero)
        body.addView(Ui.verticalGap(host, 12))

        if (plan.versions.isNotEmpty()) {
            val vcard = Ui.card(host)
            vcard.addView(Ui.text(host, "可选版本", Ui.TEXT, 14f, bold = true))
            for (v in plan.versions) {
                vcard.addView(Ui.text(host, "  · " + v, Ui.TEXT2, 13f))
            }
            body.addView(vcard)
            body.addView(Ui.verticalGap(host, 12))
        }

        if (plan.depsLine.isNotBlank()) {
            val dcard = Ui.card(host)
            dcard.addView(Ui.text(host, "依赖", Ui.TEXT, 14f, bold = true))
            dcard.addView(Ui.text(host, plan.depsLine, "#d29922", 13f))
            body.addView(dcard)
            body.addView(Ui.verticalGap(host, 12))
        }

        val scard = Ui.card(host)
        scard.addView(Ui.text(host, "空间", Ui.TEXT, 14f, bold = true))
        scard.addView(Ui.text(host, plan.spaceLine, Ui.TEXT2, 13f))
        body.addView(scard)
        body.addView(Ui.verticalGap(host, 14))

        when {
            plan.installed -> {
                body.addView(Ui.primaryButton(host, "已安装") {
                    Toast.makeText(host, plan.title + " 已就位", Toast.LENGTH_SHORT).show()
                })
                body.addView(Ui.text(host, "当前 " + plan.currentVersion, Ui.OK, 13f))
            }
            !plan.canStart -> {
                body.addView(Ui.primaryButton(host, "空间不足") { })
            }
            busy -> {
                body.addView(Ui.primaryButton(host, "安装中…") { })
                body.addView(Ui.verticalGap(host, 10))
                val snap = InstallProgress.current()
                for (st in listOf(
                    InstallProgress.Stage.DOWNLOAD,
                    InstallProgress.Stage.VERIFY,
                    InstallProgress.Stage.LAND,
                    InstallProgress.Stage.LINK,
                )) {
                    val state = when {
                        snap.stage.ordinal > st.ordinal -> "完成"
                        snap.stage == st -> "进行中"
                        else -> "等待"
                    }
                    body.addView(Ui.text(host, InstallProgress.label(st) + "  " + state, Ui.TEXT2, 13f))
                }
            }
            else -> {
                body.addView(Ui.primaryButton(host, "安装") { beginInstall(plan.name) })
            }
        }

        val snap = InstallProgress.current()
        if (snap.stage == InstallProgress.Stage.FAILED && snap.id == plan.name) {
            body.addView(Ui.verticalGap(host, 10))
            body.addView(Ui.text(host, "失败：" + snap.detail, "#f85149", 13f))
        }
    }

    private fun beginInstall(name: String) {
        if (name.isBlank()) return
        busy = true
        render()
        thread(name = "lobos-install") {
            runCatching { PackageInstaller.install(this, name, null) }
                .onFailure { InstallProgress.failed(name, it.message ?: it.javaClass.simpleName) }
            runOnUiThread { busy = false; render() }
        }
    }

    data class Plan(
        val name: String,
        val title: String,
        val summary: String,
        val depsLine: String,
        val spaceLine: String,
        val versions: List<String>,
        val canStart: Boolean,
        val installed: Boolean,
        val currentVersion: String,
    ) {
        companion object {
            fun of(ctx: Context, name: String): Plan {
                if (name.isBlank()) {
                    return Plan("", "没有选中应用", "从商店里选一个再进来。", "", "", emptyList(), false, false, "")
                }
                val entry: JSONObject? = runCatching { CatalogClient.entryFor(ctx, name) }.getOrNull()
                if (entry == null) {
                    return Plan(name, name, "清单里没有这个应用。", "", "先刷新一次可用清单。", emptyList(), false, false, "")
                }
                val versions = ArrayList<String>()
                entry.optJSONArray("versions")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("version", "")?.takeIf { it.isNotBlank() }?.let { versions.add(it) }
                    }
                }
                val ver = versions.lastOrNull().orEmpty().ifBlank { entry.optString("version", "") }
                val size = entry.optLong("size", 0L)
                val summary = buildString {
                    append("版本 ").append(ver.ifBlank { "未知" })
                    if (size > 0) append("  ·  ").append(InstallGuard.human(size))
                }
                val deps = entry.optJSONArray("deps")
                val req = entry.optJSONObject("requires")?.optString("name", "").orEmpty()
                val depsLine = buildString {
                    if (req.isNotBlank()) append("需要先有 ").append(req).append("；缺了会自动带上。")
                    if (deps != null && deps.length() > 0) {
                        if (isNotBlank()) append("\n")
                        append("包含 ").append(deps.length()).append(" 个部件")
                    }
                }
                val need = if (size > 0) size * 3 else 0L
                val free = InstallGuard.freeBytes(ctx.filesDir)
                val spaceLine = if (need > 0 && free > 0) {
                    "需要约 " + InstallGuard.human(need) + " 可用空间，当前可用 " + InstallGuard.human(free)
                } else "空间未知"
                val canStart = free <= 0 || need <= 0 || free >= need
                val idx = runCatching { ProgramIndex.all(ctx).firstOrNull { it.id == name } }.getOrNull()
                val installed = idx != null
                val currentVersion = idx?.version.orEmpty().ifBlank { "—" }
                return Plan(name, name, summary, depsLine, spaceLine, versions, canStart, installed, currentVersion)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "pieceId"

        fun intentOf(ctx: Context, name: String): Intent =
            Intent(ctx, SystemInstallActivity::class.java).putExtra(EXTRA_NAME, name)
    }
}

package lobos.services.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import kotlin.concurrent.thread
import org.json.JSONObject
import lobos.services.reg.ProgramIndex
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallGuard
import lobos.services.supply.InstallProgress
import lobos.services.supply.PackageInstaller

class SystemComponentDetailActivity : Activity() {
    private lateinit var body: LinearLayout
    private var name: String = ""
    private var busy = false
    private var measuredBytes = -1L
    private var measuring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val (b, _) = Ui.scaffold(
            this,
            name.ifBlank { "组件" },
            "版本、依赖与操作",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
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
        if (name.isBlank()) {
            body.addView(Ui.text(host, "没有选中组件", Ui.TEXT2, 14f))
            return
        }
        val facts = gather(host, name)
        if (facts.installed && !measuring && measuredBytes < 0) measureInstalled(host, name)
        body.addView(Ui.text(host, facts.name, Ui.TEXT, 20f, bold = true))
        body.addView(Ui.text(host, facts.stateLine, if (facts.installed) Ui.OK else Ui.ACCENT, 13f, bold = true))
        body.addView(Ui.verticalGap(host, 12))

        body.addView(infoRow("版本", facts.version))
        body.addView(infoRow("下载大小", facts.downloadSize))
        body.addView(infoRow("安装后大小",
            if (measuredBytes > 0) InstallGuard.human(measuredBytes) else facts.installedSize))
        body.addView(infoRow("依赖", facts.requires))
        body.addView(infoRow("安装时间", facts.installedAt))
        body.addView(infoRow("位置", facts.path))
        if (facts.heavy) {
            body.addView(Ui.text(host, "这一件体积很大，装之前先确认有足够空间；不需要就别装。", Ui.WARN, 13f))
        }
        body.addView(Ui.verticalGap(host, 16))

        val snap = InstallProgress.current()
        if (busy || (snap.id == name && snap.stage != InstallProgress.Stage.DONE && snap.stage != InstallProgress.Stage.IDLE)) {
            body.addView(Ui.text(host, "正在安装：", Ui.TEXT, 14f, bold = true))
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
                body.addView(Ui.text(host, "· " + InstallProgress.label(st) + " " + state, Ui.TEXT2, 13f))
            }
            if (snap.stage == InstallProgress.Stage.FAILED) {
                body.addView(Ui.text(host, "失败原因：" + snap.detail, Ui.ERR, 13f))
            }
            body.addView(Ui.verticalGap(host, 12))
        }

        if (!facts.installed) {
            body.addView(Ui.primaryButton(host, "安装") { startInstall(host, name) })
        } else {
            body.addView(Ui.primaryButton(host, if (busy) "处理中…" else "更新到最新") { startInstall(host, name) })
            body.addView(Ui.verticalGap(host, 8))
            body.addView(Ui.outlineButton(host, "回退到出厂版本") {
                thread(name = "lobos-rollback") {
                    val r = runCatching { lobos.services.supply.PieceUpdater.rollback(host, name) }.getOrNull()
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            host,
                            if (r?.first == true) "已回退" else "回退未生效：" + (r?.second ?: "未知原因"),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        render()
                    }
                }
            })
        }
    }

    private fun measureInstalled(host: Activity, name: String) {
        val dir = java.io.File(lobos.kernel.layout.SystemDirs.opt(host), name)
        if (!dir.exists()) return
        measuring = true
        thread(name = "lobos-piece-size") {
            val n = lobos.services.supply.Cleanup.dirSize(dir)
            runOnUiThread {
                measuredBytes = n
                measuring = false
                render()
            }
        }
    }

    private fun startInstall(host: Activity, name: String) {
        if (busy) return
        busy = true
        render()
        thread(name = "lobos-install-one") {
            val r = runCatching { PackageInstaller.install(host, name, null) }.getOrNull()
            runOnUiThread {
                busy = false
                val ok = r?.optBoolean("ok", false) == true
                android.widget.Toast.makeText(
                    host,
                    if (ok) "已安装 " + name else "安装未生效：" + (r?.optString("detail", "").orEmpty()),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                render()
            }
        }
    }

    private fun infoRow(label: String, value: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(this@SystemComponentDetailActivity, 5), 0, Ui.dp(this@SystemComponentDetailActivity, 5))
        }
        row.addView(Ui.text(this@SystemComponentDetailActivity, label, Ui.TEXT2, 13f),
            LinearLayout.LayoutParams(Ui.dp(this@SystemComponentDetailActivity, 84), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(Ui.text(this@SystemComponentDetailActivity, value.ifBlank { "未知" }, Ui.TEXT, 13f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    data class Facts(
        val name: String,
        val installed: Boolean,
        val stateLine: String,
        val version: String,
        val downloadSize: String,
        val installedSize: String,
        val heavy: Boolean,
        val requires: String,
        val installedAt: String,
        val path: String,
    )

    companion object {
        const val EXTRA_NAME = "pieceId"

        fun intentOf(ctx: Context, name: String): Intent =
            Intent(ctx, SystemComponentDetailActivity::class.java).putExtra(EXTRA_NAME, name)

        fun gather(ctx: Context, name: String): Facts {
            val meta = InstallGuard.read(ctx, name)
            val entry: JSONObject? = runCatching { CatalogClient.entryFor(ctx, name) }.getOrNull()
            val idx = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
                .firstOrNull { it.id == name }
            val installed = meta != null || idx != null
            val version = meta?.optString("version")?.takeIf { it.isNotBlank() }
                ?: entry?.optString("version")?.takeIf { it.isNotBlank() }
                ?: idx?.version?.takeIf { it.isNotBlank() } ?: ""
            val download = entry?.optLong("size", 0L) ?: 0L
            val real = 0L
            val at = meta?.optLong("installedAt", 0L) ?: 0L
            val time = if (at > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(at)) else ""
            val req = entry?.optJSONObject("requires")?.optString("name", "").orEmpty()
            val stateLine = when {
                meta != null -> "已安装"
                idx != null -> "已安装（缺安装记录，建议重装一次补上）"
                else -> "未安装"
            }
            return Facts(
                name = name,
                installed = installed,
                stateLine = stateLine,
                version = version,
                downloadSize = if (download > 0) InstallGuard.human(download) else "未知",
                installedSize = if (real > 0) InstallGuard.human(real)
                    else if (installed) "计算中…" else "装好后显示",
                heavy = download >= 200L * 1024 * 1024 || real >= 1024L * 1024 * 1024,
                requires = req,
                installedAt = time,
                path = idx?.stateDir ?: "",
            )
        }
    }
}
package lobos.services.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import kotlin.concurrent.thread
import lobos.services.supply.HealthCheck

class SystemCheckActivity : Activity() {
    private lateinit var body: LinearLayout
    private var result: HealthCheck.Result? = null
    private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (b, _) = Ui.scaffold(
            this,
            "体检结果",
            "逐项检查，不通过的点一下就能去处理",
            listOf(
                Triple("home", "首页", SystemHomeActivity::class.java),
                Triple("grid", "组件", SystemComponentsActivity::class.java),
                Triple("disk", "空间", SystemSpaceActivity::class.java),
                Triple("user", "我的", SystemAboutActivity::class.java),
            ),
            0,
        )
        body = b
        render()
        run()
    }

    private fun run() {
        val host = this
        thread(name = "lobos-health") {
            val r = runCatching { HealthCheck.run(host) }.getOrNull()
            runOnUiThread {
                running = false
                result = r
                render()
            }
        }
    }

    private fun render() {
        val host = this
        body.removeAllViews()
        if (running || result == null) {
            body.addView(Ui.text(host, "正在检查…", Ui.TEXT, 16f, bold = true))
            body.addView(Ui.text(host, "组件、安装记录、空间、失败记录、可清理", Ui.TEXT2, 13f))
            return
        }
        val r = result!!
        body.addView(Ui.text(host, r.summary(), if (r.allOk) Ui.OK else Ui.WARN, 20f, bold = true))
        body.addView(Ui.text(host, "通过 " + r.passed + " 项 · 未通过 " + r.failed + " 项", Ui.TEXT2, 13f))
        body.addView(Ui.verticalGap(host, 12))

        for (item in r.items) {
            val box = Ui.card(host, null)
            val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(Ui.text(host, if (item.ok) "通过" else "不通过", if (item.ok) Ui.OK else Ui.ERR, 13f, bold = true))
            head.addView(Ui.text(host, "  " + item.title, Ui.TEXT, 15f, bold = true))
            box.addView(head)
            box.addView(Ui.text(host, item.detail, Ui.TEXT2, 12f))
            if (item.action != HealthCheck.Action.NONE) {
                box.addView(Ui.verticalGap(host, 8))
                box.addView(Ui.outlineButton(host, item.actionLabel) {
                    val target: Class<*>? = when (item.action) {
                        HealthCheck.Action.RECOVERY -> SystemRecoveryActivity::class.java
                        HealthCheck.Action.SPACE -> SystemSpaceActivity::class.java
                        HealthCheck.Action.COMPONENTS -> SystemComponentsActivity::class.java
                        HealthCheck.Action.NONE -> null
                    }
                    if (target != null) runCatching { startActivity(Intent(host, target)) }
                })
            }
            body.addView(box)
            body.addView(Ui.verticalGap(host, 10))
        }

        body.addView(Ui.primaryButton(host, "重新检查") {
            running = true
            result = null
            render()
            run()
        })
    }
}
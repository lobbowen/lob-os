package lobos.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import lobos.bridge.CapabilityBroker
import lobos.os.PortBroker
import lobos.quickapp.DesktopIcons
import lobos.quickapp.QuickAppHost
import lobos.quickapp.QuickAppRegistry

class PanelActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)
    private lateinit var listBox: LinearLayout
    private lateinit var logBox: TextView
    private lateinit var idInput: EditText
    private val worker = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "lobos-panel").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "Lob OS 控制面板"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = "程序（快应用）由商店/OTA 装入，端口由系统分配；桌面图标需你点「装桌面」并在系统弹窗确认。"
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
        })

        val installRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        idInput = EditText(this).apply {
            hint = "程序 id（如 com.lobos.fixture）"
            setSingleLine()
        }
        installRow.addView(idInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        installRow.addView(btn("安装") { doInstall() })
        root.addView(installRow)

        root.addView(btn("刷新", exact = true) { refresh() })
        root.addView(btn("端口占用", exact = true) { showPorts() })

        val scroll = ScrollView(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        logBox = TextView(this).apply { textSize = 11f }
        root.addView(logBox)
        return root
    }

    private fun btn(label: String, exact: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            if (exact) layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun say(line: String) {
        Log.i(TAG, line)
        handler.post { logBox.text = stamp.format(Date()) + "  " + line + "\n" + logBox.text.toString().take(3000) }
    }

    private fun refresh() {
        worker.execute {
            val rows = try {
                QuickAppRegistry.listed(this).map { entry(it) }
            } catch (e: Throwable) {
                listOf("读取失败: " + (e.message ?: e.javaClass.simpleName))
            }
            handler.post { render(rows) }
        }
    }

    private fun entry(e: lobos.os.IndexEntry): String = buildString {
        append(e.id)
        append("  v").append(e.version.ifBlank { "?" })
        append("  端口=").append(if (e.httpPort > 0) e.httpPort.toString() else "未分配")
        val name = e.uiName.ifBlank { "（未登记名字）" }
        append("  ").append(name)
    }

    private fun render(rows: List<String>) {
        listBox.removeAllViews()
        if (rows.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "还没有装任何快应用。上面填程序 id 点「安装」。"
                textSize = 13f
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        for (r in rows) {
            val id = r.substringBefore("  ")
            listBox.addView(TextView(this).apply {
                text = r
                textSize = 13f
                setPadding(0, dp(8), 0, dp(2))
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(btn("打开") { openApp(id) })
            row.addView(btn("装桌面") { addIcon(id) })
            row.addView(btn("移桌面") { removeIcon(id) })
            row.addView(btn("升级") { upgrade(id) })
            row.addView(btn("卸载") { uninstall(id) })
            listBox.addView(row)
        }
    }

    private fun openApp(id: String) {
        runOnUiThread {
            val why = QuickAppHost.open(this, id)
            if (why.isEmpty()) say("已打开 $id") else say("打不开 $id: $why")
        }
        say("请求打开 $id")
    }

    private fun addIcon(id: String) {
        say("请求把 $id 装到桌面（系统会弹窗，要你确认）")
        DesktopIcons.request(this, id, labelOf(id), programRootOf(id), iconOf(id)) { st ->
            say("桌面状态 $id → " + st.label)
            toast(st.label)
        }
    }

    private fun removeIcon(id: String) {
        DesktopIcons.withdraw(this, id) { ok ->
            say("移桌面 $id → " + (if (ok) "已停用" else "失败（系统摘不掉已固定的入口）"))
        }
    }

    private fun upgrade(id: String) {
        callBridge(id, "os.appmgr.upgrade")
    }

    private fun uninstall(id: String) {
        callBridge(id, "os.appmgr.uninstall")
    }

    private fun doInstall() {
        val id = idInput.text.toString().trim()
        if (id.isBlank()) {
            toast("先填程序 id")
            return
        }
        callBridge(id, "os.appmgr.install")
    }

    private fun callBridge(id: String, method: String) {
        say("调 $method id=$id")
        worker.execute {
            val broker = CapabilityBroker.live()
            if (broker == null) {
                say("宿主桥未启动")
                return@execute
            }
            val res = runCatching {
                broker.invokeLocal(
                    CapabilityBroker.INSTALLER_ID,
                    method,
                    org.json.JSONObject().apply { put("id", id) },
                )
            }.getOrElse {
                say("$method 异常: " + (it.message ?: it.javaClass.simpleName))
                return@execute
            }
            val taskId = res.optString("taskId", "")
            say("$method → " + res.toString().take(400))
            if (taskId.isNotBlank()) pollTask(taskId)
        }
    }

    private fun pollTask(taskId: String) {
        val deadline = System.currentTimeMillis() + 90000L
        val nl = System.lineSeparator()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(700L)
            val t = lobos.os.TaskRegistry.get(this, taskId) ?: continue
            val line = stamp.format(Date()) + "  " + t.id + "  " + t.state + "  " + t.progress + "%"
            handler.post { logBox.text = line + nl + logBox.text.toString().take(3000) }
            if (t.state == "done" || t.state == "failed") {
                val result = stamp.format(Date()) + "  结果：" + t.detail
                handler.post {
                    toast(if (t.state == "done") "完成" else "失败")
                    logBox.text = result + nl + logBox.text.toString().take(3000)
                }
                if (t.state == "done") refresh()
                return
            }
        }
    }

    private fun showPorts() {
        worker.execute {
            val leases = PortBroker.list(this)
            val text = if (leases.isEmpty()) {
                "端口段 41000-50999：无人占用"
            } else {
                "端口占用 " + leases.size + " / 10000：\n" +
                    leases.joinToString("\n") { "  ${it.port}  ${it.owner}" }
            }
            handler.post { toast(text.take(400)) }
        }
    }

    private fun programRootOf(id: String): java.io.File = lobos.os.ProgramManager.stateDirOf(this, id)

    private fun labelOf(id: String): String {
        val e = lobos.os.ProgramIndex.get(this, id) ?: return id
        return e.uiName.ifBlank { id }
    }

    private fun iconOf(id: String): String = lobos.os.ProgramIndex.get(this, id)?.uiIcon.orEmpty()

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    companion object {
        const val TAG = "lobos.panel"
    }
}

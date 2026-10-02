package lobos

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import lobos.bridge.ScreenCaptureController
import lobos.capability.CapabilityAcquisitionRunner
import lobos.capability.CapabilityCatalog
import lobos.capability.Evidence
import lobos.ota.ProgramOtaSelfCheck
import lobos.lifecycle.OsHostService
import lobos.runtime.InstanceHost
import lobos.permissions.PermissionCatalog
import lobos.permissions.PermissionCenter

class MainActivity : AppCompatActivity() {

    private lateinit var diagText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var retryBtn: Button
    private lateinit var captureBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var probeBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var selfCheckText: String = ""

    private var channelBar: TextView? = null
    private var scrollBaseTopPadding = 0
    private val channelBarHeightPx: Int by lazy {
        (CHANNEL_BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()
    }
    @Volatile private var channelBarRefreshInFlight = false
    private var channelBarTick = 0


    private val requestCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureController.saveGrant(this, result.resultCode, data)
            startCaptureService(result.resultCode, data)
            RuntimeDiagnostics.append(
                this, "screenshot", true, "截屏授权已获取", "已缓存，可后台复用；ui.screenshot 现在可用"
            )
        } else {
            RuntimeDiagnostics.append(
                this, "screenshot", false, "截屏授权被取消",
                "ui.screenshot 将继续返回 -32001；可随时点「授权屏幕捕获」重来"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        diagText = findViewById(R.id.diagText)
        scroll = findViewById(R.id.scroll)
        retryBtn = findViewById(R.id.retryBtn)
        captureBtn = findViewById(R.id.captureBtn)
        copyBtn = findViewById(R.id.copyBtn)
        probeBtn = findViewById(R.id.probeBtn)

        retryBtn.setOnClickListener { restartRuntime() }
        captureBtn.setOnClickListener { requestScreenCapture() }
        copyBtn.setOnClickListener { copySelfCheck() }
        probeBtn.setOnClickListener { runNativeProbeOnce() }

        installChannelBar()
        reuseExistingCaptureGrant()
        startRuntime()
        startPolling()
        runSelfCheckOnce()
    }

    override fun onResume() {
        super.onResume()
        refreshChannelBar()
    }

    private fun runSelfCheckOnce() {
        Thread {
            val text = try {
                ProgramOtaSelfCheck.runAndFormat(this)
            } catch (e: Throwable) {
                "自检异常: " + e::class.java.simpleName + ": " + (e.message ?: "")
            }
            selfCheckText = text
        }.apply { isDaemon = true }.start()
    }

    private fun copySelfCheck() {
        copyBtn.isEnabled = false
        Thread {
            val text = try {
                ProgramOtaSelfCheck.runAndFormat(this)
            } catch (e: Throwable) {
                "自检异常: " + e::class.java.simpleName + ": " + (e.message ?: "")
            }
            selfCheckText = text
            handler.post {
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Lob OS 自检", text))
                    android.widget.Toast.makeText(
                        this, "已复制到剪贴板 —— 直接粘贴发我即可", android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (e: Throwable) {
                    android.widget.Toast.makeText(
                        this, "复制失败: " + e.message, android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                copyBtn.isEnabled = true
            }
        }.apply { isDaemon = true }.start()
    }

    private fun isAtBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= child.height - 8
    }

    private fun reuseExistingCaptureGrant() {
        val captureSpec = PermissionCatalog.byId(PermissionCatalog.MEDIAPROJECTION)
        if (captureSpec != null && PermissionCenter(this).isGranted(captureSpec)) return
        val grant = ScreenCaptureController.loadGrant(this) ?: return
        startCaptureService(grant.first, grant.second)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        try {
            val svc = Intent(this, OsHostService::class.java)
                .setAction(ScreenCaptureController.ACTION_START)
                .putExtra(ScreenCaptureController.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureController.EXTRA_RESULT_DATA, data)
            startService(svc)
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "启动截屏服务失败",
                "${e::class.java.simpleName}: ${e.message}")
        }
    }

    private fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            RuntimeDiagnostics.append(this, "screenshot", false, "平台不支持 MediaProjection", "需 API 21+")
            return
        }
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            requestCapture.launch(mpm.createScreenCaptureIntent())
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "发起截屏授权失败",
                "${e::class.java.simpleName}: ${e.message}")
        }
    }
    private fun startRuntime(action: String? = null) {
        OsHostService.ensureRunning(this)
        if (action != null) {
            try {
                startService(Intent(this, OsHostService::class.java).setAction(action))
            } catch (e: Throwable) {
                RuntimeDiagnostics.append(this, "runtime", false, "发送运行时动作失败",
                    e::class.java.simpleName + ": " + e.message)
            }
        }
    }

    private fun restartRuntime() {
        scroll.visibility = View.VISIBLE
        retryBtn.visibility = View.VISIBLE
        RuntimeDiagnostics.clear(this)
        diagText.text = "正在重启运行时..."
        startRuntime(InstanceHost.ACTION_RESTART)
        RuntimeDiagnostics.append(this, "runtime", null, "已请求宿主重读 CURRENT", "单进程模型：经宿主 intent 转发 ACTION_RESTART")
    }

    private fun runNativeProbeOnce() {
        probeBtn.isEnabled = false
        startRuntime(InstanceHost.ACTION_PROBE)
        handler.postDelayed({ probeBtn.isEnabled = true }, InstanceHost.PROBE_POLL_BUDGET_MS + 2000L)
    }

    private fun startPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (channelBarTick++ % 4 == 0) refreshChannelBar()
                val log = RuntimeDiagnostics.read(this@MainActivity)
                val body = if (log.isBlank()) "初始化中..." else log
                diagText.text = if (selfCheckText.isBlank()) body else selfCheckText + "\n" + body
                if (isAtBottom()) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun installChannelBar() {
        val frame = scroll.parent?.parent as? FrameLayout ?: return
        scrollBaseTopPadding = scroll.paddingTop
        val pad = (8f * resources.displayMetrics.density).toInt()
        val bar = TextView(this).apply {
            text = ChannelStatusText.DOWN
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(0xFFFFEBEE.toInt())
            setTextColor(0xFFC62828.toInt())
            setOnClickListener { retestChannel() }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                channelBarHeightPx,
                Gravity.TOP,
            )
        }
        channelBar = bar
        frame.addView(bar)
    }

    private fun refreshChannelBar() {
        if (channelBarRefreshInFlight) return
        channelBarRefreshInFlight = true
        Thread {
            val now = System.currentTimeMillis()
            val probe = runCatching { lobos.capability.AdbChannelComponent.refreshNow(applicationContext) }.getOrNull()
            val live = probe != null && Evidence(nowMs = now, channel = probe).channelLive()
            channelBarRefreshInFlight = false
            handler.post {
                channelBar?.visibility = if (live) View.GONE else View.VISIBLE
                reserveChannelBarSpace(!live)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun reserveChannelBarSpace(visible: Boolean) {
        val offset = if (visible) channelBarHeightPx else 0
        scroll.setPadding(
            scroll.paddingLeft,
            scrollBaseTopPadding + offset,
            scroll.paddingRight,
            scroll.paddingBottom,
        )
    }

    private fun retestChannel() {
        lobos.capability.AdbChannelComponent.reset(this, "界面要求重测通道")
        android.widget.Toast.makeText(this, "正在重测 ADB 通道…", android.widget.Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        Thread {
            val acq = CapabilityCatalog.byId(CapabilityCatalog.ADB_CHANNEL)
                ?.acquirer?.invoke(
                    Evidence(nowMs = System.currentTimeMillis(), channel = lobos.capability.AdbChannelComponent.asChannelProbe())
                )
                ?.firstOrNull()
            val result = acq?.let {
                runCatching { CapabilityAcquisitionRunner.dispatch(ctx, CapabilityCatalog.ADB_CHANNEL, it) }.getOrNull()
            }
            handler.post {
                refreshChannelBar()
                android.widget.Toast.makeText(
                    this,
                    if (result?.verified == true) "ADB 通道已恢复"
                    else "ADB 通道仍不可用：" + (result?.detail ?: "未取得结论"),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

private const val CHANNEL_BAR_HEIGHT_DP = 36

internal object ChannelStatusText {
    const val DOWN = "ADB 通道已断开 · 点此重连"
}

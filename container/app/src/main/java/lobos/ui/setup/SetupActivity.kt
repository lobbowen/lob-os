package lobos.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import lobos.BuildConfig
import lobos.ChannelStatusText
import lobos.MainActivity
import lobos.capability.AcquireKind
import lobos.capability.Acquisition
import lobos.capability.AttemptStore
import lobos.capability.BridgeTokens
import lobos.capability.CapabilityAcquisitionRunner
import lobos.capability.CapabilityCatalog
import lobos.capability.CapabilityEvidenceCollector
import lobos.capability.CapabilityNavigation
import lobos.capability.CapStatus
import lobos.capability.CapVerdict
import lobos.capability.Evidence
import lobos.setup.OnboardingFlow
import lobos.capability.PairingGate
import lobos.setup.PipelineProjection
import lobos.setup.PipelineRefresh
import lobos.setup.StageStatus
import lobos.setup.StepStatus
import lobos.lifecycle.ResidencyAudit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SetupActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val stageTexts = mutableMapOf<String, TextView>()
    private val stageButtons = mutableMapOf<String, Button>()
    private var criteriaText: TextView? = null
    private var recentText: TextView? = null
    private var channelBar: TextView? = null
    private var progressText: TextView? = null
    private var debtsText: TextView? = null
    private var debtsDetail: TextView? = null
    private var enterBtn: Button? = null
    private var panelBtn: Button? = null
    private var lastEvidence: Evidence? = null

    @Volatile private var refreshInFlight = false
    @Volatile private var actionInFlight = false
    @Volatile private var resumed = false

    private var pendingRuntimePerm: String? = null

    private val requestRuntimePerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val perm = pendingRuntimePerm
        pendingRuntimePerm = null
        if (!granted) openAppDetailsAfterDenial(perm)
        refreshSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        PipelineRefresh.subscribe(onChange)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lobos.capability.AdbChannelComponent.reset(this, "配对冲刺结束，重测通道")
        refreshSoon()
        handler.post(poller)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poller)
        super.onPause()
    }

    override fun onDestroy() {
        PipelineRefresh.unsubscribe(onChange)
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    private fun buildLayout(): View {
        val pad = (16f * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = "Lob OS 工作台"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad / 2)
        })
        recentText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
            text = recentActions()
        }
        col.addView(recentText)
        channelBar = TextView(this).apply {
            textSize = 13f
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(0xFFFFEBEE.toInt())
            setTextColor(0xFFC62828.toInt())
            setOnClickListener { retestChannel() }
            visibility = View.GONE
        }
        col.addView(channelBar)
        for (stage in OnboardingFlow.SKELETON) {
            val tv = TextView(this).apply {
                textSize = 15f
                setPadding(0, pad / 2, 0, 0)
                text = "${stage.id} ${stage.title}"
            }
            stageTexts[stage.id] = tv
            col.addView(tv)
            col.addView(TextView(this).apply {
                textSize = 12f
                setPadding(pad / 2, 0, 0, pad / 4)
                text = stage.why
            })
            val btn = Button(this).apply { visibility = View.GONE }
            stageButtons[stage.id] = btn
            col.addView(btn)
            if (stage.id == OnboardingFlow.F3) {
                enterBtn = Button(this).apply {
                    text = "进入工作台"
                    visibility = View.GONE
                    isEnabled = false
                    setOnClickListener { openWorkbench() }
                }
                col.addView(enterBtn)
            }
        }
        panelBtn = Button(this).apply {
            text = "控制面板"
            setOnClickListener { openPanel() }
        }
        col.addView(panelBtn as View)
        progressText = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }
        col.addView(progressText)
        debtsText = TextView(this).apply {
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
            setOnClickListener {
                debtsDetail?.visibility =
                    if (debtsDetail?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        col.addView(debtsText)
        debtsDetail = TextView(this).apply {
            textSize = 11f
            setPadding(pad / 2, 0, 0, 0)
            visibility = View.GONE
        }
        col.addView(debtsDetail)
        criteriaText = TextView(this).apply {
            textSize = 11f
            setPadding(0, pad, 0, 0)
        }
        col.addView(criteriaText)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, 0)
            addView(Button(context).apply {
                text = "复制诊断报告"
                setOnClickListener { copyReport() }
            })
            addView(Button(context).apply {
                text = "清空系统日志"
                setOnClickListener {
                    toast("系统日志已清空")
                }
            })
        })
        return ScrollView(this).apply { addView(col) }
    }

    private val onChange: () -> Unit = { refreshSoon() }
    private val poller = object : Runnable {
        override fun run() {
            refreshSoon()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun refreshSoon() {
        if (refreshInFlight) return
        refreshInFlight = true
        Thread {
            val snapshot = runCatching { CapabilityEvidenceCollector.collect(this) }.getOrNull()
            refreshInFlight = false
            if (snapshot != null) handler.post { render(snapshot) }
        }.apply { isDaemon = true }.start()
    }

    private fun render(e: Evidence) {
        lastEvidence = e
        recentText?.text = recentActions()
        val verdicts = CapabilityCatalog.evaluate(e)
        val stages = OnboardingFlow.stages(e, verdicts)
        for (s in stages) {
            val tv = stageTexts[s.id] ?: continue
                s.id == OnboardingFlow.F1
            tv.text = "${s.id} ${s.title} ${mark(s.status)}" +
                (if (blocked) " 通知权限缺失 → 输码通知发不出去" else "") +
                (if (s.detail.isBlank()) "" else "｜${s.detail}")
            val acq = s.action
            val btn = stageButtons[s.id] ?: continue
            btn.visibility = if (acq == null) View.GONE else View.VISIBLE
            btn.text = acq?.label ?: ""
            btn.isEnabled = true
            if (acq != null) btn.setOnClickListener { dispatch(s.actionCapId ?: "", acq, btn) }
        }
        val live = e.channelLive()
        channelBar?.visibility = if (live) View.GONE else View.VISIBLE
        channelBar?.text = ChannelStatusText.of(e.channel)

        renderDebts(verdicts)

        val ready = OnboardingFlow.readyToEnter(verdicts)
        val enterable = ready
        enterBtn?.visibility = if (ready) View.VISIBLE else View.GONE
        enterBtn?.isEnabled = enterable
        progressText?.text = progressSummary(e, verdicts)
        criteriaText?.text = "判据核对：" +
            PipelineProjection.project(e, verdicts).joinToString("  ") {
                "${it.id}${segMark(it.status)}${it.detail}"
            }
    }

    private fun recentActions(): String {
        val lines = AttemptStore.humanPairTimeline().take(3)
        val recent = if (lines.isEmpty()) {
            "最近动作：还没有过一次配对尝试 —— 点下面标着「下一步」的那个按钮"
        } else {
            "最近动作：\n" + lines.joinToString("\n")
        }
        val audit = ResidencyAudit.interruption(this)
        return if (audit == null) recent else "$audit\n$recent"
    }

    private fun mark(s: StageStatus): String = when (s) {
        StageStatus.DONE -> "[完成]"
        StageStatus.CURRENT -> "[下一步]"
        StageStatus.NEXT -> "[待办]"
        StageStatus.BLOCKED -> "[等待]"
        StageStatus.FAILED -> "[失败]"
    }

    private fun segMark(s: StepStatus): String = when (s) {
        StepStatus.DONE -> "[完成]"
        StepStatus.ACTION -> "[待办]"
        StepStatus.BLOCKED -> "[等待]"
        StepStatus.FAILED -> "[失败]"
    }

    private fun dispatch(capId: String, acq: Acquisition, btn: Button?): Boolean {
        val sent = when (acq.kind) {
            AcquireKind.USER_CODE -> { startPairing(); true }
            AcquireKind.RUNTIME_DIALOG -> requestRuntimePermission(acq)
            AcquireKind.USER_TAP -> {
                val jumped = CapabilityNavigation.launch(this, acq) { note ->
                }
                if (!jumped) toast("授权页打不开：${acq.label}")
                jumped
            }
            else -> { runAcquisition(capId, acq, btn); true }
        }
        handler.postDelayed({ refreshSoon() }, REFRESH_AFTER_TAP_MS)
        return sent
    }

    private fun startPairing() {
        lobos.capability.AdbChannelComponent.reset(this, "开始配对，重测通道")
        if (lastEvidence == null) {
            toast("环境读数还没到位，稍等一下再点")
            return
        }
        Thread {
            val e = runCatching { CapabilityEvidenceCollector.collect(this) }.getOrNull()
            val decision = e?.let { PairingGate.decide(it, CapabilityCatalog.evaluate(it)) }
            handler.post {
                if (decision == null) {
                    toast("环境读数采集失败，请再点一次「开始配对」")
                    return@post
                }
                toast(decision.notice)
                val acq = decision.jump ?: return@post
                dispatch(decision.gapCapId ?: CapabilityCatalog.WIRELESS_DEBUG, acq, null)
                refreshSoon()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun requestRuntimePermission(acq: Acquisition): Boolean {
        val perm = CapabilityNavigation.runtimePermission(acq)
        if (perm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast("本机系统不需要这一步")
            return false
        }
        pendingRuntimePerm = perm
        requestRuntimePerm.launch(perm)
        return true
    }

    private fun openAppDetailsAfterDenial(perm: String?) {
        if (perm == null) return
            this, "perm",
            "$perm 弹窗结果=未授予（多半勾了「不再询问」）→ 跳本应用详情页，给一条能走的路",
        )
        val jumped = runCatching { startActivity(CapabilityNavigation.appDetailsIntent(this)) }.isSuccess
        if (!jumped) toast("系统权限页打不开：$perm 需手动开启")
    }

    private fun runAcquisition(capId: String, acq: Acquisition, btn: Button?) {
        if (actionInFlight) return
        actionInFlight = true
        btn?.isEnabled = false
        toast("${acq.label} 执行中…")
        Thread {
            val ctx = applicationContext
            val result = runCatching { CapabilityAcquisitionRunner.dispatch(ctx, capId, acq) }.getOrNull()
            handler.post {
                actionInFlight = false
                btn?.isEnabled = true
                result?.detail?.let {
                    toast(if (result.verified) "已生效" else it)
                }
                refreshSoon()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun retestChannel() {
        lobos.capability.AdbChannelComponent.reset(this, "界面要求重测通道")
        val e = lastEvidence ?: Evidence()
        val acq = CapabilityCatalog.byId(CapabilityCatalog.ADB_CHANNEL)
            ?.acquirer?.invoke(e)?.firstOrNull()
        if (acq == null) {
            refreshSoon()
            return
        }
        toast("正在重测 ADB 通道…")
        dispatch(CapabilityCatalog.ADB_CHANNEL, acq, null)
    }

    private fun progressSummary(e: Evidence, verdicts: Map<String, CapVerdict>): String {
        val snap = lobos.permissions.PermissionLedger.register(this)
        val keepMissing = snap.missing
        val oxPending = listOf(
            CapabilityCatalog.ADB_CHANNEL,
            CapabilityCatalog.ADB_UI_AUTOMATION,
        ).filter { verdicts[it]?.status != CapStatus.GRANTED }
        val parts = mutableListOf<String>()
        parts += "权限在册 ${snap.records.size} 项" +
            if (keepMissing.isEmpty()) "（保活必需项齐）"
            else "（缺保活必需 ${keepMissing.size}：" + keepMissing.joinToString { it.id } + "）"
        if (oxPending.isEmpty()) parts += "可选组件：未启用"
        else parts += "可选组件待开：" + oxPending.joinToString { CapabilityCatalog.titleOf(it) }
        return parts.joinToString("　")
    }

    private fun renderDebts(verdicts: Map<String, CapVerdict>) {
        val debts = OnboardingFlow.debts(verdicts)
        debtsText?.text = if (debts.isEmpty()) {
            "欠账（不挡入口）：无"
        } else {
            "欠账（不挡入口）：${debts.size} 项 · 点此展开"
        }
        debtsDetail?.text = debts.joinToString("\n") {
            "· " + it.title + "：" + (verdicts[it.id]?.detail ?: "")
        }
    }

    private fun openPanel() {
        startActivity(Intent(this, lobos.ui.PanelActivity::class.java))
    }

    private fun openWorkbench() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun copyReport() {
        val e = lastEvidence
        toast("报告生成中…")
        Thread {
            val ctx = applicationContext
            val report = buildString {
                appendLine("== Lob OS 开场管线报告 ==")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT} · " +
                    "APK ${BuildConfig.VERSION_NAME}#${BuildConfig.VERSION_CODE}")
                appendLine("---- 常驻定罪 ----")
                appendLine(ResidencyAudit.interruption(this) ?: "上次收尾是正常退出（或本机首次装），无可定罪的中断")
                if (e != null) {
                    val verdicts = CapabilityCatalog.evaluate(e)
                    appendLine("---- 流程阶段 ----")
                    OnboardingFlow.stages(e, verdicts).forEach {
                        appendLine("${it.id} ${it.title} ${it.status} ${it.detail}")
                    }
                    appendLine("---- adb 实测账（files/os/permission-ledger.json） ----")
                    if (e.permissionAttempts.isEmpty()) {
                        appendLine("还没有一次静默下发：配对成功后自动流会逐项试一遍")
                    } else {
                        e.permissionAttempts.entries.sortedBy { it.key }.forEach { (id, a) ->
                            appendLine("· ${CapabilityCatalog.titleOf(id)}（$id）：${a.outcome.human}" +
                                "｜${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(a.atMs))}" +
                                "｜${a.detail}")
                        }
                    }
                    appendLine("---- 配对尝试（与通知同源） ----")
                    appendLine(AttemptStore.humanPairTimeline().joinToString("\n").ifBlank { "无" })
                    appendLine("---- 能力判据 ----")
                    (CapabilityCatalog.ALL + CapabilityCatalog.OEM_GUARDS).forEach { c ->
                        val v = verdicts[c.id]
                        appendLine(
                            "${c.segment} ${c.id}${if (c.optional) "*" else ""} " +
                                "${v?.status} ${v?.detail}｜取法 " +
                                c.acquirer(e).joinToString(">") { it.label }
                        )
                    }
                    appendLine("桥令牌：" + BridgeTokens.from(e).sorted().joinToString())
                }
                appendLine("---- 系统日志 ----")
                appendLine(lobos.log.Journal.tail(ctx, 200))
            }
            handler.post {
                runCatching {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Lob OS 管线报告", report))
                    toast("已复制 —— 设备 adb 关闭，剪贴板是唯一导出通道")
                }.onFailure { toast("复制失败：${it.message}") }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val POLL_MS = 2_000L

        private const val REFRESH_AFTER_TAP_MS = 800L
    }
}

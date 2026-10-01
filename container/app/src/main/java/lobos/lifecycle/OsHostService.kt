package lobos.lifecycle

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import lobos.OsApplication
import lobos.R
import lobos.RuntimeDiagnostics
import lobos.bridge.CapabilityBroker
import lobos.bridge.ScreenCaptureController
import lobos.capability.CapabilityEvidenceCollector
import lobos.os.AppRegistry
import lobos.os.OsFacts
import lobos.os.OsInit
import lobos.os.OsPhase
import lobos.ota.ProgramManager
import lobos.runtime.InstanceHost
import lobos.ui.setup.SetupActivity

class OsHostService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastNotifyMs = 0L

    private var pool: lobos.runtime.SupervisorPool? = null
    private var broker: CapabilityBroker? = null
    private var capture: ScreenCaptureController? = null

    private var anchorBoundLastTick: Boolean? = null
    private var lastTickMs = 0L
    private var startedAtMs = 0L
    private var anchorStateLast: AnchorState? = null
    private var anchorRebindAttempts = 0
    private var anchorRebindNextAt = 0L
    private var anchorGiveUpLogged = false
    private var adbReady = false
    private var adbSampledAt = 0L
    private var degradedLast: List<String> = emptyList()
    private var silentGrantVerified = false
    private var deviceOwnerMeasured = false
    private var ownerSampledAt = 0L
    private var tierLast = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ResidencyAudit.auditPreviousExit(this)
        val born = thread == null
        if (born) {
            lobos.os.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption())
        }
        promoteToForeground()
        ensureComponents(intent)
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption())
            handler?.post(tick)
            runCatching {
                val ver = lobos.os.ProgramRegistry.listIds(this)
                    .mapNotNull { ProgramManager(this, it).currentVersion() }
                    .singleOrNull()
                RuntimeDiagnostics.append(
                    this, "host", true, "宿主不替任何应用决定生死（I1）",
                    "程序表由内核程序存储拥有；此处只观测：current=" + (ver ?: "无") +
                        "；已登记程序数=" + AppRegistry.all(this).size,
                )
            }
            lobos.os.DozeBackstop.schedule(this)
            RuntimeDiagnostics.append(
                this, "host", true, "Lob OS 宿主就位（单进程 / 单前台服务）",
                "组件：SupervisorPool + CapabilityBroker + ScreenCaptureController；节拍 " + TICK_MS + "ms",
            )
            handler?.post {
                try {
                    lobos.os.RuntimeEnvironment.ensure(this)
                } catch (e: Throwable) {
                    RuntimeDiagnostics.append(
                        this, "prefix", false, "运行环境装配异常（\$PREFIX 内状态未知）",
                        "${e::class.java.simpleName}: ${e.message}"
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        try {
            startForeground(NOTIF_ID, buildNotification(OsInit.statusLine(this)))
        } catch (t: Throwable) {
            RuntimeDiagnostics.append(
                this, "host", false, "转前台失败",
                t::class.java.simpleName + ": " + t.message,
            )
        }
    }

    private fun ensureComponents(intent: Intent?) {
        try {
            val p = pool ?: lobos.runtime.SupervisorPool(this).also { pool = it; it.start() }
            p.onHostStart(intent)
        } catch (t: Throwable) {
            RuntimeDiagnostics.append(this, "runtime", false, "运行时实例组件异常", t::class.java.simpleName + ": " + t.message)
        }
        try {
            val b = broker ?: CapabilityBroker(this).also { broker = it; it.start() }
            b.onHostStart(intent)
        } catch (t: Throwable) {
            RuntimeDiagnostics.append(this, "bridge", false, "能力桥组件异常", t::class.java.simpleName + ": " + t.message)
        }
        try {
            val c = capture ?: ScreenCaptureController(this).also { capture = it; it.start() }
            c.onHostStart(intent)
        } catch (t: Throwable) {
            RuntimeDiagnostics.append(this, "capture", false, "截屏组件异常", t::class.java.simpleName + ": " + t.message)
        }
    }

    private val tick: Runnable = Runnable {
        try {
            val now = SystemClock.elapsedRealtime()
            val gap = if (lastTickMs > 0L) now - lastTickMs else 0L
            lastTickMs = now
            if (ResidencyPolicy.frozen(gap)) {
                RuntimeDiagnostics.append(
                    this, "residency", false, "节拍被冻结/打断（宿主可能被回收过）",
                    "距上拍 " + (gap / 1000) + "s，阈值 " + (ResidencyPolicy.FREEZE_GAP_MS / 1000) + "s；已自证续拍",
                )
                lobos.os.Journal.append(this, "residency", null, "节拍恢复（曾被冻结/回收）", "gapMs=" + gap)
            }
            val anchor = AccessibilityAnchor.state(this)
            observeAnchorTransition(anchor)
            publishResidency(gap, anchor)
            refreshStatusNotice(now, anchor)
            runCatching { pool?.sync() }
            observeAnchorHealth(anchor)
            sampleAdb(now)
            runCatching { lobos.native.DriverRegistry.ingest(this) }
            val nowWall = System.currentTimeMillis()
            if (!lobos.os.DozeBackstop.armedRecently(nowWall)) {
                val armed = lobos.os.DozeBackstop.schedule(this)
                lobos.os.Journal.append(
                    this, "doze", armed, "兜底闹钟未按期投递：已重挂",
                    "armed=" + armed + "（看门狗每拍校验布防）",
                )
            }
            if (now - ownerSampledAt > ResidencyPolicy.OWNER_PROBE_TTL_MS) {
                ownerSampledAt = now
                deviceOwnerMeasured = runCatching {
                    lobos.capability.DeviceOwnerProbe.measure(this).isDeviceOwner
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "宿主节拍异常（不致命，下一拍继续）", e)
        } finally {
            handler?.postDelayed(tick, ResidencyPolicy.FAST_TICK_MS)
        }
    }

    private fun refreshStatusNotice(now: Long, anchor: AnchorState) {
        if (now - lastNotifyMs < NOTIFY_MS) return
        lastNotifyMs = now
        ResidencyAudit.heartbeat(this)
        val facts = OsFacts(
            readingsCollected = true,
            controlPlaneUp = runCatching { CapabilityEvidenceCollector.controlPlaneUp() }.getOrDefault(false),
            channel = lobos.capability.AdbChannelComponent.probeOutcome(),
            anchor = anchor,
        )
        OsInit.refresh(this, facts, ResidencyAudit.interruption())
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildNotification(OsInit.statusLine(this)))
        }
    }

    private fun observeAnchorHealth(anchor: AnchorState) {
        val verdict = (anchor)
        if (verdict == AnchorState.BOUND) {
            if (anchorRebindAttempts > 0) {
                RuntimeDiagnostics.append(
                    this, "residency", true, "锚已恢复绑定", "此前重挂尝试=" + anchorRebindAttempts,
                )
            }
            anchorRebindAttempts = 0
            anchorRebindNextAt = 0L
            anchorGiveUpLogged = false
        }
        if (verdict != anchorStateLast) {
            anchorStateLast = verdict
            when (verdict) {
                AnchorState.BOUND -> RuntimeDiagnostics.append(
                    this, "residency", true, "锚已绑定（能力面完整）", "verdict=PROTECTED",
                )
                AnchorState.UNBOUND -> RuntimeDiagnostics.append(
                    this, "residency", false, "锚掉线（走 KeepAlive 周期重挂）",
                    "verdict=DEGRADED；退避=" + ResidencyPolicy.ANCHOR_REBIND_BASE_MS + "ms 起，上限 " +
                        ResidencyPolicy.ANCHOR_REBIND_MAX_ATTEMPTS + " 次；" +
                        ResidencyPolicy.actions(listOf(ResidencyPolicy.REASON_ANCHOR)).joinToString(),
                )
                AnchorState.UNKNOWN -> RuntimeDiagnostics.append(
                    this, "residency", null, "锚状态未知", "verdict=UNKNOWN（组件名解析不出或系统服务查不动）",
                )
            }
        }
        if (verdict != AnchorState.UNBOUND) return
        val now = SystemClock.elapsedRealtime()
        if (!ResidencyPolicy.shouldRebind(false, anchorRebindAttempts)) {
            if (!anchorGiveUpLogged) {
                anchorGiveUpLogged = true
                RuntimeDiagnostics.append(
                    this, "residency", false, "锚重挂达上限：转为降级上报",
                    "尝试=" + anchorRebindAttempts + "；动作：" +
                        ResidencyPolicy.actions(listOf(ResidencyPolicy.REASON_ANCHOR)).joinToString(),
                )
            }
            return
        }
        if (anchorRebindNextAt > 0L && now < anchorRebindNextAt) return
        anchorRebindAttempts += 1
        val attempt = anchorRebindAttempts
        val backoff = ResidencyPolicy.anchorBackoffMs(attempt)
        anchorRebindNextAt = now + backoff
        Thread {
            val out = runCatching {
                AccessibilityAnchor.ensureBound(this, AnchorPolicy.ACTIVATION_BUDGET_MS)
            }.getOrNull()
            RuntimeDiagnostics.append(
                this, "residency", out?.state == AnchorState.BOUND, "锚重挂尝试 #" + attempt,
                "结果=" + (out?.state?.name ?: "调用失败") + "；下次退避=" + backoff + "ms",
            )
        }.start()
    }

    private fun sampleAdb(now: Long) {
        pool?.tickComponents()
        adbReady = lobos.capability.AdbChannelComponent.isOnline()
        if (adbReady && !silentGrantVerified) {
            silentGrantVerified = runCatching {
                lobos.capability.PermissionLedger.readAll(this).values
                    .any { it.outcome == lobos.capability.AttemptOutcome.SILENT_OK }
            }.getOrDefault(false)
        }
    }

    private fun publishResidency(gapMs: Long, anchor: AnchorState) {
        val protectedNow = (anchor) == AnchorState.BOUND
        val runningIds = runCatching { pool?.running() ?: emptyList<String>() }.getOrDefault(emptyList())
        val installed = runCatching {
            lobos.os.ProgramRegistry.list(this).count { it.startable }
        }.getOrDefault(0)
        val reasons = ResidencyPolicy.degradedReasons(
            anchorProtected = protectedNow,
            adbReady = adbReady,
            programsRunning = runningIds.size,
            installedPrograms = installed,
        )
        val acts = ResidencyPolicy.actions(reasons)
        val tier = lobos.capability.CapabilityTier.of(
            channelLive = adbReady,
            deviceOwner = deviceOwnerMeasured,
            silentGrantVerified = silentGrantVerified,
            notes = emptyList(),
        )
        if (tier.tier.name != tierLast) {
            tierLast = tier.tier.name
            RuntimeDiagnostics.append(
                this, "capability", true, "能力档位：" + tier.tier.label,
                "依据=" + tier.basis.joinToString("；") +
                    (if (tier.unproven.isEmpty()) "" else "；未证=" + tier.unproven.joinToString("；")),
            )
            lobos.os.Journal.append(this, "capability", null, "能力档位判定", tier.tier.name.lowercase())
        }
        lobos.os.ResidencyStatus.record(
            lobos.os.ResidencyStatus.Snapshot(
                anchorBound = protectedNow,
                adbReady = adbReady,
                programsRunning = runningIds.size,
                installedPrograms = installed,
                runningIds = runningIds,
                reasons = reasons,
                actions = acts,
                tickGapMs = gapMs,
                frozen = ResidencyPolicy.frozen(gapMs),
                anchorRebindAttempts = anchorRebindAttempts,
                startedAtMs = startedAtMs,
                tier = tier.tier.name.lowercase(),
                tierBasis = tier.basis,
                adbState = lobos.capability.AdbChannelComponent.stateName(),
                adbAttempts = lobos.capability.AdbChannelComponent.state().attempts,
            ),
        )
        lobos.os.ResidencyStatus.persist(this)
        if (reasons != degradedLast) {
            degradedLast = reasons
            RuntimeDiagnostics.append(
                this, "residency", reasons.isEmpty(),
                if (reasons.isEmpty()) "常驻状态正常" else "常驻状态降级（" + reasons.size + " 项）",
                lobos.os.ResidencyStatus.detail() + (if (acts.isEmpty()) "" else "；动作：" + acts.joinToString(" | ")),
            )
        }
    }

    private fun observeAnchorTransition(st: AnchorState) {
        if (st == AnchorState.UNKNOWN) return
        val bound = st == AnchorState.BOUND
        val prev = anchorBoundLastTick
        anchorBoundLastTick = bound
        if (prev == null || prev == bound) return
        if (bound) {
            RuntimeDiagnostics.append(
                this, "accessibility", true, "锚已回到位（闸门重开）",
                "系统完成重绑，判决回到 importance=accessibility",
            )
        } else {
            RuntimeDiagnostics.append(
                this, "accessibility", false, "判决降级告警：锚掉线",
                "锚不在位 = 判决停在 importance=traffic，随时被 o-kill；" +
                    "本设计不做复活，下一次挂锚的时机是进程重生（见 AccessibilityAnchor 的激活预算说明）",
            )
        }
    }

    override fun onDestroy() {
        runCatching { OsInit.transition(this, OsPhase.STOPPING, "宿主被销毁", ResidencyAudit.interruption()) }
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        try { pool?.shutdown() } catch (_: Throwable) {}
        try { broker?.shutdown() } catch (_: Throwable) {}
        try { capture?.shutdown() } catch (_: Throwable) {}
        pool = null
        broker = null
        capture = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, OsApplication.SUPERVISOR_CHANNEL_ID)
            .setContentTitle("Lob OS 常驻")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_lobos_logo)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val TAG = "OsHostService"
        private const val NOTIF_ID = 1004
        private const val REQ_OPEN = 41
        private const val TICK_MS = ResidencyPolicy.FAST_TICK_MS
        private const val NOTIFY_MS = 60_000L

        fun ensureRunning(context: Context) {
            try {
                context.startService(Intent(context, OsHostService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "拉起宿主失败（等互保闭环其它边重试）", e)
            }
        }
    }
}

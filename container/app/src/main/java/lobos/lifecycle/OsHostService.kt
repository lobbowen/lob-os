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
import lobos.capability.ScreenCaptureController
import lobos.capability.CapabilityEvidenceCollector
import lobos.os.Level
import lobos.os.OsFacts
import lobos.os.OsInit
import lobos.os.OsPhase
import lobos.os.ProgramDir
import lobos.os.ProgramIndex
import lobos.ui.setup.SetupActivity

class OsHostService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastNotifyMs = 0L

    private var pool: lobos.runtime.SupervisorPool? = null
    private var broker: CapabilityBroker? = null
    private var capture: ScreenCaptureController? = null

    private var lastTickMs = 0L
    private var startedAtMs = 0L
    private var adbReady = false
    private var degradedLast: List<String> = emptyList()
    private var silentGrantVerified = false
    private var deviceOwnerMeasured = false
    private var ownerSampledAt = 0L
    private var tierLast = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 采一次系统退出史。KillAudit.attribution 只有在 reading 被赋值后才说得出真话，
        // 而 reading 只能由 auditOnce 填。宿主服务一起来就先采一遍，
        // 这样引导页/工作台读 interruption() 时就有归因可显示，不会停在「死因未取证」。
        runCatching { lobos.log.KillAudit.auditOnce(this) }
            .onFailure { Log.w(TAG, "读系统退出史失败", it) }
        ResidencyAudit.auditPreviousExit(this)
        val born = thread == null
        if (born) {
            lobos.os.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption(this))
        }
        promoteToForeground()
        ensureComponents(intent)
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption(this))
            handler?.post(tick)
            runCatching {
                val ver = lobos.os.ProgramRegistry.listIds(this)
                    .mapNotNull { ProgramDir(this, it).currentVersion() }
                    .singleOrNull()
                RuntimeDiagnostics.append(
                    this, "host", true, "宿主不替任何应用决定生死（I1）",
                    "程序表由内核程序存储拥有；此处只观测：current=" + (ver ?: "无") +
                        "；已登记程序数=" + lobos.os.ProgramIndex.all(this).count { it.level == lobos.os.Level.APPLICATION },
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
            startForeground(NOTIF_ID, buildHostNotification())
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
                lobos.log.Journal.note(this, "residency", null, "节拍恢复（曾被冻结/回收）", "gapMs=" + gap)
            }
            registerPermissionLedger(now)
            val a11y = AccessibilityServiceState.state(this)
            publishResidency(gap, a11y)
            refreshStatusNotice(now, a11y)
            runCatching { pool?.sync() }
            sampleAdb(now)
            runCatching { lobos.native.DriverRegistry.ingest(this) }
            val nowWall = System.currentTimeMillis()
            if (!lobos.os.DozeBackstop.armedRecently(nowWall)) {
                val armed = lobos.os.DozeBackstop.schedule(this)
                lobos.log.Journal.note(
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

    private fun refreshStatusNotice(now: Long, a11y: ServiceState) {
        if (now - lastNotifyMs < NOTIFY_MS) return
        lastNotifyMs = now
        ResidencyAudit.heartbeat(this)
        val facts = OsFacts(
            readingsCollected = true,
            controlPlaneUp = runCatching { CapabilityEvidenceCollector.controlPlaneUp() }.getOrDefault(false),
            channel = lobos.capability.AdbChannelComponent.probeOutcome(),
        )
        OsInit.refresh(this, facts, ResidencyAudit.interruption(this))
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildHostNotification())
        }
    }

    private var ledgerRegisteredAtMs: Long = 0L

    private fun registerPermissionLedger(now: Long) {
        val prev = lobos.permissions.PermissionLedger.read(this)?.atMs ?: 0L
        if (prev > 0L && now - prev < LEDGER_INTERVAL_MS) return
        ledgerRegisteredAtMs = prev
        runCatching { lobos.permissions.PermissionLedger.register(this) }
    }

    private fun sampleAdb(now: Long) {
        pool?.tickComponents()
        adbReady = lobos.capability.AdbChannelComponent.isOnline()
        if (adbReady && !silentGrantVerified) {
            silentGrantVerified = runCatching {
                lobos.permissions.PermissionLedger.readAll(this).values
                    .any { it.outcome == lobos.capability.AttemptOutcome.SILENT_OK }
            }.getOrDefault(false)
        }
    }

    private fun publishResidency(gapMs: Long, a11y: ServiceState) {
        val protectedNow = a11y == ServiceState.BOUND
        val runningIds = runCatching { pool?.running() ?: emptyList<String>() }.getOrDefault(emptyList())
        val installed = runCatching {
            lobos.os.ProgramRegistry.list(this).count { it.startable }
        }.getOrDefault(0)
        val reasons = ResidencyPolicy.degradedReasons(
            accessibilityReady = protectedNow,
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
            lobos.log.Journal.note(this, "capability", null, "能力档位判定", tier.tier.name.lowercase())
        }
        lobos.os.ResidencyStatus.record(
            lobos.os.ResidencyStatus.Snapshot(
                accessibilityReady = protectedNow,
                adbReady = adbReady,
                programsRunning = runningIds.size,
                installedPrograms = installed,
                runningIds = runningIds,
                reasons = reasons,
                actions = acts,
                tickGapMs = gapMs,
                frozen = ResidencyPolicy.frozen(gapMs),
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

    override fun onDestroy() {
        runCatching { OsInit.transition(this, OsPhase.STOPPING, "宿主被销毁", ResidencyAudit.interruption(this)) }
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

    private fun buildHostNotification(): Notification {
        val status = lobos.os.ProgramStatusHub.summaryLine(this)
        val progNotice = lobos.os.ProgramNotificationHub.summaryLine()
        val text = listOfNotNull(status, progNotice).joinToString("　")
        return buildNotification(text)
    }

    // 系统级入口该去哪：配对没完成走引导页，已完成直接进控制面板。
    //
    // 之前硬编码 SetupActivity，导致用户配好通道、日常从桌面进控制面板之后，
    // 点通知仍被扔回"你还没配对"的引导页。判据复用 OnboardingFlow.readyToEnter
    // ——它已在 SetupActivity 用着，不另写一套判断。
    private fun entryActivity(): Class<*> {
        val ready = runCatching {
            val evidence = lobos.capability.Evidence(
                nowMs = System.currentTimeMillis(),
                channel = lobos.capability.AdbChannelComponent.asChannelProbe(),
            )
            val verdicts = lobos.capability.CapabilityCatalog.evaluate(evidence)
            lobos.setup.OnboardingFlow.readyToEnter(verdicts)
        }.getOrDefault(false)
        return if (ready) lobos.ui.PanelActivity::class.java else SetupActivity::class.java
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, entryActivity()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val summary = androidx.core.app.NotificationCompat.InboxStyle().setBigContentTitle("Lob OS")
        val items = mutableListOf<String>()
        for (n in lobos.os.ProgramStatusHub.snapshot(this).take(6)) {
            items += n.id + " · " + n.state.name.lowercase() +
                if (n.aliveMs > 0) " · " + (n.aliveMs / 3600000) + "h" + ((n.aliveMs / 60000) % 60) + "m" else ""
        }
        val notices = lobos.os.ProgramNotificationHub.list()
        for (n in notices.take(5)) items += n.title + "：" + n.text
        if (items.isNotEmpty()) {
            summary.setSummaryText(text)
            for (line in items) summary.addLine(line)
        }
        return NotificationCompat.Builder(this, OsApplication.SUPERVISOR_CHANNEL_ID)
            .setContentTitle("Lob OS 常驻")
            .setContentText(text)
            .setStyle(summary)
            .setGroup(lobos.os.ProgramNotificationHub.GROUP_PARENT)
            .setSubText(text)
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
        private const val LEDGER_INTERVAL_MS = 10 * 60_000L

        fun ensureRunning(context: Context) {
            try {
                context.startService(Intent(context, OsHostService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "拉起宿主失败（等互保闭环其它边重试）", e)
            }
        }
    }
}

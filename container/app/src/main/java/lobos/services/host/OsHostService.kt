package lobos.services.host

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
import lobos.R
import lobos.services.log.RuntimeDiagnostics
import lobos.api.CapabilityBroker
import lobos.device.DeviceOwnerState
import lobos.device.ScreenCaptureController
import lobos.services.log.Journal
import lobos.services.log.KillAudit
import lobos.kernel.proc.ProcessLedger
import lobos.services.power.DozeBackstop
import lobos.services.power.PowerHostHooks
import lobos.services.supply.ProgramDir
import lobos.services.reg.ResidencyStatus
import lobos.services.perm.PermissionLedger
import lobos.services.app.DriverRegistry
import lobos.services.supervise.SupervisorPool
import lobos.device.AccessibilityServiceState
import lobos.device.ServiceState

class OsHostService : Service() {

    private var thread: HandlerThread? = null

    @Volatile private var processBorn: Boolean = false
    @Volatile private var birthDone: Boolean = false
    private var handler: Handler? = null

    private var tickThread: HandlerThread? = null
    private var tickHandler: Handler? = null
    private var lastNotifyMs = 0L
    private var pool: lobos.services.supervise.SupervisorPool? = null
    private var broker: CapabilityBroker? = null
    private var capture: ScreenCaptureController? = null

    private var lastTickMs = 0L
    private var startedAtMs = 0L
    private var degradedLast: List<String> = emptyList()
    private var deviceOwnerMeasured = false
    private var ownerSampledAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        ResidencyAudit.auditPreviousExit(this)

        val born = !birthDone
        if (born) {
            processBorn = true
        }
        if (born) {

            runCatching { lobos.services.log.RuntimeDiagnostics.markLife(this) }

            runCatching { lobos.services.log.RuntimeDiagnostics.clear(this, "LIFE-MARK") }
            lobos.services.reg.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption(this))

            birthDone = true
        }
        promoteToForeground("onStartCommand")

        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)

            tickThread = HandlerThread("lobos-tick").apply { start() }
            tickHandler = Handler(tickThread!!.looper)
        }

        handler?.post {
            runCatching { lobos.services.log.KillAudit.auditOnce(this) }
                .onFailure { Log.w(TAG, "读系统退出史失败", it) }
        }

        handler?.post {
            ensureComponents(intent)
        }
        if (born) {
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption(this))

            tickHandler?.post(tick)
            runCatching {
                val ver = lobos.services.reg.ProgramRegistry.listIds(this)
                    .mapNotNull { ProgramDir(this, it).currentVersion() }
                    .singleOrNull()
                RuntimeDiagnostics.append(
                    this, "host", true, "宿主不替任何应用决定生死（I1）",
                    "程序表由宿主程序存储拥有；此处只观测：current=" + (ver ?: "无") +
                        "；已登记程序数=" + lobos.services.reg.ProgramIndex.all(this).count { it.levelOf(this) == lobos.services.reg.Level.PROGRAM },
                )
            }
            lobos.services.power.DozeBackstop.schedule(this)
            RuntimeDiagnostics.append(
                this, "host", true, "Lob OS 宿主就位（单进程 / 单前台服务）",
                "组件：SupervisorPool + CapabilityBroker + ScreenCaptureController；节拍 " + TICK_MS + "ms",
            )
            handler?.post {
                try {
                    lobos.services.reg.RuntimeEnvironment.ensure(this)
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

    private fun promoteToForeground(where: String) {
        var called = false
        var threw: String? = null
        try {
            called = true

            OsHostService.hostAlive = true

            lobos.kernel.fs.NotifyRoute.note(
                this, "host.startForeground", NOTIF_ID, "前台", "where=" + where,
            )
            startForeground(NOTIF_ID, buildHostNotification())
        } catch (t: Throwable) {
            threw = t::class.java.simpleName + ": " + t.message
            RuntimeDiagnostics.append(
                this, "host", false, "转前台失败",
                t::class.java.simpleName + ": " + t.message,
            )
        }

        val paired = Companion.takeFgsStart()
        val gap = if (paired != null) System.currentTimeMillis() - paired.at else -1L
        RuntimeDiagnostics.append(
            this, "host", threw == null, "转前台那一族跑过了",
            "调用处=" + where + " 已调用 startForeground=" + called + " 抛出=" + (threw ?: "无") +
                " 配对=" + (paired?.marker ?: "无") +
                " 距FGSstart毫秒=" + gap,
        )
    }

    private fun ensureComponents(intent: Intent?) {

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
        try {
            val p = pool ?: lobos.services.supervise.SupervisorPool(this).also { pool = it; it.start() }
            p.onHostStart(intent)
        } catch (t: Throwable) {
            RuntimeDiagnostics.append(this, "runtime", false, "运行时实例组件异常", t::class.java.simpleName + ": " + t.message)
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
                lobos.services.log.Journal.note(this, "residency", null, "节拍恢复（曾被冻结/回收）", "gapMs=" + gap)
            }
            registerPermissionLedger(now)
            val a11y = AccessibilityServiceState.state(this)
            publishResidency(gap, a11y)
            refreshStatusNotice(now, a11y)
            runCatching { pool?.sync() }
            runCatching { lobos.services.app.DriverRegistry.ingest(this) }
            val nowWall = System.currentTimeMillis()
            if (!lobos.services.power.DozeBackstop.armedRecently(nowWall)) {
                val armed = lobos.services.power.DozeBackstop.schedule(this)
                lobos.services.log.Journal.note(
                    this, "doze", armed, "兜底闹钟未按期投递：已重挂",
                    "armed=" + armed + "（看门狗每拍校验布防）",
                )
            }
            if (now - ownerSampledAt > ResidencyPolicy.OWNER_CHECK_TTL_MS) {
                ownerSampledAt = now
                deviceOwnerMeasured = runCatching {
                    lobos.device.DeviceOwnerState.measure(this).isDeviceOwner
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "宿主节拍异常（不致命，下一拍继续）", e)
        } finally {
            tickHandler?.postDelayed(tick, ResidencyPolicy.FAST_TICK_MS)
        }
    }

    private fun refreshStatusNotice(now: Long, a11y: ServiceState) {
        if (now - lastNotifyMs < NOTIFY_MS) return
        lastNotifyMs = now
        ResidencyAudit.heartbeat(this)
        val facts = OsFacts(
            readingsCollected = true,
            controlPlaneUp = runCatching {
                val snap = lobos.services.reg.ResidencyStatus.snapshot()
                val at = snap.optLong("updatedAt", 0L)
                lobos.kernel.time.Heartbeat.fresh(at, System.currentTimeMillis())
            }.getOrDefault(false),
        )
        OsInit.refresh(this, facts, ResidencyAudit.interruption(this))

        val text = currentNotificationText()
        if (text != lastNotifiedText) {
            lastNotifiedText = text
            runCatching {

                lobos.kernel.fs.NotifyRoute.note(
                    this, "host.notify", NOTIF_ID, "前台", "（内容**变了**才逐字重发）",
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIF_ID, buildHostNotification(text))
            }
        }

    }

    private fun currentNotificationText(): String {
        val status = lobos.services.reg.ProgramStatusHub.summaryLine(this)
        val progNotice = lobos.services.reg.ProgramNotificationHub.summaryLine()
        return listOfNotNull(status, progNotice).joinToString("　")
    }

    private var ledgerRegisteredAtMs: Long = 0L

    private fun registerPermissionLedger(now: Long) {
        val prev = lobos.services.perm.PermissionLedger.read(this)?.atMs ?: 0L
        if (prev > 0L && now - prev < LEDGER_INTERVAL_MS) return
        ledgerRegisteredAtMs = prev
        runCatching { lobos.services.perm.PermissionLedger.register(this) }
    }

    private fun publishResidency(gapMs: Long, a11y: ServiceState) {
        val a11yConnected = a11y == ServiceState.BOUND

        val liveEntries = runCatching {
            lobos.kernel.proc.ProcessLedger.liveOwned(this)
        }.getOrDefault(emptyList())
        val runningIds = liveEntries.map { it.programId }.distinct()
        val ledgerAll = runCatching {
            lobos.kernel.proc.ProcessLedger.list(this)
        }.getOrDefault(emptyList())
        val runningPids = liveEntries.map { it.pid }
        val installedList = runCatching {
            lobos.services.reg.ProgramRegistry.list(this).filter { it.startable }
        }.getOrDefault(emptyList())
        val installed = installedList.size
        val installedIds = installedList.map { it.id }
        val reasons = ResidencyPolicy.degradedReasons(
            programsRunning = runningIds.size,
            installedPrograms = installed,
        )
        val acts = ResidencyPolicy.actions(reasons)
        lobos.services.reg.ResidencyStatus.record(
            lobos.services.reg.ResidencyStatus.Snapshot(
                accessibilityReady = a11yConnected,
                programsRunning = runningIds.size,
                installedPrograms = installed,
                runningIds = runningIds,
                reasons = reasons,
                actions = acts,
                tickGapMs = gapMs,
                frozen = ResidencyPolicy.frozen(gapMs),
                startedAtMs = startedAtMs,

                runningPids = runningPids,
                ledgerEntries = ledgerAll.map {
                    "pid=" + it.pid + " program=" + it.programId +
                        " gen=" + it.generation + " starttime=" + it.starttime
                },
                installedIds = installedIds,
            ),
        )
        lobos.services.reg.ResidencyStatus.persist(this)
        if (reasons != degradedLast) {
            degradedLast = reasons
            RuntimeDiagnostics.append(
                this, "residency", reasons.isEmpty(),
                if (reasons.isEmpty()) "常驻状态正常" else "常驻状态降级（" + reasons.size + " 项）",
                lobos.services.reg.ResidencyStatus.detail() + (if (acts.isEmpty()) "" else "；动作：" + acts.joinToString(" | ")),
            )
        }
    }

    override fun onDestroy() {

        val teardownStep = { step: String ->
            RuntimeDiagnostics.append(this, "host", true, "宿主拆解进度: " + step, "onDestroy 已走到=" + step)
        }
        runCatching { OsInit.transition(this, OsPhase.STOPPING, "宿主被销毁", ResidencyAudit.interruption(this)) }
        teardownStep("已写宿主被销毁")
        handler?.removeCallbacksAndMessages(null)
        tickHandler?.removeCallbacksAndMessages(null)
        teardownStep("已清回调")
        thread?.quitSafely()
        tickThread?.quitSafely()
        teardownStep("已退线程")
        thread = null
        handler = null
        tickThread = null
        tickHandler = null
        try { pool?.shutdown() } catch (_: Throwable) {}
        teardownStep("已关监督池")
        try { broker?.shutdown() } catch (_: Throwable) {}
        teardownStep("已关能力中介")
        try { capture?.shutdown() } catch (_: Throwable) {}
        teardownStep("已关截屏")
        pool = null
        broker = null
        capture = null
        super.onDestroy()
        teardownStep("已全部拆完")
    }

    private fun buildHostNotification(text: String = currentNotificationText()): Notification =
        buildNotification(text)

    private fun entryActivity(): Class<*> =
        lobos.services.app.QuickAppLaunchActivity::class.java

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, entryActivity()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val summary = androidx.core.app.NotificationCompat.InboxStyle().setBigContentTitle("Lob OS")
        val items = mutableListOf<String>()
        for (n in lobos.services.reg.ProgramStatusHub.snapshot(this).take(6)) {
            items += n.id + " · " + n.active.name.lowercase() +
                if (n.aliveMs > 0) " · " + (n.aliveMs / 3600000) + "h" + ((n.aliveMs / 60000) % 60) + "m" else ""
        }
        val notices = lobos.services.reg.ProgramNotificationHub.list()
        for (n in notices.take(5)) items += n.title + "：" + n.text
        if (items.isNotEmpty()) {
            summary.setSummaryText(text)
            for (line in items) summary.addLine(line)
        }
        return NotificationCompat.Builder(this, OsApplication.SUPERVISOR_CHANNEL_ID)
            .setContentTitle("Lob OS 常驻")
            .setContentText(text)
            .setStyle(summary)
            .setGroup(lobos.services.reg.ProgramNotificationHub.GROUP_PARENT)
            .setSubText(text)
            .setSmallIcon(R.drawable.ic_lobos_logo)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object {
        const val TAG = "OsHostService"
        private const val NOTIF_ID = 1004

        @Volatile private var lastNotifiedText: String? = null
        private const val REQ_OPEN = 41
        private const val TICK_MS = ResidencyPolicy.FAST_TICK_MS
        private const val NOTIFY_MS = 60_000L
        private const val LEDGER_INTERVAL_MS = 10 * 60_000L

        @Volatile private var hostAlive = false

        fun ensureRunning(context: Context, action: String? = null) {

            if (hostAlive && action.isNullOrBlank()) return
            try {

                val svc = Intent(context, OsHostService::class.java)
                if (!action.isNullOrBlank()) svc.action = action
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    noteFgsStart(context, "ensure")

                    try {
                        context.startForegroundService(svc)
                    } catch (e: IllegalStateException) {

                        context.startService(svc)
                    }
                } else {
                    context.startService(svc)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "拉起宿主失败（等互保闭环其它边重试）", e)
                RuntimeDiagnostics.append(
                    context, "host", false, "拉起宿主失败",
                    e.javaClass.name + ": " + (e.message ?: ""),
                )
            }
        }

        fun noteFgsStart(context: Context, path: String) {
            val at = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()

            val marker = path + "#" + at
            RuntimeDiagnostics.append(
                context, "fgs-start", true, "FGS start 那一拍",
                "起算时刻 at=" + at + " elapsedRealtime=" + elapsed + " 路径=" + path +
                    " 配对=" + marker,
            )
            fgsStartMarker = FgsStart(at, marker)
        }

        class FgsStart(val at: Long, val marker: String)

        @Volatile
        private var fgsStartMarker: FgsStart? = null

        fun takeFgsStart(): FgsStart? {
            val m = fgsStartMarker
            fgsStartMarker = null
            return m
        }
    }
}

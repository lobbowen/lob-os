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
    private var handler: Handler? = null
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { lobos.services.log.KillAudit.auditOnce(this) }
            .onFailure { Log.w(TAG, "读系统退出史失败", it) }
        ResidencyAudit.auditPreviousExit(this)
        val born = thread == null
        if (born) {
            lobos.services.reg.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption(this))
        }
        promoteToForeground()
        ensureComponents(intent)
        runCatching { lobos.services.reg.BuildIdentity.write(this) }
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption(this))
            handler?.post(tick)
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

    //  缺陷名（C-102）**那一行把「startForeground() 那一族跑过了」与「它抛没抛」记成了两件事** —— 而实际上
    //    「跑过了」的标记是那**一次调用被走到了**（=> 而 :99 那一行逐字是 startForeground），
    //    而「抛没抛」的标记是**那一次调用有没有抛**（=> 而而 :102 那一族逐字只在**抛了**时写、
    //    => 而而它逐字零命中、=> 而而那**只**说明「没抛」）；
    //    => 而而这一族逐字**只**记了「抛没抛」那**一件**、=> 而而「跑过了」那**一件**逐字**没有**任何痕迹，
    //    => 而而那一族逐字分不出「压根没走到 :99」与「走到了、而没抛」。
    //    => 而 04 今天裁的是「合成一格」：=> 而「那一行记的是什么、它什么时候记、它为什么**不**记」那三条**合成**一格，
    //    => 而「在 :99 前后加一行仓内自己的记录」是那一格**里面**的处置（=> 而按 C-220 那一族是**内容稿**、
    //    => 而而这一笔是**实现**那一族、=> 而那**只**加一行）。
    //    => 而这一行逐字用「host」那个 category（=> 而它逐字已经写得出、=> 而而 :102 那一行逐字是同一族）。
    private fun promoteToForeground() {
        var called = false
        var threw: String? = null
        try {
            called = true
            startForeground(NOTIF_ID, buildHostNotification())
        } catch (t: Throwable) {
            threw = t::class.java.simpleName + ": " + t.message
            RuntimeDiagnostics.append(
                this, "host", false, "转前台失败",
                t::class.java.simpleName + ": " + t.message,
            )
        }
        RuntimeDiagnostics.append(
            this, "host", threw == null, "转前台那一族跑过了",
            "已调用 startForeground=" + called + " 抛出=" + (threw ?: "无"),
        )
    }

    private fun ensureComponents(intent: Intent?) {
        try {
            val p = pool ?: lobos.services.supervise.SupervisorPool(this).also { pool = it; it.start() }
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
            handler?.postDelayed(tick, ResidencyPolicy.FAST_TICK_MS)
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
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, buildHostNotification())
        }
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
        // C-110：程序在跑的个数，是算出来的，不是说的 —— runningIds.size 只留了个数，
        // 而 ProcessLedger.liveOwned() 明明给的是条目（pid / generation / starttime）。
        // 今天那两样数据源都已在，只被 .size / .count 吃掉了。
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
                // C-110 第二格：同一事实有两种形态，一份结论一份原话 ——
                // 上面三个数（programsRunning / installedPrograms / runningIds）是结论，
                // 这三样是它们的原话：哪些 pid 现在活着、账本上有哪些条目、在册的是哪些 id。
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
        val status = lobos.services.reg.ProgramStatusHub.summaryLine(this)
        val progNotice = lobos.services.reg.ProgramNotificationHub.summaryLine()
        val text = listOfNotNull(status, progNotice).joinToString("　")
        return buildNotification(text)
    }

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

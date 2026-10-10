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
import lobos.capability.DeviceOwnerState
import lobos.capability.ScreenCaptureController
import lobos.log.Journal
import lobos.log.KillAudit
import lobos.os.DozeBackstop
import lobos.os.Level
import lobos.os.OsFacts
import lobos.os.OsInit
import lobos.os.OsPhase
import lobos.kernel.proc.ProcessLedger
import lobos.os.ProgramDir
import lobos.os.ProgramIndex
import lobos.os.ProgramNotificationHub
import lobos.os.ProgramRegistry
import lobos.os.ProgramStatusHub
import lobos.os.ResidencyStatus
import lobos.os.RuntimeEnvironment
import lobos.permissions.PermissionLedger
import lobos.pieces.DriverRegistry
import lobos.runtime.SupervisorPool

class OsHostService : Service() {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastNotifyMs = 0L

    private var pool: lobos.runtime.SupervisorPool? = null
    private var broker: CapabilityBroker? = null
    private var capture: ScreenCaptureController? = null

    private var lastTickMs = 0L
    private var startedAtMs = 0L
    private var degradedLast: List<String> = emptyList()
    private var deviceOwnerMeasured = false
    private var ownerSampledAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    "程序表由宿主程序存储拥有；此处只观测：current=" + (ver ?: "无") +
                        "；已登记程序数=" + lobos.os.ProgramIndex.all(this).count { it.level == lobos.os.Level.PROGRAM },
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
            runCatching { lobos.pieces.DriverRegistry.ingest(this) }
            val nowWall = System.currentTimeMillis()
            if (!lobos.os.DozeBackstop.armedRecently(nowWall)) {
                val armed = lobos.os.DozeBackstop.schedule(this)
                lobos.log.Journal.note(
                    this, "doze", armed, "兜底闹钟未按期投递：已重挂",
                    "armed=" + armed + "（看门狗每拍校验布防）",
                )
            }
            if (now - ownerSampledAt > ResidencyPolicy.OWNER_CHECK_TTL_MS) {
                ownerSampledAt = now
                deviceOwnerMeasured = runCatching {
                    lobos.capability.DeviceOwnerState.measure(this).isDeviceOwner
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
            // 控制面在不在：直接问 ResidencyStatus 的心跳新鲜度（60 秒内更新过）。
            // 原本经 CapabilityEvidenceCollector —— 判据体系已清空，而
            // 「控制面是否在线」本来就是宿主自己的心跳事实，不该绕道判据。
            controlPlaneUp = runCatching {
                val snap = lobos.os.ResidencyStatus.snapshot()
                val at = snap.optLong("updatedAt", 0L)
                at > 0L && System.currentTimeMillis() - at < 60_000L
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
        val prev = lobos.permissions.PermissionLedger.read(this)?.atMs ?: 0L
        if (prev > 0L && now - prev < LEDGER_INTERVAL_MS) return
        ledgerRegisteredAtMs = prev
        runCatching { lobos.permissions.PermissionLedger.register(this) }
    }

    private fun publishResidency(gapMs: Long, a11y: ServiceState) {
        // 变量名刻意不叫「protected」：无障碍是 UI 自动化的执行体，
        // 不是托住后台的锚（托住后台的是前台服务 + Doze 兜底闹钟）。
        // 这里只如实记录「服务当前是否已连」，供权限服务与控制面板查询。
        val a11yConnected = a11y == ServiceState.BOUND
        // 「在跑」问**账本**，不读监管池的内存名单 ——
        // 池子那份是「谁有监管器」，不是「进程在跑」：进程自己 daemonize
        // 出去时账本知道而池子不知道，反之池子留着 key 但进程早没了也有。
        // 对外字段名仍叫 runningIds（CapabilityBroker 在读这个键），
        // 但值必须是真机事实，否则面板会报一个不存在的运行态。
        val runningIds = runCatching {
            lobos.kernel.proc.ProcessLedger.liveOwned(this).map { it.programId }.distinct()
        }.getOrDefault(emptyList())
        val installed = runCatching {
            lobos.os.ProgramRegistry.list(this).count { it.startable }
        }.getOrDefault(0)
        val reasons = ResidencyPolicy.degradedReasons(
            programsRunning = runningIds.size,
            installedPrograms = installed,
        )
        val acts = ResidencyPolicy.actions(reasons)
        lobos.os.ResidencyStatus.record(
            lobos.os.ResidencyStatus.Snapshot(
                accessibilityReady = a11yConnected,
                programsRunning = runningIds.size,
                installedPrograms = installed,
                runningIds = runningIds,
                reasons = reasons,
                actions = acts,
                tickGapMs = gapMs,
                frozen = ResidencyPolicy.frozen(gapMs),
                startedAtMs = startedAtMs,
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

    /**
     * 通知点开后去哪儿。
     *
     * 原本是「判据全过就去 PanelActivity，没过就去 SetupActivity」——
     * 那是「APK 侧自己判权限、自己取权、自己引导」的旧设计。UI 随那一并清空了，
     * 这里统一走启动入口（QuickAppLaunchActivity），由它拉起对应的 Program
     * （控制面板就是其中之一）。
     */
    private fun entryActivity(): Class<*> =
        lobos.quickapp.QuickAppLaunchActivity::class.java

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, entryActivity()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val summary = androidx.core.app.NotificationCompat.InboxStyle().setBigContentTitle("Lob OS")
        val items = mutableListOf<String>()
        for (n in lobos.os.ProgramStatusHub.snapshot(this).take(6)) {
            // 状态是 systemctl 的三列，不是一个自造的 state —— 取 active 那列
            items += n.id + " · " + n.active.name.lowercase() +
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

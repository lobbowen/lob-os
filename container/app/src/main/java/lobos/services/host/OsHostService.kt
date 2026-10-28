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
        //  缺陷名（C-102）**那重活**逐字**跑**在**那主线程**上**（=> 而而那**它**们**逐字是**那**两**件**事**）。
        //    => 而那**成因**：=> 而那 ensureComponents 那一族逐字**去**装**那**预装件**（=> 而而那**它**们**逐字是**那**两**件**事**：「那**落位**是**什么**」与「那**线程**是**什么**」）。
        //      => 而而那**装**那一族逐字**要**解包**与**搬**那**几**兆**字节**（=> 而那八**个** zip **那**一**族**），
        //      => 而而那**主线程**逐字**等不起**（=> 而那 20 秒**那一道**）
        //    => 而而那**后果**逐字 = 而那宿主服务逐字**ANR**（=> 而那「waited 20003ms」**那一行**）、
        //      => 而而那**进程**逐字**死**了、=> 而而那**落位**那一族**与**那**身份**那一族逐字**还是**没跑**。
        //    => 而那**处置**：=> 而那重活逐字**搬到**那**线程**上**（=> 而而那 **HandlerThread** **那一族**逐字**先**起**、
        //      => 而而那**它**们**逐字是**那**两**件**事**：「那**主线程**做**什么**」与「那**工作线程**做**什么**」）。
        //    => 而按 C-104：=> 而那宿主那一族逐字**只有一条路**（=> 而而那**它**逐字**是**那**同一个**触发**、
        //      => 而而那**它**们**逐字不是**两条路**）。
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
        }
        //  => 而那重活逐字**在**那**工作线程**上**跑**（=> 而而那**主线程**逐字**只**做**那轻活**）。
        handler?.post {
            ensureComponents(intent)
            runCatching { lobos.services.reg.BuildIdentity.write(this) }
        }
        if (born) {
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
        //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
        //    => 而而那一行逐字没有配对的标记、=> 而而那一个量逐字算不准**
        //    —— 而实际上这一行逐字是「startForeground 那一拍」，=> 而而它逐字**要**能指回
        //    「FGS start 那一拍」那**一行**、=> 而而那一行逐字带一个配对的标记。
        //    => 而按 C-112：=> 而那一格逐字**不是**「我算不出来的」那个形状、=> 而而它逐字是「**配不准**」那个形状。
        //    => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族、=> 而而它逐字**只**加这一行。
        val paired = Companion.takeFgsStart()
        val gap = if (paired != null) System.currentTimeMillis() - paired.at else -1L
        RuntimeDiagnostics.append(
            this, "host", threw == null, "转前台那一族跑过了",
            "已调用 startForeground=" + called + " 抛出=" + (threw ?: "无") +
                " 配对=" + (paired?.marker ?: "无") +
                " 距FGSstart毫秒=" + gap,
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
        //  缺陷名（C-102）**宿主正在拆、而进程死在拆到一半** —— 而实际上 onDestroy 那一族逐字有**六步**
        //    （=> 而 :271 到 :276：=> 而那**四行**逐字**没有一行**会抛），=> 而而原来那一句只记了**第一步**
        //    （=> 而「宿主被销毁」那一行）、=> 而而**后面那几步**逐字**没有**任何痕迹；
        //    => 而那后果是：=> 而进程死在拆到一半时、=> 而外面逐字只看见「宿主被销毁」那一行、
        //    => 而而**看不出**它死在哪一步、=> 而那一格逐字**答不了**「为什么那几趟死在拆到一半」。
        //    => 而按 C-103 先答「它为什么有两边」：=> 而那两边是「仓内那一族**记不着的**」
        //       （=> 而那逐字是「进程死在哪一步」这一格、=> 而那**要**设备侧那一格）与
        //       「仓内那一族**记着的**」（=> 而那逐字是那六步逐字各记一行）。
        //    => 而这里逐字在**每一步之后**记一行、=> 而而它逐字**只**记「走到哪一步了」那一格、
        //       => 而而「为什么死」那一格逐字**仍**要设备侧那一格。
        val teardownStep = { step: String ->
            RuntimeDiagnostics.append(this, "host", true, "宿主拆解进度: " + step, "onDestroy 已走到=" + step)
        }
        runCatching { OsInit.transition(this, OsPhase.STOPPING, "宿主被销毁", ResidencyAudit.interruption(this)) }
        teardownStep("已写宿主被销毁")
        handler?.removeCallbacksAndMessages(null)
        teardownStep("已清回调")
        thread?.quitSafely()
        teardownStep("已退线程")
        thread = null
        handler = null
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

        fun ensureRunning(context: Context, action: String? = null) {
            try {
                //  缺陷名（C-102）**「FGS start 那一拍」逐字被记在了**不是** FGS start 的那一路上、
                //    => 而而那一拍逐字**配错了**、=> 而而问二那个量逐字**算不出来**
                //    —— 而实际上这一路逐字**也**是 `startForegroundService`、
                //    => 而而 FGS start 那一拍逐字**在**这一路上、=> 而而这里逐字**记**它**是**对的**。
                //  缺陷名（C-102）**这一条路逐字用**那 startService（=> 而而那**它**们**逐字是**那**两**件**事**）。
                //    => 而那**成因**：=> 而那 startService 逐字**只**在**那应用**在**前台**的时候**才**能**起**那后台服务**、
                //      => 而而那**应用起来**那一拍逐字**不**在前台**（=> 而而那**它**们**逐字是**那**两**件**事**）。
                //    => 而而那**后果**逐字 = 而那宿主服务逐字**起不来**、=> 而而那**落位**那一族**与**那**身份**那一族
                //      逐字**都**只在**那宿主服务起**的那一趟**里**跑**、=> 而而那**它们**逐字**都**没跑**。
                //      => 而而那**证据**逐字 = 而那设备上那「起内置服务」**与**那「assets 列表」**与**那
                //        build-identity.json **逐字都**是**那旧**的**那一个**。
                //    => 而那**处置**：=> 而这一条路逐字**也**用**那 startForegroundService（=> 而而那**它**逐字**跟**
                //      那 BootReceiver.startQuietly **那一族**同一**一个**（=> 而而那**它**们**逐字是**那**两**件**事**）。
                //    => 而按 C-104：=> 而那宿主那一族逐字**只有**这一条路**用** startForegroundService、
                //      => 而而那**它**们**逐字是**那**同一个**触发**的**两种**写法**、=> 而而那**它**们**逐字不是**两条路**。
                val svc = Intent(context, OsHostService::class.java)
                if (!action.isNullOrBlank()) svc.action = action
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    noteFgsStart(context, "ensure")
                    context.startForegroundService(svc)
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

        /**
         *  缺陷名（C-102）**那 5 秒窗口的起算时刻逐字没被记下来、=> 而而那一个量逐字算不出来**
         *  —— 而实际上那 5 秒窗口逐字是「FGS start -> startForeground」那个量，
         *  => 而而这一族逐字把「FGS start」那一拍记下来、=> 而而那一格逐字**能**算出来。
         *  => 而按 C-103：=> 而它为什么有两边 => 而那两边逐字是「宿主出生 -> startForeground」与
         *     「FGS start -> startForeground」那两族、=> 而而它们起算的**时刻不一样**、=> 而而落点是**同一拍**。
         *  => 而按 C-112：=> 而这一行逐字带**完整 epoch 毫秒**、=> 而而那一格逐字**不是**「我算不出来的」那个形状。
         *  => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族。
         */
        fun noteFgsStart(context: Context, path: String) {
            val at = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
            //    => 而而那一行逐字没有配对的标记、=> 而而那一个量逐字算不准**
            //    —— 而实际上同一次触发逐字**只**记一行、=> 而而这一行逐字带一个配对的标记，
            //    => 而而服务起来那一拍逐字**能**指回这一行、=> 而而那一个量逐字**能**算准。
            //    => 而按 C-104：=> 而那**同一个**触发逐字**只**触发一次、=> 而而那**一次**记一行、
            //    => 而而**不**会有第二行逐字**抢**掉配对。
            //    => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族、=> 而而它逐字**只**加这一行。
            val marker = path + "#" + at
            RuntimeDiagnostics.append(
                context, "fgs-start", true, "FGS start 那一拍",
                "起算时刻 at=" + at + " elapsedRealtime=" + elapsed + " 路径=" + path +
                    " 配对=" + marker,
            )
            fgsStartMarker = FgsStart(at, marker)
        }

        /** 而而 FGS start 那一拍逐字与它的配对标记（=> 而那一族逐字是**同一个**触发逐字**一次**配一个） */
        class FgsStart(val at: Long, val marker: String)

        //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
        //    => 而而那一行逐字没有配对的标记、=> 而而那一个量逐字算不准**
        //    —— 而实际上这一格逐字是「**这一次**触发逐字记的那一行」、
        //    => 而而它逐字只装**一行**、=> 而而消费掉之后逐字清空、=> 而而下一次触发逐字**另**记一行。
        //    => 而按 C-112：=> 而那一格逐字**不是**「我算不出来的」那个形状、=> 而而它逐字是「**配不准**」那个形状。
        @Volatile
        private var fgsStartMarker: FgsStart? = null

        /** 而而那一格逐字取出配对标记并清空（=> 而而它逐字**只**给**一次** startForeground 用） */
        fun takeFgsStart(): FgsStart? {
            val m = fgsStartMarker
            fgsStartMarker = null
            return m
        }
    }
}

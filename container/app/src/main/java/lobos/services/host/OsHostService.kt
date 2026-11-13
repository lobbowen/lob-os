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

    //  缺陷名 C-102：旧版 `val born = thread == null` 表达的是「这进程没被销毁过」，不是「第一次起来」。
    //    `thread` 只在 onDestroy（`:378`）变 null，而 onDestroy 只在服务被正常拆解时跑，系统杀进程不走它，
    //    于是 thread 永不为 null、born 恒为 false、`OsInit.beginLife` 永不被调：`events.jsonl` 的 os-phase /
    //    宿主出生 / os-run 都 0，`files/var/lib/state.json` phase=DEGRADED（不是 beginLife 写的 BOOTING），
    //    diag「宿主拆解进度」0。正解：「这进程是第一次起来」是进程自己的状态，不由「有没有被销毁过」推出。
    @Volatile private var processBorn: Boolean = false
    @Volatile private var birthDone: Boolean = false
    private var handler: Handler? = null
    //  tick 走自己的队列（不跟装件那一族抢）。
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

    //  缺陷名 C-102：旧版 startForeground 只在 onStartCommand 里。成因：startForegroundService 被系统拒了
    //    （mAllowStartForeground false）时服务没起、onStartCommand 没跑、startForeground 没被调，系统等不到
    //    就 ANR（「bg anr: … did not then call Service.startForeground()」）。
    //  处置：startForeground 搬到 onCreate（服务起来的第一个时刻，不等 onStartCommand）。
    override fun onCreate() {
        super.onCreate()
        promoteToForeground("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //  缺陷名 C-102：退出史在主线程上取（`KillAudit.auditOnce` 调 `getHistoricalProcessExitReasons`，
        //    那是系统服务的同步 IPC）。后果：宿主 ANR（「waited 20001ms: executing service …OsHostService」）被杀。
        //  处置：退出史搬到工作线程上取，主线程只做轻活；要在 handler 起好之后。
        ResidencyAudit.auditPreviousExit(this)
        //  born 那一格改成「这进程是第一次起来」
        //    （ **不是**「这进程没被销毁过」、 **不是** `thread == null`）。
        val born = !birthDone
        if (born) {
            processBorn = true
        }
        if (born) {
            //  诊断的「上一世」那一份先清（只清上一世、不清这一世写的行），必须在 beginLife 之前——
            //    beginLife 写的「宿主出生」「索引已从落位重建」是这一世的、要留住。旧版它原来在服务池那一族
            //    （`SupervisorPool.start()` 的 `RuntimeDiagnostics.clear(host)`，在工作线程上）而出生在主线程上，
            //    两条链竞态、服务池先到，把 beginLife 写的那一档清掉了（07 核到 journal at 1793483983860、
            //    而 diag.jsonl 最早一条 at 1793483984570，晚 710 ms）。
            //  出生标记先记下（它是「上一世/这一世」的分界），也必须在 beginLife 之前；clear 只清上一世（01 那一裁）。
            runCatching { lobos.services.log.RuntimeDiagnostics.markLife(this) }
            //  clear 只清上一世（按那标记切）
            //    标记之后才清（这是 01 那一裁）
            runCatching { lobos.services.log.RuntimeDiagnostics.clear(this, "LIFE-MARK") }
            lobos.services.reg.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption(this))
            //  出生做完就记下（是进程自己的状态，不由「有没有被销毁过」推出）；第二次 onStartCommand 不再走出生那一族
            //    （beginLife 只跑一次），而同一进程里 onStartCommand 可以再被调（服务的常态）。
            birthDone = true
        }
        promoteToForeground("onStartCommand")
        //  缺陷名 C-102：重活跑在主线程上。成因：ensureComponents 去装预装件，要解包与搬那几兆字节（八个 zip），
        //    主线程等不起那 20 秒。后果：宿主 ANR（「waited 20003ms」）、进程死了，落位与身份都没跑。
        //  处置：重活搬到 HandlerThread 上（先起线程），主线程只做轻活。按 C-104：宿主只有一条路。
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
            //  tick 用自己那一个 HandlerThread。
            tickThread = HandlerThread("lobos-tick").apply { start() }
            tickHandler = Handler(tickThread!!.looper)
        }
        //  退出史在工作线程上取（不在主线程上等系统服务那一边）。
        handler?.post {
            runCatching { lobos.services.log.KillAudit.auditOnce(this) }
                .onFailure { Log.w(TAG, "读系统退出史失败", it) }
        }
        //  重活在工作线程上跑（主线程只做轻活）。
        //  缺陷名 C-102：身份那一族不该跟落位的成败绑在一起。成因：`BuildIdentity.write` 在这块里，前面是
        //    `ensureComponents(intent)`，落位一旦中途断了就跑不到身份那一行，后果是身份还是旧的 sha。
        //  处置：身份自己写（在 `OsApplication.onCreate` 那一拍），不跟落位成败绑在一起。
        handler?.post {
            ensureComponents(intent)
        }
        if (born) {
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption(this))
            //  缺陷名 C-102：旧版 tick 与装件在同一队列上（ensureComponents 在 `:106` post 到 handler 队列、
            //    tick 在 `:111` post 到同一队列）。后果：装件一挂住就把 tick 饿死，`ResidencyStatus.record` 不再被调、
            //    updatedAt 陈旧、`Heartbeat.fresh` 答 false、controlPlaneUp 落 false。
            //  处置：tick 走自己的队列（`tickHandler`），不跟装件抢。
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

    //  缺陷名 C-102：旧版把「startForeground() 跑过了」与「它抛没抛」记成了两件事——「跑过了」的标记是那一次
    //    调用被走到了（`:99`），「抛没抛」的标记只在抛了时写（`:102`），零命中只说「没抛」，分不出「压根没走到
    //    :99」与「走到了、没抛」。04 今天裁的是合成一格（那一行记什么、什么时候记、为什么不记），处置是在 :99
    //    前后加一行仓内自己的记录（C-220 那一族是内容稿、这一笔是实现）。这一行用 "host" 那个 category。
    private fun promoteToForeground(where: String) {
        var called = false
        var threw: String? = null
        try {
            called = true
            //  宿主活着就记着（幂等那一格靠它判）。
            OsHostService.hostAlive = true
            //  发法名答的是「那一条路」，记录落进诊断（下一趟靠它就能归到路、不用抓栈）。
            //  系统侧记的那一条（`NotificationManagerService:post:lobos.os`）是系统记的我们这个 uid 发过一次，
            //  不是我们主动发的。
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
        //  缺陷名 C-102：startForeground 那一拍没有配对的标记，一个量算不准。这一行要能指回「FGS start 那一拍」，
        //    一行带一个配对的标记。按 C-112：这不是「算不出来」那个形状，是「配不准」；按 C-220 这一笔只加这一行。
        //  缺陷名 C-102：旧版那条读数记不出调用处——promoteToForeground 从 onCreate 与 onStartCommand 各调一次，
        //    记的是同一条消息、分不清哪一次。后果：应用自己记的「已调用 startForeground=true」与系统判的
        //    「did not then call Service.startForeground()」并排。处置：一条读数带调用处，配对带编号（按 C-112，
        //    两条都是系统那边说的、不互相顶替）。
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
        //  缺陷名 C-102：能力桥起在装件之后。成因：`pool.onHostStart` 调 sync（`syncServices` 调 `d.act(host)`，
        //    piece-<id> 的 act 就是 `installOneById`），装件是同一队列上的长活、把后面几步饿死，broker.start() 起不来。
        //    后果：LocalServerSocket 没在听，M6 那一格还测不了。处置：能力桥先起（不等装件），它是短活。
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
        //  缺陷名 C-102：旧版每一拍都重算状态快照（`.notify(NOTIF_ID, buildHostNotification())` 每拍调
        //    `ProgramStatusHub.snapshot(this)`，把每一条程序状态读一遍）。常驻通知保留（Lead 裁「保留前台服务、
        //    保留常驻通知」），状态快照改成按需（它答「此刻状态是什么」、不要求进程一直在）。
        //  处置：通知按需重算，只在内容变了时重发；没变不重算、快照不被翻（没变那一档不产生耗）。
        val text = currentNotificationText()
        if (text != lastNotifiedText) {
            lastNotifiedText = text
            runCatching {
                //  发法名答的是「那一条路」，记录落进诊断
                lobos.kernel.fs.NotifyRoute.note(
                    this, "host.notify", NOTIF_ID, "前台", "（=> 而那内容**变了**才逐字重发）",
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIF_ID, buildHostNotification(text))
            }
        }
        //  「没变」那一档也不重算程序状态：快照只在 `buildNotification` 那格被读，
        //    而它只在上面「变了」那一支才被调到。
    }

    /**
     * 通知内容（按需算、缓存着）：内容是状态（答「此刻状态是什么」），缓存只在内容变了时换，判据是「内容变没变」。
     */
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
        //  缺陷名 C-102：旧版那句只记了第一步（「宿主被销毁」），后面几步没痕迹，进程死在拆到一半时外面看不出
        //    它死在哪一步（onDestroy 那一族有六步，`:271` 到 `:276` 四行没有一行会抛）。按 C-103 先答「为什么有两边」：
        //    两边是「仓内记不着的」（进程死在哪一步，要设备侧）与「仓内记着的」（六步各记一行）。
        //    这里在每一步之后记一行，只记「走到哪一步」，「为什么死」仍要设备侧那一格。
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

    /**
     * 通知那一格（内容由调用方给、不自己再算）：内容按需算，快照不被每拍翻一遍。
     */
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

        //  通知内容的缓存（只在内容变了时换）。
        @Volatile private var lastNotifiedText: String? = null
        private const val REQ_OPEN = 41
        private const val TICK_MS = ResidencyPolicy.FAST_TICK_MS
        private const val NOTIFY_MS = 60_000L
        private const val LEDGER_INTERVAL_MS = 10 * 60_000L

        /**
         * 宿主活着没有（幂等那一面）。缺陷 C-102：旧版 `ensureRunning` 无条件调 `startForegroundService`，每次都重新 post 一次
         *   前台通知（系统侧记 `NotificationManagerService:post:lobos.os`），而 `InstanceHost.bootProgramOnce` 每轮都调它
         *   （`bootLoop` 一秒一拍、间隔 6 到 60 秒的根就是它）。后果：OOM 归零了而通知仍每 2 到 3 秒一条，「崩溃 → 重启 →
         *   重新 post」被证伪。处置：已活着就不再拉起、不重新 post（真没活着才拉起）。
         */
        @Volatile private var hostAlive = false

        fun ensureRunning(context: Context, action: String? = null) {
            //  宿主已经活着时直接回去
            //    （`action` 不空时不省，它是显式那一路）。
            if (hostAlive && action.isNullOrBlank()) return
            try {
                //  缺陷名 C-102：「FGS start 那一拍」曾被记在不是 FGS start 的路上——这一路也是 `startForegroundService`，记它是对的。
                //  缺陷名 C-102：旧版这一路用 `startService`，而它只应用在前台时才能起后台服务，应用起来那一拍不在前台。
                //    后果：宿主服务起不来，落位与身份都只在宿主服务起的那一趟里跑、都没跑（设备上「起内置服务」「assets 列表」
                //    「build-identity.json」都是旧的）。处置：这一路也用 `startForegroundService`（与 `BootReceiver.startQuietly`
                //    同一个）；按 C-104：只有这一条路，不是两条路。
                val svc = Intent(context, OsHostService::class.java)
                if (!action.isNullOrBlank()) svc.action = action
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    noteFgsStart(context, "ensure")
                    //  缺陷名 C-102：FGS 准入被系统拒时。成因：`startForegroundService` 要 mAllowStartForeground，
                    //    Dozing 那一档可以拒它。后果：服务起不来、onStartCommand 没跑、ANR 是那「did not then call
                    //    Service.startForeground()」。处置：被拒时退回 `startService`（同一个服务），而 onCreate
                    //    已经调过 startForeground。
                    try {
                        context.startForegroundService(svc)
                    } catch (e: IllegalStateException) {
                        //  FGS 准入被拒：退回 `startService`。
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

        /**
         * 缺陷名 C-102：旧版没记 5 秒窗口的起算时刻，一个量算不出来。那一族把「FGS start」那一拍记下来，就能算。
         *    按 C-103：它有两边——「宿主出生 -> startForeground」与「FGS start -> startForeground」起算时刻不同、
         *    落点是同一拍。按 C-112：这一行带完整 epoch 毫秒，不是「算不出来」那个形状；按 C-220 这一笔是实现。
         */
        fun noteFgsStart(context: Context, path: String) {
            val at = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            //  缺陷名 C-102：startForeground 那一拍要配「FGS start」那一行，两行都带配对的标记，一个量才算得准。
            //    同一次触发只记一行；按 C-104 一次记一行、不会有第二行抢掉配对；按 C-220 这一笔只加这一行。
            val marker = path + "#" + at
            RuntimeDiagnostics.append(
                context, "fgs-start", true, "FGS start 那一拍",
                "起算时刻 at=" + at + " elapsedRealtime=" + elapsed + " 路径=" + path +
                    " 配对=" + marker,
            )
            fgsStartMarker = FgsStart(at, marker)
        }

        /** FGS start 那一拍与它的配对标记（同一个触发一次配一个）。 */
        class FgsStart(val at: Long, val marker: String)

        //  缺陷名 C-102：startForeground 那一拍要配「FGS start」那一行。这一格装的是这一次触发记的那一行、
        //    只装一行，消费掉就清空，下一次触发另记一行。按 C-112：不是「算不出来」那个形状，是「配不准」。
        @Volatile
        private var fgsStartMarker: FgsStart? = null

        /** 取出配对标记并清空（只给一次 startForeground 用）。 */
        fun takeFgsStart(): FgsStart? {
            val m = fgsStartMarker
            fgsStartMarker = null
            return m
        }
    }
}

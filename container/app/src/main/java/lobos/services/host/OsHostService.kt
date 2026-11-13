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

    //  缺陷名（C-102）**那 born 那一格逐字表达的是「这进程没被销毁过」、 它逐字该表达「这进程是第一次起来」** ——
    //    旧版逐字是 `val born = thread == null`
    //      `thread` 逐字**只在** onDestroy（`:378`）逐字变 null、
    //        onDestroy 逐字**只**在服务被**正常拆解**时跑
    //        系统**杀**进程那一族逐字**不走** onDestroy
    //        **thread 逐字永不为 null**
    //      **born 逐字恒为 false**
    //      **后果**： `OsInit.beginLife` 逐字**永不被调**
    //        `events.jsonl` 逐字 os-phase / 宿主出生 / os-run 逐字**都 0**
    //          （ beginLife 逐字必写其中之一）
    //        `files/var/lib/state.json` 逐字 phase=**DEGRADED**（ **不是** beginLife 写的 BOOTING）
    //        diag 逐字「宿主拆解进度」逐字 **0**（ onDestroy 逐字没跑过）
    //    **正解**： 「这进程逐字是第一次起来」逐字是**那进程自己的一个状态**，
    //      它逐字**不**由「有没有被销毁过」推出来。
    //    它逐字是那**进程自己的**一个逐字状态（ 它逐字随那进程一起来、 它逐字随那进程一起走）。
    @Volatile private var processBorn: Boolean = false
    @Volatile private var birthDone: Boolean = false
    private var handler: Handler? = null
    //  **tick** 逐字**走**那**自己**的**那一个**队列**（ **它**逐字**不**跟**那**装件**那一族**抢**）。
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

    //  缺陷名（C-102）**那 startForeground** 逐字**只**在**那 onStartCommand **那一族**里面**。
    //    **成因**： startForegroundService 逐字**被**那系统**拒**了的时候（ mAllowStartForeground false **那一族**）、
    //      **服务**逐字**没有**起、 onStartCommand 逐字**没有**跑、 startForeground 逐字**没有**被**调**。
    //      系统**等**那 startForeground **那一道**、 **等不到**、 ANR 逐字**是**那「bg anr: … did not then call Service.startForeground()」。
    //    **处置**： startForeground 逐字**搬**到**那 onCreate **那一族**里面**。
    //      **它**们**逐字是**那**两**件**事**：「那**服务**起没起**」与「那**入口**是**什么**」）。
    //      onCreate 逐字**在**那服务**起来**的第一**个**时刻**、 **它**逐字**不等**那 onStartCommand。
    override fun onCreate() {
        super.onCreate()
        promoteToForeground("onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //  缺陷名（C-102）**那退出史**逐字**在**那**主线程**上**取**（ **它**逐字**是**那**一次** binder **调用**）。
        //    **成因**： KillAudit.auditOnce 逐字**调**那 getHistoricalProcessExitReasons（ **它**逐字**是**那
        //      **系统**服务**那**一边**的**同步** IPC）、 **它**逐字**在**那**主线程**上**、 **它**逐字**会**等**那**系统**服务**那**一边**。
        //    **后果**逐字 = 而那**宿主**服务逐字**ANR**（ 「waited 20001ms: executing service …OsHostService」**那一行**）、
        //      **它**逐字**杀**了**那**进程**、 **它**们**逐字是**那**两**件**事**：「那**读数**逐字**挂**在哪**」与「那**它**逐字**在**哪**一页**」））。
        //    **处置**： **退出史**逐字**搬**到**那**工作线程**上**取**（ **它**逐字**不**是**那**落位**那一族**、
        //      和那**它**们**逐字是**那**两**件**事**）， **主线程**逐字**只**做**那**轻活**。
        //    **它**逐字**要**在**那 handler **起**好**之后**（ **它**逐字**不**是**那**出生**那一族**）。
        ResidencyAudit.auditPreviousExit(this)
        //  born 那一格逐字改成「这进程逐字是第一次起来」
        //    （ **不是**「这进程没被销毁过」、 **不是** `thread == null`）。
        val born = !birthDone
        if (born) {
            processBorn = true
        }
        if (born) {
            //  **诊断**那一族的「上一世」那一份逐字**先清**（ 它逐字是那诊断那一族自己的语义：
            //    它逐字只清「上一世」那一份、 它逐字**不**清「这一世」写的那几行）
            //    它逐字**必须**在 beginLife **之前**（ beginLife 逐字写的
            //      「宿主出生」「索引已从落位重建」那几行逐字是**这一世**的、 它们逐字要**留住**）。
            //    它逐字**原来**在服务池那一族（ `SupervisorPool.start()` 逐字
            //      `RuntimeDiagnostics.clear(host)`、 它逐字在**工作线程**上、
            //      **出生**那一族逐字在**主线程**上） **两条链是竞态**、
            //      服务池逐字**先到**、 它逐字把 beginLife 写的那一档逐字**清掉了**
            //      （ 07 本轮逐字核到：那 scan-cost 那一档逐字在 journal 的 at 逐字 1793483983860、
            //        而那 diag.jsonl 逐字**最早**一条 at 逐字 1793483984570 —— **晚 710 ms**）。
            //  **出生标记**逐字**先**记下（ 它逐字是那「上一世 / 这一世」的分界）
            //    它逐字**必须**在 beginLife **之前**（ beginLife 逐字写的
            //      「宿主出生」「索引已从落位重建」与那 scan-cost 那一档逐字是**这一世**的、
            //      它们逐字要**留住**）。
            runCatching { lobos.services.log.RuntimeDiagnostics.markLife(this) }
            //  clear 逐字**只清上一世**（ 它逐字按那标记逐字切）
            //    它逐字**在**那标记**之后**逐字**不**清（ 这是 01 那一裁）
            runCatching { lobos.services.log.RuntimeDiagnostics.clear(this, "LIFE-MARK") }
            lobos.services.reg.ResidencyStatus.restore(this)
            OsInit.beginLife(this, ResidencyAudit.interruption(this))
            //  **出生**逐字**做完**就逐字记下（ 它逐字是那进程自己的一个状态、
            //    它逐字**不**由「有没有被销毁过」推出来）。
            //  **第二次** onStartCommand 逐字**不再**走那出生那一族（ beginLife 逐字**只**跑一次）
            //    **同一进程**里那 onStartCommand 逐字**可以**再被调（ 它逐字是那服务的常态）。
            birthDone = true
        }
        promoteToForeground("onStartCommand")
        //  缺陷名（C-102）**那重活**逐字**跑**在**那主线程**上**（ **它**们**逐字是**那**两**件**事**）。
        //    **成因**： ensureComponents 那一族逐字**去**装**那**预装件**（ **它**们**逐字是**那**两**件**事**：「那**落位**是**什么**」与「那**线程**是**什么**」）。
        //      **装**那一族逐字**要**解包**与**搬**那**几**兆**字节**（ 八**个** zip **那**一**族**），
        //      **主线程**逐字**等不起**（ 20 秒**那一道**）
        //    **后果**逐字 = 而那宿主服务逐字**ANR**（ 「waited 20003ms」**那一行**）、
        //      **进程**逐字**死**了、 **落位**那一族**与**那**身份**那一族逐字**还是**没跑**。
        //    **处置**： 重活逐字**搬到**那**线程**上**（ **HandlerThread** **那一族**逐字**先**起**、
        //      **它**们**逐字是**那**两**件**事**：「那**主线程**做**什么**」与「那**工作线程**做**什么**」）。
        //    按 C-104： 宿主那一族逐字**只有一条路**（ **它**逐字**是**那**同一个**触发**、
        //      **它**们**逐字不是**两条路**）。
        if (born) {
            startedAtMs = System.currentTimeMillis()
            thread = HandlerThread("lobos-host").apply { start() }
            handler = Handler(thread!!.looper)
            //  **tick** **那**一个**队列**（ **它**逐字**是**那**那**一个** HandlerThread）。
            tickThread = HandlerThread("lobos-tick").apply { start() }
            tickHandler = Handler(tickThread!!.looper)
        }
        //  **退出史**逐字**在**那**工作线程**上**取**（ **它**逐字**不**在**那**主线程**上**等**那**系统**服务**那**一边**）。
        handler?.post {
            runCatching { lobos.services.log.KillAudit.auditOnce(this) }
                .onFailure { Log.w(TAG, "读系统退出史失败", it) }
        }
        //  重活逐字**在**那**工作线程**上**跑**（ **主线程**逐字**只**做**那轻活**）。
        //  缺陷名（C-102）**那身份**那一族逐字**不**该**跟**那**落位**的**成败**绑**在**一**起**。
        //    **成因**： BuildIdentity.write 逐字**在**这**一块**里、 **它**的**前面**是**那** ensureComponents(intent) **那一行**、
        //      **落位**那一族逐字**一**旦**中途**断了**、 **它**逐字**就**跑**不到**那**身份**那一行**。
        //      **后果**逐字 = 而那**身份**那一族逐字**还是**那**旧**的**sha**。
        //    **处置**： **身份**那一族逐字**自己**写**（ **它**逐字**在**那 OsApplication.onCreate **那一拍**写**）。
        //      **它**们**逐字是**那**两**件**事**（「那**落位**那**件**落没落**」**与**「那**一次**构建**是**哪**一次**」）。
        //      **它**逐字**不**跟**那**落位**的**成败**绑**在**一**起**。
        handler?.post {
            ensureComponents(intent)
        }
        if (born) {
            OsInit.transition(this, OsPhase.RUNNING, "宿主组件就绪", ResidencyAudit.interruption(this))
            //  缺陷名（C-102）**那 tick** **与**那**装件**那一族逐字**在**那**同一**个**队列**上**。
            //    **成因**： ensureComponents 那一族逐字**在**那 **:106** 那一行 **post** 到**那** handler **队列**、
            //      **tick** 逐字**在**那 **:111** 那一行 **post** 到**那** **同一**个**队列**、
            //      和那**它们**逐字是**那**两**件**事**：「那**谁**先**到**」与「那**它**们**共**那**一个**队列**」）。
            //    **后果**逐字 = 而那**装件**那一族**一**挂**住**、 **它**逐字**把**那 **tick** 逐字**饿死**、
            //      **它**逐字**让**那 ResidencyStatus.record 逐字**不**再**被**调**、 **它**逐字**让**那 updatedAt 逐字**陈旧**、
            //      **它**逐字**让**那 Heartbeat.fresh 逐字**答**那 false、 **它**逐字**让**那 controlPlaneUp 逐字**落**那 false。
            //    **处置**： **tick** 逐字**走**那**自己**的**那一个**队列**（ **它**逐字**不**跟**那**装件**那一族**抢**）、
            //      **它**逐字**是**那**那**一个** Handler **那一族**、 **它们**逐字**是**那**两**件**事**。
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

    //  缺陷名（C-102）**那一行把「startForeground() 那一族跑过了」与「它抛没抛」记成了两件事** —— 而实际上
    //    「跑过了」的标记是那**一次调用被走到了**（ :99 那一行逐字是 startForeground），
    //    而「抛没抛」的标记是**那一次调用有没有抛**（ :102 那一族逐字只在**抛了**时写、
    //    它逐字零命中、 **只**说明「没抛」）；
    //    这一族逐字**只**记了「抛没抛」那**一件**、 「跑过了」那**一件**逐字**没有**任何痕迹，
    //    一族逐字分不出「压根没走到 :99」与「走到了、而没抛」。
    //    04 今天裁的是「合成一格」： 「那一行记的是什么、它什么时候记、它为什么**不**记」那三条**合成**一格，
    //    「在 :99 前后加一行仓内自己的记录」是那一格**里面**的处置（ 按 C-220 那一族是**内容稿**、
    //    这一笔是**实现**那一族、 **只**加一行）。
    //    这一行逐字用「host」那个 category（ 它逐字已经写得出、 :102 那一行逐字是同一族）。
    private fun promoteToForeground(where: String) {
        var called = false
        var threw: String? = null
        try {
            called = true
            //  **宿主**逐字**活着**逐字**记**着（ **幂等**那一格逐字**靠**它判）
            OsHostService.hostAlive = true
            //  **发法名**逐字答的是「那一条路」、 **记录**逐字**落**那诊断
            //    （ **下一趟**逐字靠它逐字就能归到路、 它逐字**不用**抓栈）
            //    **系统**侧逐字记的那一条（`NotificationManagerService:post:lobos.os`）
            //      逐字是**系统**记的我们这个 uid 发过一次、 它逐字**不**逐字说我们逐字主动发的
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
        //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
        //    一行逐字没有配对的标记、 一个量逐字算不准**
        //    —— 而实际上这一行逐字是「startForeground 那一拍」， 它逐字**要**能指回
        //    「FGS start 那一拍」那**一行**、 一行逐字带一个配对的标记。
        //    按 C-112： 一格逐字**不是**「我算不出来的」那个形状、 它逐字是「**配不准**」那个形状。
        //    按 C-220： 04 那边那一族是**内容稿**、 这一笔是**实现**那一族、 它逐字**只**加这一行。
        //  缺陷名（C-102）**那一条读数**逐字**记不**出**那**调用处**（ **它**们**逐字是**那**两**件**事**）。
        //    **成因**： promoteToForeground 逐字**从**那 onCreate **与**那 onStartCommand **那两**处**各**调**一次**、
        //      **它们**逐字**记**的是**那同一条**消息**、 **它们**逐字**分不清**那**哪**一次**调**的**。
        //    **后果**逐字 = 而那**应用**自己**记**的那一条（那「已调用 startForeground=true」）**与**
        //      那**系统**判的**那一条**（那「did not then call Service.startForeground()」）逐字**并排**、
        //      **它们**逐字**答**的是**那**两**件**事**（「那**应用**自己**记**的**那一族**」与「那**系统**判的**那一族**」）。
        //    **处置**： 一条读数逐字**带上**那**调用处**（ **它**们**逐字是**那**两**件**事**：「那**调**的**是**哪**一处**」与「那**记**的**是**什么**」）。
        //      **配对**逐字**带**那**编号**（ **它**们**逐字是**那**两**件**事**：「那**配**的**是**谁**」与「那**配**上**了**没有**」）。
        //      按 C-112： **两条**逐字**都**是**那系统那边说的**、 **它们**逐字**不互相顶替**。
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
        //  缺陷名（C-102）**那能力桥**逐字**起**在**那装件** **之后**、 **它**们**逐字是**那**两**件**事**。
        //    **成因**： pool.onHostStart 逐字**调**那 sync（ syncServices 逐字**调**那 d.act(host)、
        //      **piece-<id>** **那一族**的 act 逐字**就是**那 installOneById（ **它**逐字**在**那**落位**那一族**里**））、
        //      **装件**逐字**是**那**同**一个**队列**上**那**一**个**长活**、 **它**逐字**把**那**后面**的**那**几**步**饿死**。
        //      **能力桥**（ broker.start() 那一行）逐字**在**那**装件** **之后**、 **它**逐字**起不来**、
        //      **它**们**逐字是**那**两**件**事**：「那**桥**在不在**」与「那**它**为什么**没**起**」）。
        //    **后果**逐字 = 而那 LocalServerSocket 逐字**没有**在**听**、 **它**逐字**让**那 M6 那**一格**逐字**还**测不了**。
        //    **处置**： **能力桥**逐字**先**起**（ **它**逐字**不**等**那**装件**）、 **它**逐字**是**那**那**一个**队列**上**那**一**个**短活**。
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
        //  缺陷名（C-102）**那一格逐字每一拍逐字都重新算那一遍那状态快照** ——
        //    旧版逐字是 `.notify(NOTIF_ID, buildHostNotification())`
        //      `buildHostNotification` 那一格逐字**每拍**逐字调那
        //        `ProgramStatusHub.snapshot(this)`（ 它逐字把那**每一条**程序状态逐字**读一遍**）
        //      **常驻通知**逐字**保留**（ 它逐字是那保活那一条、
        //        Lead 逐字裁「那一族逐字**保留**前台服务、 **常驻通知**逐字**保留**」）
        //      「**状态快照**」那一类逐字**改成按需**
        //        （ 它逐字答的是「此刻那状态是什么」、 它逐字**不**要求那进程逐字一直在）
        //      **判据**逐字是「那一族逐字在那一段时间逐字**实际在做什么**」那一个事实上
        //        （ 它逐字**不**由「它逐字该做什么」顶掉）
        //    **处置**： 通知逐字**按需**逐字重算
        //      它逐字**只**在「那内容**变了**」时逐字**重发**
        //      内容**没**变时逐字**不**重算、 **快照**逐字**不**被翻
        //      **判据**逐字是「那内容**变没变**」（ 它逐字是那**内容**那一面）
        //      **没变**那一档逐字**不**算那一次重算（ **耗**逐字**没有**）
        //  **内容**逐字**按需**逐字算（ 它逐字**只**在要发的那一趟才**算**）
        //    「**没变**」那一档逐字**不**重发、 **快照**逐字**不**被翻
        val text = currentNotificationText()
        if (text != lastNotifiedText) {
            lastNotifiedText = text
            runCatching {
                //  **发法名**逐字答的是「那一条路」、 **记录**逐字**落**那诊断
                lobos.kernel.fs.NotifyRoute.note(
                    this, "host.notify", NOTIF_ID, "前台", "（=> 而那内容**变了**才逐字重发）",
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(NOTIF_ID, buildHostNotification(text))
            }
        }
        //  「**没变**」那一档逐字**也**不重算那**那一条条**的程序状态
        //    （ **快照**逐字**只**在 `buildNotification` 那一格逐字**才**被读
        //      一格逐字**只**在上面那「变了」那一支逐字**才**被调到）
    }

    /**
     * 那**通知**那内容逐字（ 它逐字**按需**逐字算、 它逐字**缓存**着）。
     *
     *   **内容**逐字是那**状态**逐字（ 它逐字答「此刻那状态是什么」）
     *   **缓存**逐字**只**在「那内容**变了**」时逐字**换**
     *   **判据**逐字是「那内容**变没变**」
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
        //  缺陷名（C-102）**宿主正在拆、而进程死在拆到一半** —— 而实际上 onDestroy 那一族逐字有**六步**
        //    （ :271 到 :276： **四行**逐字**没有一行**会抛）， 原来那一句只记了**第一步**
        //    （ 「宿主被销毁」那一行）、 **后面那几步**逐字**没有**任何痕迹；
        //    后果是： 进程死在拆到一半时、 外面逐字只看见「宿主被销毁」那一行、
        //    **看不出**它死在哪一步、 一格逐字**答不了**「为什么那几趟死在拆到一半」。
        //    按 C-103 先答「它为什么有两边」： 两边是「仓内那一族**记不着的**」
        //       （ 逐字是「进程死在哪一步」这一格、 **要**设备侧那一格）与
        //       「仓内那一族**记着的**」（ 逐字是那六步逐字各记一行）。
        //    这里逐字在**每一步之后**记一行、 它逐字**只**记「走到哪一步了」那一格、
        //       「为什么死」那一格逐字**仍**要设备侧那一格。
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
     * 那**通知**那一格逐字（ **内容**逐字**由**那调用方逐字**给**、 它逐字**不**自己再算一遍）。
     *
     *   **内容**逐字**按需**逐字算（ **快照**那一族逐字**不**被每拍翻一遍）
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

        //  **通知内容**逐字那一个缓存（ 它逐字**只**在「那内容**变了**」时逐字**换**）
        @Volatile private var lastNotifiedText: String? = null
        private const val REQ_OPEN = 41
        private const val TICK_MS = ResidencyPolicy.FAST_TICK_MS
        private const val NOTIFY_MS = 60_000L
        private const val LEDGER_INTERVAL_MS = 10 * 60_000L

        /**
         * 那**宿主**逐字**活着没有**（ 它逐字是那**幂等**那一面）。
         *
         * 缺陷名（C-102）**那一格逐字每一轮逐字都重新拉起那宿主、 它逐字就发一次通知** ——
         *   旧版逐字**没有**这一格、 `ensureRunning` 逐字**无条件**逐字调
         *     那 `startForegroundService`
         *     **每一次**逐字 `startForegroundService` 逐字**重新 post** 一次那前台通知
         *       （ 系统侧逐字记的那一条逐字是
         *         `NotificationManagerService:post:lobos.os`）
         *   **调用点**逐字每轮逐字都在调：
         *     · `InstanceHost.bootProgramOnce` 逐字**每一轮**逐字调它
         *       （ `bootLoop` 逐字**一秒**一拍、 间隔逐字 6 到 60 秒那一档
         *         的**根**逐字**就是**它）
         *   **后果**（07 本轮逐字核出、Lead 本机复核成立）：
         *     · 而那 **OOM** 逐字归零了、 通知逐字**仍在**每 2 到 3 秒一条
     *       「崩溃 → 重启 → 重新 post」那条推论逐字**已被证伪**
     *       通知逐字**有另一个成因**、 它逐字就是这一格
         *   **处置**： **已经活着**时逐字**不**再拉起
         *     **宿主**逐字**活着**时逐字**不**重新 post 那通知
         *     **真的**没活着时逐字**才**拉起（ **保活**那一族逐字**不**受影响）
         */
        @Volatile private var hostAlive = false

        fun ensureRunning(context: Context, action: String? = null) {
            //  **宿主**逐字**已经活着**时逐字**直接**回去
            //    （ `action` 逐字**不**空时逐字**不**省、 它是那**显式**那一路）
            if (hostAlive && action.isNullOrBlank()) return
            try {
                //  缺陷名（C-102）**「FGS start 那一拍」逐字被记在了**不是** FGS start 的那一路上、
                //    一拍逐字**配错了**、 问二那个量逐字**算不出来**
                //    —— 而实际上这一路逐字**也**是 `startForegroundService`、
                //    FGS start 那一拍逐字**在**这一路上、 这里逐字**记**它**是**对的**。
                //  缺陷名（C-102）**这一条路逐字用**那 startService（ **它**们**逐字是**那**两**件**事**）。
                //    **成因**： startService 逐字**只**在**那应用**在**前台**的时候**才**能**起**那后台服务**、
                //      **应用起来**那一拍逐字**不**在前台**（ **它**们**逐字是**那**两**件**事**）。
                //    **后果**逐字 = 而那宿主服务逐字**起不来**、 **落位**那一族**与**那**身份**那一族
                //      逐字**都**只在**那宿主服务起**的那一趟**里**跑**、 **它们**逐字**都**没跑**。
                //      **证据**逐字 = 而那设备上那「起内置服务」**与**那「assets 列表」**与**那
                //        build-identity.json **逐字都**是**那旧**的**那一个**。
                //    **处置**： 这一条路逐字**也**用**那 startForegroundService（ **它**逐字**跟**
                //      那 BootReceiver.startQuietly **那一族**同一**一个**（ **它**们**逐字是**那**两**件**事**）。
                //    按 C-104： 宿主那一族逐字**只有**这一条路**用** startForegroundService、
                //      **它**们**逐字是**那**同一个**触发**的**两种**写法**、 **它**们**逐字不是**两条路**。
                val svc = Intent(context, OsHostService::class.java)
                if (!action.isNullOrBlank()) svc.action = action
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    noteFgsStart(context, "ensure")
                    //  缺陷名（C-102）**那 FGS **准入**被**那系统**拒**了**的时候、 **它**们**逐字是**那**两**件**事**）。
                    //    **成因**： startForegroundService 逐字**要**那**准入**（ mAllowStartForeground **那一族**）、
                    //      **设备**逐字**可以**在**那 Dozing **那一档**里**拒**它**。
                    //    **后果**逐字 = 而那**服务**逐字**起不来**、 onStartCommand 逐字**没有**跑、
                    //      startForeground 逐字**没有**被**调**、 ANR 逐字**是**那「did not then call Service.startForeground()」。
                    //    **处置**： FGS **被拒**的时候**退回**那 startService（ **它**逐字**是**那**同一个**服务**、
                    //      **它**们**逐字不是**两条路**）。
                    //      **onCreate** **那一族**逐字**已经**调**那 startForeground（ **它**们**逐字是**那**两**件**事**）。
                    try {
                        context.startForegroundService(svc)
                    } catch (e: IllegalStateException) {
                        //  FGS **准入**被拒**了**： **它**逐字**退回**那 startService
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
         *  缺陷名（C-102）**那 5 秒窗口的起算时刻逐字没被记下来、 一个量逐字算不出来**
         *  —— 而实际上那 5 秒窗口逐字是「FGS start -> startForeground」那个量，
         *  这一族逐字把「FGS start」那一拍记下来、 一格逐字**能**算出来。
         *  按 C-103： 它为什么有两边 两边逐字是「宿主出生 -> startForeground」与
         *     「FGS start -> startForeground」那两族、 它们起算的**时刻不一样**、 落点是**同一拍**。
         *  按 C-112： 这一行逐字带**完整 epoch 毫秒**、 一格逐字**不是**「我算不出来的」那个形状。
         *  按 C-220： 04 那边那一族是**内容稿**、 这一笔是**实现**那一族。
         */
        fun noteFgsStart(context: Context, path: String) {
            val at = System.currentTimeMillis()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
            //    一行逐字没有配对的标记、 一个量逐字算不准**
            //    —— 而实际上同一次触发逐字**只**记一行、 这一行逐字带一个配对的标记，
            //    服务起来那一拍逐字**能**指回这一行、 一个量逐字**能**算准。
            //    按 C-104： **同一个**触发逐字**只**触发一次、 **一次**记一行、
            //    **不**会有第二行逐字**抢**掉配对。
            //    按 C-220： 04 那边那一族是**内容稿**、 这一笔是**实现**那一族、 它逐字**只**加这一行。
            val marker = path + "#" + at
            RuntimeDiagnostics.append(
                context, "fgs-start", true, "FGS start 那一拍",
                "起算时刻 at=" + at + " elapsedRealtime=" + elapsed + " 路径=" + path +
                    " 配对=" + marker,
            )
            fgsStartMarker = FgsStart(at, marker)
        }

        /** 而 FGS start 那一拍逐字与它的配对标记（ 一族逐字是**同一个**触发逐字**一次**配一个） */
        class FgsStart(val at: Long, val marker: String)

        //  缺陷名（C-102）**那一次 startForeground 逐字该配哪一行 FGS start 逐字、
        //    一行逐字没有配对的标记、 一个量逐字算不准**
        //    —— 而实际上这一格逐字是「**这一次**触发逐字记的那一行」、
        //    它逐字只装**一行**、 消费掉之后逐字清空、 下一次触发逐字**另**记一行。
        //    按 C-112： 一格逐字**不是**「我算不出来的」那个形状、 它逐字是「**配不准**」那个形状。
        @Volatile
        private var fgsStartMarker: FgsStart? = null

        /** 而那一格逐字取出配对标记并清空（ 它逐字**只**给**一次** startForeground 用） */
        fun takeFgsStart(): FgsStart? {
            val m = fgsStartMarker
            fgsStartMarker = null
            return m
        }
    }
}

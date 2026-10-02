package lobos.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import lobos.bridge.AdbClientRunner
import lobos.bridge.MdnsWatcher
import lobos.capability.AttemptStore
import lobos.setup.PipelineRefresh
import lobos.permissions.PermissionCatalog
import lobos.permissions.PermissionCenter

class PairingProbeService : Service() {

    private var watcher: MdnsWatcher? = null

    @Volatile private var pairingHost: String? = null
    @Volatile private var pairingPort: Int = 0
    @Volatile private var connectPort: Int = 0
    @Volatile private var busy = false

    @Volatile private var pairingLive = false
    @Volatile var notificationBlocked = false; private set
    @Volatile private var roundStartMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIF)
                nm.cancel(RESULT_NOTIF)
                PipelineRefresh.notifyChanged()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SUBMIT -> handleCode(intent)
            else -> startProbe()
        }
        return START_STICKY
    }

    private fun startProbe() {
        if (!notificationsUsable()) {
            notificationBlocked = true
            ProbeJournal.append(
                this, "svc",
                "通知权限未授予 → 不起 browse：输码通知发不出去（Android 13+ 静默丢弃，notify 不抛异常）",
            )
            PipelineRefresh.notifyChanged()
            stopSelf()
            return
        }
        notificationBlocked = false
        if (running && pairingLive) { renderStatus(); return }
        running = true
        pairingHost = null; pairingPort = 0; pairingLive = false; connectPort = 0
        val w = watcher ?: MdnsWatcher(applicationContext).also { watcher = it }
        roundStartMs = System.currentTimeMillis()
        ProbeJournal.browsePairingStartedAt = roundStartMs
        ProbeJournal.browseConnectStartedAt = roundStartMs
        ProbeJournal.append(this, "svc", "探针启动：开始 browse ${MdnsWatcher.TYPE_PAIRING} + ${MdnsWatcher.TYPE_CONNECT}")
        val sink = object : MdnsWatcher.Sink {
            override fun onRecord(type: String, host: String?, port: Int, name: String, ageMs: Long) {
                val self = this@PairingProbeService
                val now = System.currentTimeMillis()
                if (type == MdnsWatcher.TYPE_PAIRING) {
                    pairingHost = host; pairingPort = port
                    pairingLive = true
                    if (ProbeJournal.pairingRecordFirstSeenAt == 0L) ProbeJournal.pairingRecordFirstSeenAt = now
                    ProbeJournal.append(this@PairingProbeService, "mdns", "pairing 记录 $name host=${host ?: "?"} port=$port browse后 ${ageMs}ms")
                } else {
                    if (connectPort != port) {
                        lobos.capability.AdbChannelComponent.reset(this@PairingProbeService, "配对端口变化，重测通道")
                        ProbeJournal.append(
                            self, "mdns",
                            "connect 端口变化 $connectPort→$port → 通道缓存作废",
                        )
                    }
                    connectPort = port
                    if (ProbeJournal.connectRecordFirstSeenAt == 0L) ProbeJournal.connectRecordFirstSeenAt = now
                    ProbeJournal.append(this@PairingProbeService, "mdns", "connect 记录 $name port=$port browse后 ${ageMs}ms")
                }
                renderStatus()
            }

            override fun onLost(type: String, name: String) {
                if (type == MdnsWatcher.TYPE_PAIRING) {
                    if (!pairingLive) return
                    pairingLive = false; pairingHost = null; pairingPort = 0
                    ProbeJournal.append(
                        this@PairingProbeService, "mdns",
                        "pairing 记录消失（${name.ifBlank { "无实例名" }}）→ 端口读数作废",
                    )
                } else if (connectPort > 0) {
                    connectPort = 0
                    lobos.capability.AdbChannelComponent.reset(this@PairingProbeService, "配对端口清空，重测通道")
                    ProbeJournal.append(
                        self, "mdns",
                        "connect 记录消失（${name.ifBlank { "无实例名" }}）→ 端点作废、通道缓存作废",
                    )
                } else return
                renderStatus()
            }

            override fun onLog(message: String) {
                ProbeJournal.append(this@PairingProbeService, "mdns", message)
            }
        }
        w.start(MdnsWatcher.TYPE_PAIRING, roundStartMs, sink)
        w.start(MdnsWatcher.TYPE_CONNECT, roundStartMs, sink)
        renderStatus()
        Handler(Looper.getMainLooper()).postDelayed({
            if (running && !pairingLive)
                ProbeJournal.append(this, "mdns", "45s 内未见 pairing 记录（对话框若已打开 = ②时序定罪样本）")
        }, 45_000L)
    }

    private fun handleCode(intent: Intent) {
        ProbeJournal.codeReceivedAt = System.currentTimeMillis()
        val code = extractCode(intent)
        ProbeJournal.append(this, "pair", "快捷回复送达：code=${code?.length ?: 0} 位")
        if (code.isNullOrBlank()) {
            conclude("配对码为空（本次未提交任何数字）")
            return
        }
        if (busy) {
            conclude("上一次配对仍在进行，本次未提交")
            return
        }
        val host = pairingHost
        val pport = pairingPort
        if (!pairingLive || host == null || pport <= 0) {
            ProbeJournal.append(this, "pair", "无在册 mDNS 配对端点 → 未发起配对（不伪造地址）")
            conclude("配对端口不在册：请在「无线调试」页点「与配对设备配对」并把对话框保持打开")
            return
        }
        busy = true
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(RESULT_NOTIF)
        renderStatus("配对进行中（$host:$pport）")
        Thread {
            val outcome = AdbClientRunner.pair(
                applicationContext, host, pport, code,
                connectPort.takeIf { it > 0 }, PAIR_TIMEOUT_MS,
            )
            busy = false
            val reason = if (outcome.ok) "" else (outcome.error ?: outcome.raw.take(200))
            val now = System.currentTimeMillis()
            AttemptStore.recordPair(now, outcome.ok, reason)
            ProbeJournal.append(
                this, "pair",
                if (outcome.ok) "配对成功（${host}:${pport}）：${outcome.json?.optString("guid")?.take(16)}"
                else "配对失败：$reason",
            )
            val head = latestConclusion() + if (outcome.ok) " —— 回首页继续下一步" else ""
            notifyConclusion(head, ok = outcome.ok)
            renderStatus(head, stickyError = !outcome.ok)
            lobos.capability.AdbChannelComponent.reset(this, "配对成功，重测通道")
            PipelineRefresh.notifyChanged()
        }.apply { isDaemon = true }.start()
    }

    private fun conclude(reason: String) {
        AttemptStore.recordPair(System.currentTimeMillis(), ok = false, reason)
        ProbeJournal.append(this, "pair", "输入送达但未发起配对：$reason")
        val head = latestConclusion()
        notifyConclusion(head, ok = false)
        renderStatus(head, stickyError = true)
        PipelineRefresh.notifyChanged()
    }

    private fun latestConclusion(): String =
        AttemptStore.humanPairTimeline().firstOrNull() ?: "本次输入未产生结论"

    private fun notificationsUsable(): Boolean {
        val spec = PermissionCatalog.byId(PermissionCatalog.POST_NOTIFICATIONS) ?: return true
        return PermissionCenter(applicationContext).isGranted(spec)
    }

    private fun renderStatus(override: String? = null, stickyError: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val live = override ?: when {
            pairingLive -> "配对端口 $pairingPort 在册 —— 下拉本通知「输入配对码」"
            connectPort > 0 ->
                "无线调试在线（连接端口 $connectPort），未见到配对对话框记录 —— 点「与配对设备配对」后即出现"
            else -> "等待 mDNS 记录：把「与配对设备配对」对话框开着，端口出现后这里就能输码"
        }
        val history = AttemptStore.humanPairTimeline()
        val head = if (override == null) history.firstOrNull() else override
        val body = buildString {
            if (head != null) appendLine(head)
            if (head != live) appendLine(live)
            history.drop(1).forEach { appendLine(it) }
        }.trimEnd()
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Lob OS · S0 配对探针")
            .setContentText(head ?: live)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setContentIntent(openAppIntent())
        if (stickyError) builder.color = 0xFFD64545.toInt()
        val replyInput = RemoteInput.Builder(EXTRA_CODE).setLabel("6 位配对码").build()
        val replyAction = NotificationCompat.Action.Builder(
            0, "输入配对码", submitIntent(),
        ).addRemoteInput(replyInput).setAllowGeneratedReplies(true).build()
        builder.addAction(replyAction)
        runCatching { nm.notify(NOTIF, builder.build()) }
            .onFailure { ProbeJournal.append(this, "svc", "通知发布失败：${it::class.java.simpleName}: ${it.message}") }
    }

    private fun notifyConclusion(head: String, ok: Boolean) {
        val body = (listOf(head) + AttemptStore.humanPairTimeline().drop(1)).joinToString("\n")
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (ok) "配对成功" else "配对未成功")
            .setContentText(head)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        builder.color = if (ok) 0xFF2E7D32.toInt() else 0xFFD64545.toInt()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(RESULT_NOTIF, builder.build())
        }.onFailure {
            ProbeJournal.append(this, "svc", "结论通知发布失败：${it::class.java.simpleName}: ${it.message}")
        }
    }

    private fun extractCode(intent: Intent): String? {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return null
        val raw = results.getCharSequence(EXTRA_CODE) ?: return null
        return raw.filter { it.isDigit() }.toString().takeIf { it.isNotEmpty() }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, REQ_OPEN,
        Intent(this, lobos.ui.setup.SetupActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or immutability(),
    )

    private fun submitIntent(): PendingIntent = PendingIntent.getService(
        this, REQ_SUBMIT,
        Intent(this, PairingProbeService::class.java).setAction(ACTION_SUBMIT),
        PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag(),
    )

    private fun mutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= 31) 1 shl 18 else 0

    private fun immutability(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "S0 配对探针", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "无线 ADB 配对输码快捷回复（探针期）" },
            )
        }
    }

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        watcher?.stopAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SUBMIT = "lobos.action.PAIR_SUBMIT"
        const val ACTION_STOP = "lobos.action.PAIR_STOP"
        const val EXTRA_CODE = "pair_code"
        @Volatile var running: Boolean = false
        @Volatile var instance: PairingProbeService? = null
        private const val CHANNEL = "lobos_pairing_probe"
        private const val NOTIF = 3637

        private const val RESULT_NOTIF = 3638
        private const val REQ_OPEN = 31
        private const val REQ_SUBMIT = 32
        private const val PAIR_TIMEOUT_MS = 30_000L
    }
}

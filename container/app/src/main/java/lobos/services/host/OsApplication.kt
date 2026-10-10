package lobos.services.host

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import lobos.services.supply.CatalogClient
import lobos.services.app.Foreground
import lobos.services.app.QuickAppHost
import lobos.services.log.RuntimeDiagnostics
import lobos.kernel.KernelHooks
import lobos.kernel.power.PowerHostHooks
import lobos.services.reg.ResidencyStatus

class OsApplication : Application() {

    companion object {
        const val SUPERVISOR_CHANNEL_ID = "lobos_host"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        lobos.services.app.Foreground.attach(this)
        initQuickAppRuntime()
        // 内核的两个注入钩子在这里注册 —— onCreate 是进程级入口，早于任何组件。
        //
        // 注册在 OsHostService.onStartCommand 里是不够的：宿主被杀后钩子随进程
        // 一起消失，而无障碍服务与 Doze 兜底闹钟都会被系统单独拉起 —— 那时
        // 钩子已经没了，「请拉起宿主」这个请求就落空。
        KernelHooks.setEnsurer { ctx -> OsHostService.ensureRunning(ctx) }
        KernelHooks.setReporter { ctx, stage, ok, message, detail ->
            RuntimeDiagnostics.append(ctx, stage, ok, message, detail ?: "")
        }
        PowerHostHooks.setEnsurer { ctx -> OsHostService.ensureRunning(ctx) }
        PowerHostHooks.setWakeObserver { nowMs ->
            ResidencyStatus.recordWake()
            RuntimeDiagnostics.append(
                this, "doze", null, "兜底投递：确保 OS 宿主在",
                "自唤醒间隔=" + lobos.kernel.power.DozeBackstop.WAKE_BACKSTOP_MS + "ms（now=" + nowMs + "）"
            )
        }
                OsHostService.ensureRunning(this)
        registerWakeupEdges()
        supplyOnStartup()
    }

    private fun initQuickAppRuntime() {
        runCatching {
            com.didi.dimina.Dimina.init(
                this,
                com.didi.dimina.Dimina.DiminaConfig.Builder()
                    .setDebugMode(true)
                    .setShowCapsule(false)
                    .setShowLaunchLoading(false)
                    .setEnableMultiTask(true)
                    .build()
            )
        }.onFailure {
            RuntimeDiagnostics.append(
                this, "quickapp", false,
                "快应用运行时（dimina）初始化失败",
                it.javaClass.name + ": " + (it.message ?: "")
            )
        }.onSuccess {
            runCatching { lobos.services.app.QuickAppHost.registerCapabilities(this) }
                .onFailure {
                    RuntimeDiagnostics.append(this, "quickapp", false, "能力模块注册失败", it.javaClass.name + ": " + (it.message ?: ""))
                }
            RuntimeDiagnostics.append(this, "quickapp", true, "快应用运行时已就绪", "dimina 初始化完成；能力桥 extBridge(module=lobos)")
        }
    }

    private fun supplyOnStartup() {
        Thread {
            runCatching { CatalogClient.refresh(this@OsApplication, false) }
        }.apply { isDaemon = true }.start()
    }

    private fun registerWakeupEdges() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                OsHostService.ensureRunning(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { registerReceiver(receiver, filter) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                SUPERVISOR_CHANNEL_ID, "Lob OS 常驻", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "OS 宿主状态（唯一前台服务，锁屏常驻）" }
        )
    }
}

package lobos.host

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import lobos.host.Foreground
import lobos.services.log.RuntimeDiagnostics
import lobos.kernel.KernelHooks
import lobos.services.power.PowerHostHooks
import lobos.services.reg.ResidencyStatus

class OsApplication : Application() {

    companion object {
        const val SUPERVISOR_CHANNEL_ID = "lobos_host"
    }

    override fun onCreate() {
        super.onCreate()

        runCatching { lobos.services.reg.BuildIdentity.write(this) }
        // V2: unpack the bundled Ubuntu system disk on first boot (background).
        // Everything else (services/terminal) checks UbuntuSystem.isReady().
        Thread {
            runCatching { lobos.system.RootfsInstaller.install(this) }
                .onFailure { e ->
                    runCatching {
                        java.io.File(filesDir, "rootfs-error.txt").writeText(
                            e.javaClass.name + ": " + (e.message ?: "") + "\n" +
                            e.stackTraceToString().take(3000))
                    }
                    RuntimeDiagnostics.append(this, "rootfs", false, "系统盘安装失败", e.message ?: "")
                }
        }.apply { isDaemon = true; name = "lobos-rootfs-install"; start() }
        createChannels()
        lobos.host.Foreground.attach(this)
        initQuickAppRuntime()
        KernelHostAdapters.register(this)
        KernelHooks.setEnsurer { OsHostService.ensureRunning(this) }
        PowerHostHooks.setEnsurer { ctx -> OsHostService.ensureRunning(ctx) }
        PowerHostHooks.setWakeObserver { nowMs ->
            ResidencyStatus.recordWake()
            RuntimeDiagnostics.append(
                this, "doze", null, "兜底投递：确保 OS 宿主在",
                "自唤醒间隔=" + lobos.host.DozeBackstop.WAKE_BACKSTOP_MS + "ms（now=" + nowMs + "）"
            )
        }

                OsHostService.ensureRunning(this)
        registerWakeupEdges()
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
            RuntimeDiagnostics.append(this, "quickapp", true, "快应用运行时已就绪", "dimina 初始化完成；能力桥 extBridge(module=lobos)")
        }
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

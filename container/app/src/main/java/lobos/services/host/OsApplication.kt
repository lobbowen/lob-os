package lobos.services.host

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import lobos.services.app.Foreground
import lobos.services.app.QuickAppHost
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
        //  缺陷名（C-102）**那身份**那一族逐字**跟**那**落位**的**成败**绑**在**一**起**。
        //    => 而那**成因**：=> 而那 BuildIdentity.write 逐字**在**那 handler?.post **那一块**里、
        //      => 而而那**它**的**前面**是**那** ensureComponents(intent) **那一行**、
        //      => 而而那**落位**那一族逐字**一**旦**中途**断了**、=> 而而那**它**逐字**就**跑**不到**那**身份**那一行**。
        //      => 而而那**后果**逐字 = 而那**身份**那一族逐字**还是**那**旧**的**sha**（=> 而那**它**逐字**答**的是「那**一次**构建**是**哪**一次**」）。
        //    => 而那**处置**：=> 而那**身份**那一族逐字**自己**写**（=> 而而那**它**逐字**在**这**应用**自己**的**出生**那**一拍**写**）。
        //      => 而而那**它**们**逐字是**那**两**件**事**（「那**落位**那**件**落没落**」**与**「那**一次**构建**是**哪**一次**」）。
        //      => 而而那**它**逐字**不**跟**那**落位**的**成败**绑**在**一**起**、=> 而而那**它**逐字**在**那**服务**起不来**的时候**也**照写**。
        runCatching { lobos.services.reg.BuildIdentity.write(this) }
        createChannels()
        lobos.services.app.Foreground.attach(this)
        initQuickAppRuntime()
        KernelHooks.setEnsurer { ctx -> OsHostService.ensureRunning(ctx) }
        KernelHooks.setReporter { ctx, stage, ok, message, detail ->
            RuntimeDiagnostics.append(ctx, stage, ok, message, detail ?: "")
        }
        PowerHostHooks.setEnsurer { ctx -> OsHostService.ensureRunning(ctx) }
        PowerHostHooks.setWakeObserver { nowMs ->
            ResidencyStatus.recordWake()
            RuntimeDiagnostics.append(
                this, "doze", null, "兜底投递：确保 OS 宿主在",
                "自唤醒间隔=" + lobos.services.power.DozeBackstop.WAKE_BACKSTOP_MS + "ms（now=" + nowMs + "）"
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
            runCatching { lobos.services.app.QuickAppHost.registerCapabilities(this) }
                .onFailure {
                    RuntimeDiagnostics.append(this, "quickapp", false, "能力模块注册失败", it.javaClass.name + ": " + (it.message ?: ""))
                }
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


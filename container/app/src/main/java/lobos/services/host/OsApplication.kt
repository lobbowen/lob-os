package lobos.services.host

import android.app.Application
import lobos.services.supply.ShellEntry
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
        createChannels()
        probeProcessBuilder(this)
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
    private fun probeProcessBuilder(ctx: Context) {
        val b = ShellEntry.resolve(ctx)
        val rep = StringBuilder()
        rep.append("bin=").append(b?.absolutePath ?: "解析不到").append('\n')
        if (b != null && b.isFile) {
            runCatching {
                val p = ProcessBuilder(b.absolutePath, "-c", "echo FROM-PB-OK").redirectErrorStream(true).start()
                val txt = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                rep.append("pb输出=").append(txt.replace('\n', '|')).append(" rc=").append(p.exitValue())
            }.onFailure { e ->
                rep.append("pb失败=").append(e.javaClass.name).append(": ").append(e.message)
            }
        }
        RuntimeDiagnostics.append(
            this, "pb-probe", rep.toString().contains("FROM-PB-OK"),
            "宿主用 ProcessBuilder 起解释器", rep.toString(),
        )
    }


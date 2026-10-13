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
import lobos.kernel.power.PowerHostHooks
import lobos.services.reg.ResidencyStatus

class OsApplication : Application() {

    companion object {
        const val SUPERVISOR_CHANNEL_ID = "lobos_host"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        installSystemView()
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
                "自唤醒间隔=" + lobos.kernel.power.DozeBackstop.WAKE_BACKSTOP_MS + "ms（now=" + nowMs + "）"
            )
        }

        Thread {
            runCatching { lobos.services.app.PreinstalledPieces.installAll(this@OsApplication) }
                .onFailure {
                    RuntimeDiagnostics.append(
                        this@OsApplication, "prefix", false,
                        "预装件装置过程中出错",
                        it::class.java.simpleName + ": " + it.message,
                    )
                }
        }.apply { isDaemon = true }.start()
                OsHostService.ensureRunning(this)
        registerWakeupEdges()
    }

    private fun installSystemView() {
        val tag = "LobosSysView"
        android.util.Log.i(tag, "进入")
        val bind = runCatching { lobos.kernel.compat.LobosEnv.get("PATH") }
        android.util.Log.i(tag, "loadLibrary 后 PATH=" + bind)
        android.util.Log.i(tag, "开始写 environ")
        var wrote = 0
        var total = 0
        runCatching {
            val root = lobos.services.reg.RuntimeEnvironment.treeRootFor(this)
            val view = lobos.services.reg.RuntimeEnvironment.treeRootEnv(this, root, null)
            total = view.size
            for ((k, v) in view) {
                if (lobos.kernel.compat.LobosEnv.set(k, v)) wrote++
            }
        }.onFailure {
            android.util.Log.e(tag, "写 environ 抛了", it)
        }
        android.util.Log.i(tag, "写完 " + wrote + "/" + total)
        val probe = runCatching {
            val dir = java.io.File(filesDir, "var/run")
            dir.mkdirs()
            val f = java.io.File(dir, "sysview-probe.sh")
            val body = StringBuilder()
            body.append("echo LD=\$LD_LIBRARY_PATH").append('\n')
            body.append("echo P=\$PATH").append('\n')
            body.append("/data/user/0/lobos.os/files/usr/bin/rg --version 2>&1 | head -1").append('\n')
            f.writeText(body.toString())
            val out = lobos.kernel.compat.LobosEnv.spawnProbe("/system/bin/sh " + f.absolutePath)
            f.delete()
            out
        }.getOrElse { "threw " + it.javaClass.simpleName }
        android.util.Log.i(tag, "探针 " + probe.replace('\n', '|'))
        RuntimeDiagnostics.append(
            this, "sysview", wrote > 0,
            "系统视图写入宿主进程环境 " + wrote + "/" + total,
            probe.replace('\n', ' ').take(400),
        )
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

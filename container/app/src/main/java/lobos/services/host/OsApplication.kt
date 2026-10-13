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
        terminalSelfCheck()
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
        RuntimeDiagnostics.append(
            this, "sysview", wrote > 0,
            "系统视图写入宿主进程环境 " + wrote + "/" + total,
            "写入方式：JNI 改本进程 environ；子进程由 execve 层的 env_merge_sys 继承",
        )
    }

    private fun terminalSelfCheck() {
        val sh = java.io.File("/system/bin/sh")
        val r = runCatching {
            lobos.kernel.ipc.PtySession.runToCompletion(
                this, sh,
                listOf("-c", "echo __LD__=\$LD_LIBRARY_PATH; echo __PATH__=\$PATH; echo __BINS__=\$(ls /data/user/0/lobos.os/files/usr/bin | tr '\\n' ' ')"),
                timeoutMs = 8_000,
            )
        }.getOrNull()
        val out = r?.output.orEmpty()
        val ld = out.lineSequence().firstOrNull { it.startsWith("__LD__=") }?.removePrefix("__LD__=").orEmpty()
        val path = out.lineSequence().firstOrNull { it.startsWith("__PATH__=") }?.removePrefix("__PATH__=").orEmpty()
        val bins = out.lineSequence().firstOrNull { it.startsWith("__BINS__=") }?.removePrefix("__BINS__=").orEmpty()
        val libOk = ld.contains("usr/lib")
        val binOk = path.contains("usr/bin")
        RuntimeDiagnostics.append(
            this, "terminal", libOk && binOk,
            "终端自检（经 PTY 服务跑一条命令）",
            "LD_LIBRARY_PATH 含 usr/lib：$libOk" +
                "\nPATH 含 usr/bin：$binOk" +
                "\nusr/bin 里有什么：$bins" +
                "\nLD=$ld" +
                "\n错误：${r?.error ?: "无"}",
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

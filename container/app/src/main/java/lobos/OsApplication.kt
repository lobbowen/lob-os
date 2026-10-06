package lobos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build


import lobos.lifecycle.OsHostService



class OsApplication : Application() {

    companion object {
        const val SUPERVISOR_CHANNEL_ID = "lobos_host"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        lobos.quickapp.Foreground.attach(this)
        initQuickAppRuntime()
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
            runCatching { lobos.quickapp.QuickAppHost.registerCapabilities(this) }
                .onFailure {
                    RuntimeDiagnostics.append(this, "quickapp", false, "能力模块注册失败", it.javaClass.name + ": " + (it.message ?: ""))
                }
            RuntimeDiagnostics.append(this, "quickapp", true, "快应用运行时已就绪", "dimina 初始化完成；能力桥 extBridge(module=lobos)")
        }
    }

    private fun supplyOnStartup() {
        Thread {
            // 只刷商店目录。工具链/运行时装不装由「装程序时按该程序的 requires 决定」，
            // 走 os/PackageInstaller 那条唯一安装路径。
            //
            // 原先这里还调 SupplyProvisioner.ensure，等于每次 App 启动就无条件从商店拉
            // node/curl/git/jq/npm/pnpm/sqlite3，既绕过了注册表与控制面板，也让这些件的
            // 落位规则和安装器不一致（node 装在 toolchain/node/bin/，而 libc++_shared.so
            // 装在 usr/bin/，两者不相关 —— 裸环境启动必然 linker 失败）。
            runCatching { lobos.os.CatalogClient.refresh(this@OsApplication, false) }
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

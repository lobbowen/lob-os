package lobos

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import lobos.lifecycle.AccessibilityAnchor
import lobos.lifecycle.AnchorState
import lobos.lifecycle.OsHostService
import lobos.os.KillAudit
import lobos.lifecycle.AnchorPolicy

class OsApplication : Application() {

    companion object {
        const val SUPERVISOR_CHANNEL_ID = "lobos_host"
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        OsHostService.ensureRunning(this)
        ensureProtectionActive()
        registerWakeupEdges()
    }

    private fun ensureProtectionActive() {
        val appCtx = applicationContext
        Thread({
            try {
                runCatching { KillAudit.auditOnce(appCtx) }
                val startedMs = SystemClock.elapsedRealtime()
                val outcome = runCatching {
                    AccessibilityAnchor.ensureBound(appCtx, AnchorPolicy.ACTIVATION_BUDGET_MS)
                }.getOrNull()
                val elapsedMs = SystemClock.elapsedRealtime() - startedMs
                runCatching {
                    when ((outcome?.state ?: AnchorState.UNKNOWN)) {
                        AnchorState.BOUND -> RuntimeDiagnostics.append(
                            appCtx, "anchor", true, "保护生效：锚在位",
                            "state=${outcome?.state} bound=${outcome?.bound} 耗时=${elapsedMs}ms —— " +
                                "ColorOS 判决停在 importance=accessibility"
                        )
                        AnchorState.UNBOUND -> RuntimeDiagnostics.append(
                            appCtx, "anchor", false, "判决降级告警：锚未生效",
                            "state=${outcome?.state} bound=${outcome?.bound} 耗时=${elapsedMs}ms" +
                                "（保护激活上界 ${AnchorPolicy.ACTIVATION_BUDGET_MS}ms）—— " +
                                "锚不在位则 ColorOS 判决停在 importance=traffic，随后会被 o-kill；" +
                                "本设计不提供死后恢复"
                        )
                        AnchorState.UNKNOWN -> RuntimeDiagnostics.append(
                            appCtx, "anchor", false,
                            if (outcome == null) "判决降级告警：锚状态未知" else "判决降级告警：锚读数取不到",
                            (if (outcome == null) "ensureBound 调用失败" else "state=UNKNOWN：组件名解析不出或系统服务查不动") +
                                " 耗时=${elapsedMs}ms（保护激活上界 ${AnchorPolicy.ACTIVATION_BUDGET_MS}ms）—— " +
                                "取不到读数不等于保护生效，也不许记成「adb 办不成」，判据见激活预算"
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }, "protection-active").start()
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

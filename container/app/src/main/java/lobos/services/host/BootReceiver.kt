package lobos.services.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import lobos.services.log.RuntimeDiagnostics


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i(TAG, "BootReceiver: $action -> 拉起 OsHostService（唯一入口）")
            startQuietly(context, Intent(context, OsHostService::class.java))
        }
    }

    private fun startQuietly(context: Context, svc: Intent) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                //  缺陷名（C-102）**那 5 秒窗口的起算时刻逐字没被记下来、=> 而而那一个量逐字算不出来**
                //    —— 而实际上那 5 秒窗口逐字是「FGS start -> startForeground」那个量，
                //    => 而而「FGS start」那一拍逐字就是这一行**之前**的那一拍、
                //    => 而而仓内逐字**没有**那一行、=> 而而那一格逐字**算不出来**。
                //    => 而按 C-104：=> 而「FGS start」那一拍在**两条路上都有**（=> 而 boot 那一路与「服务起」那一路逐字都起服务）、
                //    => 而而这里逐字是**唯一**能记下那一拍的地方、=> 而而这一拍逐字**先于**服务起来。
                //    => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族、=> 而而它逐字**只**加这一行。
                OsHostService.noteFgsStart(context, "boot")
                context.startForegroundService(svc)
            } else {
                OsHostService.noteFgsStart(context, "boot")
                context.startService(svc)
            }
        } catch (e: IllegalStateException) {
            runCatching { context.startService(svc) }
                .onFailure { t ->
                    Log.e(TAG, "拉起 ${svc.component?.shortClassName} 失败", t)
                    RuntimeDiagnostics.append(
                        context, "boot", false, "拉起 ${svc.component?.shortClassName} 失败",
                        "startForegroundService 被拒（后台启动限制）：" +
                            t::class.java.simpleName + ": " + (t.message ?: ""),
                    )
                }
        } catch (e: Throwable) {
            Log.e(TAG, "拉起 ${svc.component?.shortClassName} 失败", e)
            RuntimeDiagnostics.append(
                context, "boot", false, "拉起 ${svc.component?.shortClassName} 失败",
                "${e::class.java.simpleName}: ${e.message}"
            )
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}

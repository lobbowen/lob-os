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
                //    => 而按 C-104：=> 而宿主那一族逐字**只有**这一处用 startForegroundService、
                //    => 而而这一拍逐字**只**在这一条路上、=> 而而它逐字**先于**服务起来。
                //    => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族、=> 而而它逐字**只**加这一行。
                OsHostService.noteFgsStart(context, "boot")
                context.startForegroundService(svc)
            } else {
                //  缺陷名（C-102）**「FGS start 那一拍」逐字被记在了**不是** FGS start 的那一路上、
                //    => 而而那一拍逐字**配错了**、=> 而而问二那个量逐字**算不出来**
                //    —— 而实际上这一路逐字是 `startService`（=> 而**不是** `startForegroundService`）、
                //    => 而而 FGS start 那一拍逐字**不**在这一路上、=> 而而这里逐字**不该**记那一拍。
                //    => 而按 C-112：=> 而那一格逐字**不是**「我算不出来的」那个形状，=> 而而它逐字是「**记错了**」那个形状。
                //    => 而按 C-220：=> 而 04 那边那一族是**内容稿**、=> 而而这一笔是**实现**那一族、=> 而而它逐字**只**改这一行。
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

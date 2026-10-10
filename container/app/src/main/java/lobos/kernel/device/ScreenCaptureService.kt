package lobos.kernel.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        val code = intent?.getIntExtra(ScreenCaptureController.EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(ScreenCaptureController.EXTRA_RESULT_DATA)
        if (code != 0 && data != null) {
            ScreenCaptureController.startProjectionFromService(this, code, data)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun promote() {
        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "截屏会话转前台失败: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Lob OS 截屏会话")
            .setContentText("仅在截屏期间存在，结束即停")
            .setOngoing(true)
            .build()
    }

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIF_ID = 1005

        /** 前台服务通知渠道 id（自持，避免依赖宿主常量）。 */
        const val CHANNEL_ID = "lobos_screen_capture"
        const val CHANNEL_NAME = "Lob OS 截屏"

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, ScreenCaptureService::class.java)
                .putExtra(ScreenCaptureController.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureController.EXTRA_RESULT_DATA, data)
            runCatching { ctx.startForegroundService(i) }.onFailure {
                Log.w(TAG, "截屏会话无法启动: ${it::class.java.simpleName}")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ScreenCaptureService::class.java)) }
        }
    }
}

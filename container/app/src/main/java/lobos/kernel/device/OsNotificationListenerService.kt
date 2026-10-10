package lobos.kernel.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class OsNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationStore.connected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationStore.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) NotificationStore.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationStore.onRemoved(sbn?.key)
    }
}

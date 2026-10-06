package lobos.quickapp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

object Foreground {

    @Volatile private var current: WeakReference<Activity>? = null

    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(Callbacks())
    }

    fun current(): Activity? = current?.get()

    fun require(label: String): Activity =
        current() ?: throw IllegalStateException(
            "当前没有前台 Activity：$label。宿主服务型调用点需要先有界面在前台。"
        )

    private class Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) {
            current = WeakReference(a)
        }

        override fun onActivityPaused(a: Activity) {
            if (current?.get() === a) current = null
        }

        override fun onActivityCreated(a: Activity, s: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {
            if (current?.get() === a) current = null
        }
    }
}

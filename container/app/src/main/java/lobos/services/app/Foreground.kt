package lobos.services.app

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
            unitOf(a)?.let { runCatching { ProgramGroup.onUiClosed(a, it) } }
        }

        private fun unitOf(a: Activity): String? {
            val id = runCatching {
                a.intent?.getStringExtra(QuickAppLaunchActivity.EXTRA_ID)
            }.getOrNull()
            return if (!id.isNullOrBlank() && ProgramGroup.hasUi(a, id)) id else null
        }
    }
}
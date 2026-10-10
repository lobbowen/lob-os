package lobos.services.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 前台 Activity 跟踪 —— 兼作**前端生命周期**的观测点。
 *
 * 我们与 Linux 不同：后端是普通进程，前端是快应用（dimina 承载）。
 * dimina 没有「小程序退出」的回调，所以唯一能观测到「前端那一侧结束」
 * 的地方是**承载它的宿主 Activity 销毁**。
 *
 * 那个时机要做的事：[ProgramGroup.onUiClosed] —— 按 manifest 里
 * `ui.onUiClosed` 声明的那一档，决定后端要不要跟着停。
 * systemd 靠 cgroup.kill 一次收干净；我们没有 cgroup（通用 APK 无
 * setpgid / setcgroup），这一层是分体形态下自己的等价物。
 */
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
            // 承载前端的宿主 Activity 销毁 → 前端那一侧结束
            unitOf(a)?.let { runCatching { ProgramGroup.onUiClosed(a, it) } }
        }

        /** 这个 Activity 承载的是哪个程序的前端 —— 从 intent 取，不猜 */
        private fun unitOf(a: Activity): String? {
            val id = runCatching {
                a.intent?.getStringExtra(QuickAppLaunchActivity.EXTRA_ID)
            }.getOrNull()
            return if (!id.isNullOrBlank() && ProgramGroup.hasUi(a, id)) id else null
        }
    }
}
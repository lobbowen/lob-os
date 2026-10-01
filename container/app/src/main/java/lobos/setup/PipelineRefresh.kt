package lobos.setup

import android.os.Handler
import android.os.Looper

object PipelineRefresh {

    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun subscribe(onChange: () -> Unit) {
        listeners += onChange
    }

    @Synchronized
    fun unsubscribe(onChange: () -> Unit) {
        listeners -= onChange
    }

    fun notifyChanged() {
        val snapshot: List<() -> Unit>
        synchronized(this) { snapshot = listeners.toList() }
        main.post { snapshot.forEach { runCatching { it() } } }
    }
}

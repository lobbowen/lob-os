package lobos.android.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.ui.home.HomeEvent
import lobos.ui.home.HomeMetric
import lobos.ui.home.HomeUiState

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Error("加载中"))
    val state: StateFlow<HomeUiState> = _state

    fun load(ctx: Context) {
        viewModelScope.launch { _state.value = withContext(Dispatchers.IO) { gather(ctx) } }
    }

    fun onEvent(ctx: Context, event: HomeEvent) {}

    companion object {
        fun gather(ctx: Context): HomeUiState {
            val running = lobos.android.services.power.KernelPower.state(ctx) == lobos.android.services.power.KernelPower.State.RUNNING
            val uptimeMs = runCatching {
                lobos.android.services.reg.ResidencyStatus.snapshot().optLong("uptimeMs", 0L)
            }.getOrDefault(0L)
            val uptimeMinutes = uptimeMs / 60000
            val rt = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val threadCount = Thread.activeCount()
            val sessions = runCatching { lobos.system.exec.PtySession.activeSessions().size }.getOrDefault(0)
            val appCount = 0 // app market not wired yet; installed apps count goes here
            return HomeUiState.Ready(
                running = running, uptimeMinutes = uptimeMinutes, online = running,
                metrics = listOf(
                    HomeMetric("$heapUsedMb MB", "Memory"),
                    HomeMetric(threadCount.toString(), "Threads"),
                    HomeMetric(sessions.toString(), "Sessions"),
                    HomeMetric(appCount.toString(), "Apps"),
                ),
                apps = emptyList(),
            )
        }
    }
}

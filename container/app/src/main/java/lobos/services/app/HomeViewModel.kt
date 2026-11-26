package lobos.services.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.services.reg.ProgramIndex
import lobos.services.reg.ResidencyStatus
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
            val running = lobos.services.power.KernelPower.state(ctx) == lobos.services.power.KernelPower.State.RUNNING
            val programs = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            val uptimeMs = runCatching {
                ResidencyStatus.snapshot().optLong("uptimeMs", 0L)
            }.getOrDefault(0L)
            val uptime = if (uptimeMs > 0) {
                val mins = uptimeMs / 60000
                "${mins / 60}h ${mins % 60}m"
            } else "—"
            val rt = Runtime.getRuntime()
            val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val threadCount = Thread.activeCount()
            val sessions = runCatching {
                lobos.kernel.ipc.PtySession.activeSessions().size
            }.getOrDefault(0)
            val metrics = listOf(
                HomeMetric("$heapUsedMb MB", "内存占用"),
                HomeMetric(threadCount.toString(), "线程"),
                HomeMetric(sessions.toString(), "活跃会话"),
                HomeMetric(programs.size.toString(), "已装组件"),
            )
            return HomeUiState.Ready(
                running = running, uptime = uptime, online = running,
                metrics = metrics, apps = emptyList(),
            )
        }
    }
}

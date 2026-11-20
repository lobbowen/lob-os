package lobos.services.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.services.host.OsInit
import lobos.services.reg.ProgramIndex
import lobos.services.supply.ProgramDir
import lobos.services.supply.SystemState
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
            val running = runCatching { OsInit.snapshot(ctx).phase.name == "RUNNING" }.getOrDefault(false)
            val programs = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            val versions = runCatching {
                ProgramDir(ctx, "python").installedVersions().size
            }.getOrDefault(0)
            val report = runCatching { SystemState.evaluate(ctx) }.getOrNull()
            val metrics = listOf(
                HomeMetric(report?.failedRecent?.toString() ?: "—", "近期失败"),
                HomeMetric(versions.toString(), "运行时版本"),
                HomeMetric(programs.size.toString(), "已装组件"),
                HomeMetric("—", "活跃实例"),
            )
            return HomeUiState.Ready(
                running = running, uptime = "—", online = running,
                metrics = metrics, apps = emptyList(),
            )
        }
    }
}

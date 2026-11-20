package lobos.services.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lobos.services.supply.Cleanup
import lobos.services.supply.InstallGuard
import lobos.ui.space.DirItem
import lobos.ui.space.SpaceEvent
import lobos.ui.space.SpaceUiState
import java.io.File

class SpaceViewModel : ViewModel() {
    private val _state = MutableStateFlow<SpaceUiState>(SpaceUiState.Error("加载中"))
    val state: StateFlow<SpaceUiState> = _state

    fun load(ctx: Context) {
        viewModelScope.launch { _state.value = withContext(Dispatchers.IO) { gather(ctx) } }
    }

    fun onEvent(ctx: Context, event: SpaceEvent) {
        if (event is SpaceEvent.Clean) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { Cleanup.run(ctx) }
                load(ctx)
            }
        }
    }

    companion object {
        fun gather(ctx: Context): SpaceUiState {
            val root = ctx.filesDir
            val free = InstallGuard.freeBytes(root)
            val used = runCatching { Cleanup.dirSize(root) }.getOrDefault(0L)
            val total = used + free
            val dirs = listOf("opt", "usr", "var", "cache").mapNotNull { name ->
                val dir = File(root, name)
                if (!dir.exists()) return@mapNotNull null
                val size = runCatching { Cleanup.dirSize(dir) }.getOrDefault(0L)
                val desc = when (name) {
                    "opt" -> "已装组件与运行时"
                    "usr" -> "系统入口与共享库"
                    "var" -> "状态 · 日志 · 清单"
                    "cache" -> "临时文件"
                    else -> ""
                }
                DirItem(name, desc, InstallGuard.human(size))
            }
            return SpaceUiState.Ready(
                used = InstallGuard.human(used),
                free = InstallGuard.human(free),
                total = InstallGuard.human(total),
                usedRatio = if (total > 0) used.toFloat() / total else 0f,
                path = root.absolutePath,
                dirs = dirs,
            )
        }
    }
}

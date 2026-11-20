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
import lobos.services.supply.ProgramDir
import lobos.services.reg.UnitEntry
import lobos.services.supply.InstallGuard
import lobos.ui.components.ComponentItem
import lobos.ui.components.ComponentsEvent
import lobos.ui.components.ComponentsUiState

class ComponentsViewModel : ViewModel() {
    private val _state = MutableStateFlow<ComponentsUiState>(ComponentsUiState.Loading)
    val state: StateFlow<ComponentsUiState> = _state
    private var filter = 0

    fun load(ctx: Context) {
        viewModelScope.launch { _state.value = withContext(Dispatchers.IO) { gather(ctx, filter) } }
    }

    fun onEvent(ctx: Context, event: ComponentsEvent) {
        when (event) {
            is ComponentsEvent.Filter -> { filter = event.idx; load(ctx) }
            else -> Unit
        }
    }

    companion object {
        private val RUNTIME_IDS = setOf("python", "node", "go", "python-3.11", "python-3.12")
        private val TOOL_IDS = setOf("cmake", "git", "make", "rg", "jq", "curl", "sqlite", "npm", "pnpm", "pkgconf", "bash", "busybox")
        private val LIB_IDS = setOf("openssl", "zlib", "libffi", "crypto", "libcxx")

        fun gather(ctx: Context, filter: Int): ComponentsUiState {
            val programs = runCatching { ProgramIndex.all(ctx) }.getOrDefault(emptyList())
            fun item(p: UnitEntry): ComponentItem {
                val id = p.id
                val activeVer = runCatching { ProgramDir(ctx, id).currentVersion() }.getOrNull()
                val shownVer = activeVer ?: p.version
                val kind = when {
                    RUNTIME_IDS.any { id.startsWith(it) } -> "runtime"
                    TOOL_IDS.any { id.startsWith(it) } -> "tool"
                    LIB_IDS.any { id.startsWith(it) } -> "library"
                    else -> "tool"
                }
                return ComponentItem(
                    id = id, name = id, detail = "$shownVer · $kind",
                    status = if (activeVer != null) "使用中" else "已装",
                    installed = true, inUse = activeVer != null, kind = kind,
                )
            }
            val all = programs.map { item(it) }
            val runtime = all.filter { it.kind == "runtime" }.toMutableList()
            val tools = all.filter { it.kind == "tool" }.toMutableList()
            val libraries = all.filter { it.kind == "library" }.toMutableList()
            if (filter != 1) {
                if (runtime.none { it.id == "python" }) runtime.add(0, ComponentItem("python", "Python", "运行时 · 未安装", "未装", false, kind = "runtime"))
            }
            return ComponentsUiState.Ready(runtime, tools, libraries, filter)
        }
    }
}

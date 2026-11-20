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
import lobos.services.reg.UnitEntry
import lobos.services.supply.PackageInstaller
import lobos.services.supply.ProgramDir
import lobos.ui.components.ComponentItem
import lobos.ui.components.ComponentDetail
import lobos.ui.components.ComponentsEvent
import lobos.ui.components.ComponentsUiState
import lobos.ui.components.VersionItem

class ComponentsViewModel : ViewModel() {
    private val _state = MutableStateFlow<ComponentsUiState>(ComponentsUiState.Loading)
    val state: StateFlow<ComponentsUiState> = _state
    private var filter = 0
    private var detailId: String? = null

    fun load(ctx: Context) {
        viewModelScope.launch { _state.value = withContext(Dispatchers.IO) { gather(ctx, filter, detailId) } }
    }

    fun onEvent(ctx: Context, event: ComponentsEvent) {
        when (event) {
            is ComponentsEvent.Filter -> { filter = event.idx; load(ctx) }
            is ComponentsEvent.OpenDetail -> { detailId = event.id; load(ctx) }
            ComponentsEvent.Back -> { detailId = null; load(ctx) }
            is ComponentsEvent.SwitchVersion -> {
                val id = detailId ?: return
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { runCatching { PackageInstaller.activate(ctx, id, event.version) } }
                    load(ctx)
                }
            }
            is ComponentsEvent.Install -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { runCatching { PackageInstaller.install(ctx, event.id, null) } }
                    load(ctx)
                }
            }
        }
    }

    companion object {
        private val RUNTIME_IDS = setOf("python", "node", "go", "python-3.11", "python-3.12")
        private val TOOL_IDS = setOf("cmake", "git", "make", "rg", "jq", "curl", "sqlite", "npm", "pnpm", "pkgconf", "bash", "busybox")
        private val LIB_IDS = setOf("openssl", "zlib", "libffi", "crypto", "libcxx")

        fun gather(ctx: Context, filter: Int, detailId: String? = null): ComponentsUiState {
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
            if (filter != 1 && runtime.none { it.id == "python" }) {
                runtime.add(0, ComponentItem("python", "Python", "运行时 · 未安装", "未装", false, kind = "runtime"))
            }
            val detail = detailId?.let { id -> buildDetail(ctx, id, programs) }
            return ComponentsUiState.Ready(runtime, tools, libraries, filter, detail)
        }

        private fun buildDetail(ctx: Context, id: String, programs: List<UnitEntry>): ComponentDetail {
            val dir = ProgramDir(ctx, id)
            val current = runCatching { dir.currentVersion() }.getOrNull()
            val versions = runCatching { dir.installedVersions() }.getOrDefault(emptyList())
                .sortedByDescending { it }
                .map { v -> VersionItem(v, if (v == current) "当前激活 · 默认" else "已安装", v == current) }
            val entry = programs.firstOrNull { it.id == id }
            val deps = entry?.requires?.toList().orEmpty()
            val caps = buildString {
                entry?.capabilities?.let {
                    if (it.isNotEmpty()) append(it.joinToString(" · "))
                }
                if (isEmpty()) append("命令与运行时环境")
            }
            return ComponentDetail(id, id, versions, deps, caps)
        }
    }
}

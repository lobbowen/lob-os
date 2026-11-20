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
import lobos.services.supply.CatalogClient
import lobos.services.supply.InstallGuard
import lobos.ui.store.StoreAppItem
import lobos.ui.store.StoreEvent
import lobos.ui.store.StoreUiState

class StoreViewModel : ViewModel() {

    private val _state = MutableStateFlow<StoreUiState>(StoreUiState.Loading)
    val state: StateFlow<StoreUiState> = _state

    fun load(ctx: Context) {
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { gather(ctx) }
        }
    }

    fun onEvent(ctx: Context, event: StoreEvent) {
        when (event) {
            StoreEvent.Refresh -> viewModelScope.launch {
                val cur = _state.value
                if (cur is StoreUiState.Ready) _state.value = cur.copy(refreshing = true)
                withContext(Dispatchers.IO) { runCatching { CatalogClient.refresh(ctx, true) } }
                _state.value = withContext(Dispatchers.IO) { gather(ctx) }
            }
            is StoreEvent.OpenDetail -> Unit
        }
    }

    companion object {
        fun gather(ctx: Context): StoreUiState {
            val installed = runCatching { ProgramIndex.all(ctx).map { it.id } }
                .getOrDefault(emptyList()).toSet()
            val arr = runCatching { CatalogClient.entries(ctx) }.getOrDefault(org.json.JSONArray())
            val out = ArrayList<StoreAppItem>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val name = e.optString("name").ifBlank { e.optString("id") }
                if (name.isBlank()) continue
                val versions = e.optJSONArray("versions")
                val count = versions?.length() ?: 0
                val version = if (count > 0) {
                    versions?.optJSONObject(count - 1)?.optString("version", "") ?: ""
                } else e.optString("version", "")
                val size = e.optLong("size", 0L)
                val sub = buildString {
                    append("v").append(version.ifBlank { "?" })
                    if (count > 1) append(" · ").append(count).append(" 个版本")
                    if (size > 0) append(" · ").append(InstallGuard.human(size))
                }
                val isInstalled = installed.contains(name)
                out.add(StoreAppItem(name, sub, if (isInstalled) "已安装" else "安装", isInstalled))
            }
            return StoreUiState.Ready(out.sortedWith(compareBy({ it.installed }, { it.name })))
        }
    }
}

package lobos.ui.store

import lobos.ui.components.UiState

// 商店列表的一项。纯数据，不含任何系统层引用。
data class StoreAppItem(
    val name: String,
    val versionLabel: String,
    val stateLabel: String,
    val installed: Boolean,
)

sealed interface StoreUiState : UiState {
    data object Loading : StoreUiState
    data class Ready(
        val apps: List<StoreAppItem>,
        val refreshing: Boolean = false,
    ) : StoreUiState
    data class Error(val message: String) : StoreUiState
}

sealed interface StoreEvent {
    data object Refresh : StoreEvent
    data class OpenDetail(val name: String) : StoreEvent
}

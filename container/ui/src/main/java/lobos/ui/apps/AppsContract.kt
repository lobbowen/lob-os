package lobos.ui.apps

import lobos.ui.components.UiState

data class AppItem(
    val id: String,
    val name: String,
    val category: String,
    val size: String,
    val installed: Boolean,
    val symbol: String,
    val color: Long,
)

sealed interface AppsUiState : UiState {
    data class Ready(val apps: List<AppItem>) : AppsUiState
    data class Error(val msg: String) : AppsUiState
}

sealed interface AppsEvent {
    data object AddApp : AppsEvent
    data class Install(val id: String) : AppsEvent
}

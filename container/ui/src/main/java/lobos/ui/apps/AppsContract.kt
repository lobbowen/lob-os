package lobos.ui.apps

import lobos.ui.components.UiState

sealed interface AppsUiState : UiState {
    data class Ready(val apps: List<String>) : AppsUiState
    data class Error(val msg: String) : AppsUiState
}

sealed interface AppsEvent {
    data object AddApp : AppsEvent
}

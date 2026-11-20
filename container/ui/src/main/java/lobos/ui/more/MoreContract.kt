package lobos.ui.more

import lobos.ui.components.UiState

sealed interface MoreUiState : UiState {
    data object Ready : MoreUiState
}

sealed interface MoreEvent {
    data class Open(val itemKey: String) : MoreEvent
}

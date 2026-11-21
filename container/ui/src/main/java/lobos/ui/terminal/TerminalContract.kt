package lobos.ui.terminal

import lobos.ui.components.UiState

sealed interface TerminalUiState : UiState {
    data class Ready(val screen: List<TerminalLine>, val connected: Boolean = true) : TerminalUiState
    data class Error(val msg: String) : TerminalUiState
}

sealed interface TerminalEvent {
    data class Input(val bytes: String) : TerminalEvent
}

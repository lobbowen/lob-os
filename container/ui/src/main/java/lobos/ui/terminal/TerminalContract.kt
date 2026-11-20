package lobos.ui.terminal

import lobos.ui.components.UiState

sealed interface TerminalUiState : UiState {
    data class Ready(
        val lines: List<String>,
        val prompt: String = "",
    ) : TerminalUiState

    data class Error(val msg: String) : TerminalUiState
}

sealed interface TerminalEvent {
    data class Submit(val cmd: String) : TerminalEvent
}

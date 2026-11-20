package lobos.ui.space

import lobos.ui.components.UiState

data class DirItem(val name: String, val desc: String, val size: String)

sealed interface SpaceUiState : UiState {
    data class Ready(
        val used: String,
        val free: String,
        val total: String,
        val usedRatio: Float,
        val path: String,
        val dirs: List<DirItem>,
    ) : SpaceUiState
    data class Error(val message: String) : SpaceUiState
}

sealed interface SpaceEvent {
    data class OpenDir(val name: String) : SpaceEvent
    data object Clean : SpaceEvent
}

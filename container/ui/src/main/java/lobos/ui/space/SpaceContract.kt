package lobos.ui.space

import lobos.ui.components.UiState

/** A file or folder entry in the system disk browser. */
data class FsEntry(
    val name: String,
    val isDir: Boolean,
    val size: String,
    val modified: String,
    val kind: String,
)

sealed interface SpaceUiState : UiState {
    data class Ready(
        val used: String,
        val free: String,
        val total: String,
        val usedRatio: Float,
        val path: String,
        val breadcrumb: List<String>,
        val entries: List<FsEntry>,
        val entryCount: Int,
    ) : SpaceUiState
    data class Error(val message: String) : SpaceUiState
}

sealed interface SpaceEvent {
    data class OpenDir(val name: String) : SpaceEvent
    data object NavigateUp : SpaceEvent
    data object Refresh : SpaceEvent
    data object Clean : SpaceEvent
}

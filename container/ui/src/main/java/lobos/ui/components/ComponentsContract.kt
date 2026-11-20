package lobos.ui.components

data class ComponentItem(
    val id: String,
    val name: String,
    val detail: String,
    val status: String,
    val installed: Boolean,
    val inUse: Boolean = false,
    val kind: String = "runtime",
)

data class VersionItem(val version: String, val detail: String, val active: Boolean)

sealed interface ComponentsUiState : UiState {
    data object Loading : ComponentsUiState
    data class Ready(
        val runtime: List<ComponentItem>,
        val tools: List<ComponentItem>,
        val libraries: List<ComponentItem>,
        val filter: Int = 0,
    ) : ComponentsUiState
    data class Error(val msg: String) : ComponentsUiState
}

sealed interface ComponentsEvent {
    data class OpenDetail(val id: String) : ComponentsEvent
    data class Install(val id: String) : ComponentsEvent
    data class SwitchVersion(val version: String) : ComponentsEvent
    data class Filter(val idx: Int) : ComponentsEvent
}

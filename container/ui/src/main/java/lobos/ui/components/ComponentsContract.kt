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

data class VersionItem(
    val version: String,
    val detail: String,
    val active: Boolean,
    val installed: Boolean = true,
)

data class ComponentDetail(
    val id: String,
    val name: String,
    val versions: List<VersionItem>,
    val deps: List<String>,
    val capabilities: String,
    val installed: Boolean = true,
)

sealed interface ComponentsUiState : UiState {
    data object Loading : ComponentsUiState
    data class Ready(
        val runtime: List<ComponentItem>,
        val tools: List<ComponentItem>,
        val libraries: List<ComponentItem>,
        val filter: Int = 0,
        val detail: ComponentDetail? = null,
        val notice: String? = null,
    ) : ComponentsUiState
    data class Error(val msg: String) : ComponentsUiState
}

sealed interface ComponentsEvent {
    data class OpenDetail(val id: String) : ComponentsEvent
    data class Install(val id: String, val version: String? = null) : ComponentsEvent
    data class SwitchVersion(val version: String) : ComponentsEvent
    data class Filter(val idx: Int) : ComponentsEvent
    data object Back : ComponentsEvent
}

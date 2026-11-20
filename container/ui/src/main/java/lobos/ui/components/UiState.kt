package lobos.ui.components

// 所有屏幕 UiState 的标记接口。UI 层与系统层的边界契约：
// 系统层产出实现了本接口的 sealed 状态，UI 层只按状态渲染、只回传事件。
interface UiState

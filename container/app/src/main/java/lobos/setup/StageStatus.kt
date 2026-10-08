package lobos.setup

/**
 * 引导流程里每一段的进展 —— 界面用它决定「已完成 / 下一步 / 待办 / 等待 / 失败」怎么显示。
 *
 * 以前这里没有这个类型，`SetupActivity` 直接 import 一个不存在的类
 * —— 那是项目初始提交就带的编译错误，一直没被发现（从没编译过）。
 */
enum class StageStatus { DONE, CURRENT, NEXT, BLOCKED, FAILED }

/** 段内每一步的状态 */
enum class StepStatus { DONE, ACTION, BLOCKED, FAILED }
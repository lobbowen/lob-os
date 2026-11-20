# UI 规范检测报告（2026-11-21）

用户反馈：UI 稳定性极差，"点进去退不了、点进去就乱"。要求按原生界面规范系统检测。
本报告基于真机实测 + 代码审查，不修改代码。所有结论带证据。

## 一、结论：不符合原生规范，且问题成体系

核心判断：当前 UI 是"多个 Activity 各自 startActivity + Compose 内部状态"混合，
没有统一的导航架构和返回栈契约。这违反 Android 原生导航规范，是"乱"的根源。

## 二、实测发现的问题（按严重程度）

### P0 严重：Compose 内部状态未接系统返回键
证据：组件页 -> 点 python 进详情（Compose 内部 detail 状态切换，不是新 Activity）
-> 按系统返回键 -> 直接回到首页（SystemHomeActivity），而不是详情->列表。
根因：ComponentsScreen 的 detail 状态只有 UI 层的 onBack 回调，
没有用 androidx.activity.compose.BackHandler 接管系统返回键。
用户预期的"返回上一层"和实现不一致，这是"退不了/乱"的直接表现。

### P0 严重：混合导航架构（Activity + Compose 内部状态并存）
证据：13 个 Activity，基类混用（5 个 AppCompatActivity、7 个 Activity、1 个全限定），
导航有 7 处 startActivity + Compose 内部 detail 状态切换两套机制。
同一个 App 内"有的页面是新 Activity、有的是 Compose 状态"，导致返回行为不一致：
- 商店->详情：startActivity（新 Activity，系统返回正常）
- 组件->详情：Compose 内部状态（系统返回直接退出整个页面）
用户无法形成稳定的返回心智模型。违反 Material 导航一致性规范。

### P1 高：触摸热区远低于原生 48dp 标准
证据：首页功能条 5 个入口，uiautomator 实测文字热区
终端(117-181) 组件(312-376) 空间(507-571) 应用(703-767) 更多(899-963)，
每个仅 64px 宽 x 39px 高（约 21dp x 13dp），远低于 Material 48dp（约 144px）。
虽然 Column 有 86dp 高的容器，但 uiautomator 只识别到文字区域，
且用近似坐标（x=233/576/960）点击会点错入口（实测 tap576 进了空间而非组件）。
用户体感就是"点不动/点错"。违反 Material touch target 规范。

### P1 高：无统一 AppBar/Up 导航
证据：所有 Compose 屏（终端/应用/组件/关于/商店）都没有原生 TopAppBar
和 Up 按钮（向下箭头）。各屏自行用大标题，没有统一的上层导航入口。
系统返回键是唯一返回手段，而它又被 P0 问题破坏。
违反 Material App bars 与 Android  Up navigation 规范。

### P2 中：Activity 无显式父 Activity 声明
证据：Manifest 里除 QuickAppLaunch 外没有任何 parentActivityName，
没有 launchMode 约束（除 QuickAppLaunch 的 singleTask），
startActivity 后返回栈靠系统默认行为，各 Activity 可能被重复入栈。
之前多次实测出现过同一 Activity 多实例堆叠（dumpsys 里 SystemHome 重复 8 次）。

### P2 中：BaseActivity/主题不统一
证据：themes.xml 只有 Theme.AppCompat.DayNight.NoActionBar，
但 7 个 Activity 继承 android.app.Activity（不消费 AppCompat 主题），
5 个继承 AppCompatActivity，Compose 用 LobosTheme 自己包一层，
三套主题体系并存。窗口 insets、状态栏处理各屏不一致（首页单独加 statusBarsPadding，
详情页又加一遍，旧 View 页用 Ui.applyInsets）。

## 三、与主流原生规范的差距

主流 2026 Android 单 Activity 架构（Navigation 3）：
- 一个 Activity + NavHost + 统一的返回栈
- 每个屏是 composable destination，系统返回键由 NavController 统一处理
- 统一 TopAppBar + Up 按钮
- 触摸热区自动满足 48dp

当前实现：13 Activity + 内部 Compose 状态 + 无 NavController，
等于"用现代 UI 工具实现了传统散乱导航"，UI 换了 Compose 但架构没跟上。

## 四、建议的整改方向（不立即执行，待确认）

### 方案 A：补齐现有架构的规范缺口（中等改动，风险低）
1. 所有 Compose 屏加 BackHandler 接管系统返回（先修 P0）
2. 组件详情的返回语义统一：内部状态屏按返回键先回列表
3. 功能条触摸热区扩到 48dp+（Column 填满整格、clickable 在整格上）
4. 统一加 TopAppBar/Up 按钮
5. Manifest 补 parentActivityName
6. 统一所有 Activity 基类为 AppCompatActivity

优点：改动可控，不动整体架构。缺点：治标，Activity 堆叠问题仍在。

### 方案 B：迁移到单 Activity + Navigation 3（大改动，风险高）
把所有屏改成 composable destination，一个 NavHost 管返回栈。
优点：从根上符合 2026 原生规范。缺点：工作量大，现有系统层接线要重写。

## 五、报缺
- 未做 UI test 设备运行（只编译通过），交互回归靠手工 adb 坐标点击。
- Activity 堆叠的长期稳定性只观察到重复实例，未做长时间压力测试。
- Compose 屏的 48dp 热区：uiautomator 只识别文字，实际 clickable 范围需看源码确认，
  但从用户体感和坐标测试看，热区确实偏小。

---

## 十二、整改补记：架构升级与结论修正（2026-11-21）

### 已执行：方案 B 单 Activity + Navigation 3
- 13 Activity -> 1 MainActivity + NavDisplay（navigation3 1.2.2）
- Destination 密封类 + mutableStateListOf backStack + entryProvider
- 系统返回由 NavDisplay.onBack 统一 pop
- 组件详情从"内部状态+startActivity 双机制"改为独立 destination（P0 根治）
- More 分流 check/diagnosis 到 destination，新增 SimpleScreen(TopAppBar+Up)

### 真机压力测试结论（多轮进出）
- 5 入口逐个进出：全部进对屏、返回回首页
- 组件->详情->系统返回：正确回列表（P0 修复）
- topResumed 始终是唯一 MainActivity（单 task 单 Activity）
- 进程数 1、0 崩溃、无 Activity 实例泄漏

### 结论修正
- 原 P1"48dp 热区不达标"是误判：uiautomator 抓的是文字 bounds，
  实际 clickable 挂在整格 Column（65x86dp），热区达标。
- 原 P0"返回乱"确为真实问题，已由 Navigation 3 统一栈根治。

### 遗留
- 旧 13 Activity 源码仍注册但无外部入口可达（死代码，未删以免影响程序快捷启动/deep link）。
- TopAppBar：主屏保留已确认的沉浸式大标题设计（首页明确要求无标题栏），
  仅二级屏（体检/诊断）用 SimpleScreen 的 TopAppBar+Up。规范统一与设计稿的取舍已记录。

# UI 重构执行计划（详细）

本文是 `UI-REFACTOR-PLAN.md` 的细化，回答"怎么开工"。本阶段仍不动代码，只定计划。
目标：把命令式 View UI 迁到 Jetpack Compose + Material 3，根治"点不动"，做出设计感，
并让独立安卓前端工程师能接手。

---

## 阶段 0：决策冻结（开工前，本轮完成）

产出（都已在上两篇文档，此处汇总为开工清单）：
- 技术栈冻结：Kotlin 2.4.20 + Compose Compiler 同版本 + Compose 1.13 + Compose Material 3 1.5.x + BOM。
- 设计冻结：MaterialExpressiveTheme + 动态颜色 + Material Icons；1-2 个 hero moment；不自造设计系统。
- 架构冻结：UDF + sealed UiState + immutable data class + ViewModel/StateFlow；
  事件向下、状态向上；每个 composable 由 state 派生，不手动镜像。
- 迁移法冻结：官方增量法，ComposeView 互操作，逐屏替换，不一次全迁。
- 协作冻结：前端工程师负责 :ui 模块与主题/组件库；系统侧提供 UseCase 接口与 UI state 契约。

---

## 阶段 1：硬门槛与骨架（前置，无界面改动）

### 1.1 compileSdk 35 -> 37
- 原因：Compose 1.12+ 要求 compileSdk 37（AGP 9 已满足）。
- 步骤：本地用 dl.google.com 下 platform 37 到 /home/bowen/ndk-acquire/sdk；
  改 container/app/build.gradle.kts 的 compileSdk=37（targetSdk 暂保持 35，互不影响）。
- 验收：gradle assembleDebug 成功（编译通过，UI 未变）。
- 风险：CI 镜像 SDK 版本；本阶段先把本地跑通，CI 后续同步。

### 1.2 引入 Compose 依赖
- 根 build.gradle.kts：加 `id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"`。
- container/app/build.gradle.kts：加 compose-bom（2026.09.x 稳定版）、
  androidx.compose.material3:material3、androidx.compose.ui:ui-tooling-preview、
  androidx.activity:activity-compose、androidx.lifecycle:lifecycle-viewmodel-compose。
- 验收：空 ComposeView 能 inflate，gradle 编译通过。

### 1.3 建 :ui 模块骨架
- 新建 module :ui（或 app 内 ui 包，取决于是否要独立给前端工程师——建议独立 module）。
- 内容：Theme.kt（MaterialExpressiveTheme + colorScheme + 动态颜色）、
  Type.kt、通用组件（LobosCard/LobosButton/LobosNavBar 的 Composable 版）、
  Preview 样例。
- 验收：Preview 可见 M3 组件；无业务逻辑。

---

## 阶段 2：第一个 Compose 屏（垂直切片，验证全链路）

选 SystemStoreActivity（商店）作为第一个——交互相对集中、用户常看、结构浅。

### 2.1 UI state 契约
- StoreUiState：sealed interface（Loading / Ready(entries, installed, refreshing) / Error(msg)）。
- StoreEvent：Refresh / OpenDetail(name) / 等。
- ViewModel：暴露 StateFlow<StoreUiState>，CatalogClient.entries/refresh 走 UseCase，
  UI 只发事件。

### 2.2 Composable 化
- StoreScreen(state, onEvent)：LazyColumn 列应用卡片，M3 Card + ListItem；
  刷新按钮/空态/错误态由 state 派生；Material Icons。
- 用 ComposeView 在 SystemStoreActivity 里 setContent，保留 Activity 外壳。

### 2.3 验收（这是"根治点不动"的判据）
- 设备实测：快速连点刷新/卡片，每次都响应，不丢点击、不闪烁、滚动位置保持。
- UI test：composeTestRule 断言点击刷新触发 onEvent(Refresh)、卡片点击触发 OpenDetail。
- Preview：StoreScreen 各 state 都有 Preview。
- 视觉截图：M3 卡片/配色/图标，与旧 GitHub Dark 对比有设计感提升。

---

## 阶段 3：第二个屏（详情/版本选择，最高交互密度）

SystemInstallActivity（详情页）有 7 处点击、版本安装/切换/卸载，是最关键的交互面。

### 3.1 UI state 契约
- DetailUiState：Loading / Ready(name, versions, currentVersion, installedVersions, installState, progress) / Error。
- DetailEvent：Install(version) / Activate(version) / Uninstall / Reinstall。
- ViewModel 封装 PackageInstaller.install/activate/uninstall + InstallProgress 流。

### 3.2 Composable 化
- DetailScreen：版本列表（每版本一项，状态徽标：当前/已装/未安装）、
  安装/切换/卸载按钮由 state+enabled 派生、进度条走 InstallProgress 四阶段。
- 状态变化用 M3 motion（列表项、进度），1 个 hero moment：版本切换的确认/反馈。

### 3.3 验收
- 设备实测：装 3.11、切 3.14、卸载，每步按钮立即响应，无"点不动"；进度可见。
- UI test：断言各事件回调、按钮 enabled 随 state 变化。
- 与旧版功能对齐：版本展示、安装、切换、卸载一个不少。

---

## 阶段 4：逐屏迁移其余（按交互密度排序）

优先级（先交互多的）：
1. SystemHomeActivity（5 点击，2 重建）——主控制页，第二个 hero moment（控制环/停止）。
2. HostControlActivity（312 行，系统级控制）——最复杂，最后迁，保持 View 直到 Compose 版验证过。
3. SystemComponentDetailActivity（2 点击）——组件详情。
4. SystemCheckActivity（2 点击）——体检。
5. SystemComponentsActivity / SystemSpaceActivity / SystemAboutActivity / SystemRecoveryActivity
   （各 1 点击或静态）——低风险，批量迁。
6. QuickAppLaunchActivity——无 UI（纯转发），不动。

每屏验收统一：点击响应、UI test、Preview、视觉对齐 M3、功能不回退。

---

## 阶段 5：设计收尾与全量验证

- 全 App 统一 MaterialExpressiveTheme + 动态颜色；移除旧 Ui.kt 硬编码调色板。
- 图标全换 Material Icons；自绘 IconView 仅在无等价物时保留（AndroidView 过渡）。
- motion：spring 动效覆盖导航与状态切换；hero moment 控制在 1-2 个。
- 折叠屏适配：WindowSizeClass（Ui.widthClass 已有）迁移到 Compose 的
  androidx.compose.material3.windowsizeclass，保持内屏/外屏两套布局。
- 全量设备回归：11 屏逐屏点一遍，重点"点不动"问题消失。

---

## 独立前端工程师协作契约

### 交给前端的（:ui 模块）
- Theme.kt / 组件库 / 全部 Composable 屏 / Preview / UI test。
- 输入：UI state 数据类定义 + onEvent 回调签名（系统侧提供）。
- 验收：每屏 Preview + UI test + 设备上点击响应正常。

### 系统侧保留（不交给前端）
- 系统服务、能力 API、HAL、签名、安装器、包管理、RuntimeResolver。
- UseCase 接口：给 UI 层提供 StateFlow，接收事件；UI 不直接碰系统层。

### 接口定义顺序（关键）
1. 系统侧先定 UI state 数据类 + onEvent 签名（阶段 2.1/3.1 示范）。
2. 前端按契约实现 Composable。
3. 双方用 Preview/UI test + 设备实测验收。

这样前端不需要理解系统层，系统侧不需要写 UI；契约先行避免返工。

---

## 风险与报缺

- compileSdk 37 的 CI 镜像影响未核（本地先跑通，CI 同步另排）。
- A2UI 库是否正式 release 未核（现阶段不依赖它，只借鉴"目录=合约"形状）。
- 我们现状 UDF 程度未核——阶段 2 会建立第一个真正的 UDF 屏，其余逐步对齐。
- 引入 Compose 会让 APK 体积增大、编译时间变长——可接受（系统应用非尺寸敏感）。
- HostControlActivity 最复杂，放最后迁，风险最高，预留回退到 View 的能力
  （ComposeView 可随时换回原 View 树）。

---

## 开工前需要用户确认

1. 是否按此计划执行（阶段 1 -> 2 -> 3 -> ...）。
2. :ui 是独立 module 还是 app 内 ui 包（建议独立 module，便于前端工程师接手）。
3. 前端工程师是外部人还是让我起一个 subagent 负责 :ui——影响协作方式。

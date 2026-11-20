# UI 重构方案：从命令式 View 到 Jetpack Compose + M3

用户认定当前 UI 有巨大问题：交互点不动、没设计感。目标是产品级设计感 + 稳定交互，
并引入独立安卓前端工程师协作。本文是调研结论与执行方案，不是立即动手的许可。

---

## 一、调研结论（2026-11-20，官方出处）

### 1.1 主流框架：Jetpack Compose 已是唯一官方方向
- [事实] Compose 是官方声明式界面工具包，官方参考应用 Now in Android 全用 Compose。
- [事实] Material Android 宣布 all-in on Compose：Material Views 1.14.0 是 Views 最终稳定版，
  之后只维护不加新功能；新特性集中在 Compose Material 3。
- [事实] Compose Material 3 1.5.0、Compose 1.13 稳定；Material 2 已弃用。
- 出处：github.com/android/nowinandroid、m3.material.io/blog/material-is-compose-first、
  developer.android.com/develop/ui/compose/designsystems/material3。
- [判断] 新界面与本项目 UI 外壳默认 Compose，不再用 View 写新东西。

### 1.2 设计感：Material 3 Expressive
- [事实] M3 Expressive 是 Material Design 3 的扩展版本（不是 M4），与 Android 16 视觉一致；
  含 14 个新/更新组件、motion springs 物理动效、扩展形状库、强调排版、动态配色。
  官方建议：形状多样化、丰富颜色、排版引导、容器化、流体动效、1-2 个 hero moment。
- [判断] 不自造设计系统。直接 Compose Material 3 + Expressive 主题 + 动态颜色 +
  Material Icons。自绘 Canvas 图标替换掉。先落 M3，Expressive 作为其上增量。

### 1.3 AI 友好：官方 A2UI（智能体到界面渲染器）+ Android skills
- [事实] 官方 Compose 文档有整条 A2UI 路线：AI 生成界面由受限协议（A2UI JSON）驱动、
  由 Compose 渲染，"无需执行任意代码"；含架构验证、错误自动回报代理自我修正、
  Material 3 基本目录、A2UI 规范 0.9.1。核心概念"目录（catalog）= 合约"：
  定义 agent 可用的界面元素，不让 agent 生成任意代码。
- [事实] 官方 Android skills（agentskills.io 标准）：`android skills add
  migrate-xml-views-to-jetpack-compose`，含迁移、性能、测试 skill；迁移要求每个
  composable 带 Preview + UI test。
- [判断] 我们已有同构的 ApiSpec/能力目录，"目录=合约"是把既有形状搬到 UI 侧，
  不是新概念。AI 工作流：Android Studio/Cursor + 官方 skills + Preview/UI test 闭环。
  Figma Code Connect + MCP 可选增强；v0/Relume/Galileo 不适合本目标。

---

## 二、现状诊断（为什么点不动、没设计感）——读代码得出

- 11 个 Activity 全是代码构造 View（`Ui.kt` 的 TextView/LinearLayout/GradientDrawable）。
- 9 个 Activity 的 render() 都是 `body.removeAllViews()` + 全量重建 → 点击时 View 被销毁重建，
  这是"点不动"的直接根因：点击回调执行中或执行后 View 树被替换，滚动位置丢失、
  点击态丢失、视觉闪烁。
- `Ui.scaffold` 对整个 root 设 `LAYER_TYPE_SOFTWARE`（软件渲染）→ 卡顿。
- 颜色是硬编码 GitHub Dark 调色板（BG/SURFACE/ACCENT 常量），绕过主题系统 → 无设计体系。
- 主题是 Theme.AppCompat（Material 1），既不是 M3 也没有 MaterialTheme。
- 没有 XML 布局（只有 activity_main.xml）→ 好消息：没有"XML→Composable"迁移面，
  只需把 `Ui.kt` 组件换成 Composable。

---

## 三、硬门槛（迁移前置，机械可核）

| # | 项 | 现状 | 要求 | 处置 |
|---|---|---|---|---|
| 1 | Kotlin | 2.4.20 | Compose 编译器同版本 | 满足 |
| 2 | AGP | 9.4.0 | AGP 9 | 满足 |
| 3 | compileSdk | 35 | Compose 1.12+ 要 37 | 不满足，须升 37 + 下 platform 37 |
| 4 | Compose 编译器插件 | 无 | org.jetbrains.kotlin.plugin.compose | 须加 |
| 5 | BOM | 无 | compose-bom 管理版本 | 须加 |
| 6 | material3 依赖 | 无 | androidx.compose.material3:material3 | 须加 |

compileSdk 35→37 会连带 CI 镜像与 SDK 下载，是最大前置。本地 dl.google.com 14MB/s 可下 platform 37。

---

## 四、迁移策略（官方增量法，适配本项目）

1. **逐屏迁移，不一次全迁**：先迁用户最常看、最近才写的两个面——
   SystemStoreActivity（商店）与 SystemInstallActivity（详情/版本选择）。
   它们 View 结构浅，最容易换成 Composable。
2. **互操作**：用 ComposeView 在现有 Activity 里嵌入 Compose 内容
   （保留现有系统服务/能力 API 不动，UI 层逐步替换）。
   全迁完成后再考虑单 Activity + Navigation 3，本轮不做。
3. **组件库**：把 Ui.kt 的 card/button/text 逐步换成 Composable，
   形成 :ui 模块的主题与组件库（MaterialTheme + colorScheme + Material Icons）。
4. **架构性根治"点不动"**：Compose 用 sealed UiState + 不可变 data class + ViewModel
   暴露 StateFlow + UDF（事件向下、状态向上）。按钮 enabled/onClick 由 state 派生，
   不再 removeAllViews 全量重建。每个交互组件带 Preview + UI test 断言点击改状态。
5. **设计落地**：MaterialExpressiveTheme + 动态颜色 + 1-2 个 hero moment
   （如主控制环/停止按钮），其余克制一致；自绘图标换 Material Icons。

---

## 五、独立安卓前端工程师协作方式

- 可行且值得：UI 与系统层边界清晰，Compose UI 模块可独立开发/预览/测试，
  不碰 HAL/服务/签名。
- 分工：前端工程师负责 :ui 模块、主题、组件库、各屏 Composable；
  系统侧提供稳定的 UseCase/StateFlow 接口；UI state 契约先定义再实现。
- 约束（防止跑偏）：必须用官方 Compose + Material 3，禁止自造设计系统/引入非官方框架；
  系统签名/权限代码仍归核心团队；每个 composable 必须带 Preview + UI test 才可合入。

---

## 六、执行顺序（待用户确认后开始）

1. 升 compileSdk 35→37，下 platform 37，加 Compose 编译器插件 + BOM + material3 依赖（编译验证）
2. 建 :ui 模块骨架：MaterialTheme + colorScheme + 通用组件（Preview 先行）
3. 迁 SystemStoreActivity 为第一个 Compose 屏（ComposeView 嵌入），带 UI test
4. 迁 SystemInstallActivity（详情/版本选择）
5. 设备验证：交互流畅（不再点不动）、视觉为 M3、UI test 通过
6. 逐屏迁移其余 7 个 Activity
7. 引入前端工程师后按此规范继续，不做二次设计体系

---

## 七、报缺（本次调研未过）

- m3.material.io 正文 JS 渲染取不到，M3 Expressive 引的是 Android 官方文档描述。
- developer.android.com 原域 TLS 不可达，用 developer.android.google.cn 官方镜像。
- compileSdk 37 对现有 CI 镜像的影响未核。
- A2UI 库是否正式 release（还是 preview）未核，规范 0.9.1。
- 现状 UDF 程度与测试面未核。

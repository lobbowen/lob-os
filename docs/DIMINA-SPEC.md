# Dimina 官方规范（我们必须遵守的部分）

> 来源：`didi/dimina` 仓库的 `android/README.md` 与根 `README.md`，2026-10 核对。
> 本文只记**我们踩到的那几条**，不是完整文档。

---

## 1. 官方怎么设计的

根 README 的原话：

> 将 WXML、WXSS 与 JavaScript / TypeScript 源码编译为**统一资源包**，再交由容器加载。
> **逻辑与视图分开运行**：业务逻辑运行在独立 JS 引擎或 Worker 中，视图交给 WebView 渲染。

| 平台 | 逻辑引擎 | 视图容器 |
|---|---|---|
| Android | QuickJS | Android WebView |

**「逻辑与视图分开」是官方设计，不是缺陷。** 编译产物里页面 JS 独立成文件
（`pages/<页>/index.js` → `pages_<页>.js`）是正常的，容器自己会按 `app-config` 加载。

**所以：不要在宿主侧把 `pages_*.js` 合并进 `logic.js`。** 那是补丁，破坏官方结构。

---

## 2. Android 接入的硬要求

官方 README「步骤 4：启动小程序」写明，每个小程序文件夹必须包含：

### 2.1 `config.json` 必含 `path`

```json5
{
  "appId": "wx92269e3b2f304afc",
  "name": "小程序名称",
  "path": "example/index",     // ← 入口路径，我们曾长期缺失
  "versionCode": 1,
  "versionName": "1.0.0"
}
```

**`path` 就是入口路径，容器靠它决定打开哪一页。缺了 → 容器起来但没有入口 → 灰屏。**

### 2.2 zip 名必须与 appId 一致

```txt
assets/jsapp/
  ├── wx92269e3b2f304afc/
  │   ├── config.json
  │   └── wx92269e3b2f304afc.zip      ← 文件名 = appId
```

### 2.3 启动时 `MiniProgram(path = …)`

```kotlin
val miniProgram = MiniProgram(
    appId = "wx92269e3b2f304afc",
    name = "小程序名称",
    path = "example/index",           // ← 不是 null
    versionCode = 1,
    versionName = "1.0.0",
)
Dimina.getInstance().startMiniProgram(context, miniProgram)
```

---

## 3. 我们的对应实现

| 官方要求 | 我们的做法 |
|---|---|
| `config.json` 带 `path` | 清单加 `ui.entry`（快应用必填，限件内相对路径），装入时由宿主写进 `config.json` |
| zip 名 = appId | `QuickAppHost.install` 打 `<appId>.zip` |
| `MiniProgram(path=…)` | `open()` 从 `config.json` 读回再传，不再传 `null` |

`ui.entry` 由**发布方**在 `program-manifest.json` 里声明，宿主不猜 ——
宿主只负责把它落到 `config.json` 的 `path` 字段。

---

## 3.5 Android 容器配置（易错）

| 配置 | 官方语义 | 我们的取值 |
|---|---|---|
| `setShowCapsule` | 胶囊显隐，SDK 全局启动配置 | `false`（控制面不显示返回箭头） |
| `setShowLaunchLoading` | 默认启动遮罩；关闭后内容仍需等实际加载完 | `false` |
| `setEnableMultiTask` | **默认 true = 独立最近任务卡片**；false = 进宿主任务栈 | `false` |
| `setVirtualFilePrefix` | 「**可选**」——但 Android 视图层走 WebView 域名映射，**设了反而坏事** | **不设** |

### 3.5.1 `setVirtualFilePrefix` 是坑

官方 `android/README.md` 把它列为可选并说「必须在 init 时设置」，
但《小程序包更新说明》写明：

> Android 端 WebView 通过 `https://appassets.androidplatform.net/jsapp/`
> 映射到 `${filesDir}/jsapp/`

**视图层根本不走这个前缀。** 我们按 `android/README.md` 的字面加了它，
结果视图层找不到文件（真机报 `resourceLoaded: module not found`）—— 
**文档两处对不上时，以描述实际加载机制的那份为准。**

### 3.5.2 `setEnableMultiTask` 必设 false

官方《宿主管理小程序版本与胶囊》：

> 默认 `true` 保持独立任务及页面保活行为。Android 小程序页面进宿主任务栈，
> 不再创建独立的最近任务卡片。

真机日志里出现过 `Force finishing activity PanelActivity` +
`TransitionChain: Combining AR.finish-force-crash (CLOSE)`，
独立任务栈被拆时 WebView 会一起没。

---

## 4. 编译器的输入规范（实测）

`@dimina/compiler` 的 `DEFAULT_TEMPLATE_EXTS` 只有 `[".wxml", ".ddml"]`：

| 文件 | 必需 | 说明 |
|---|---|---|
| `project.config.json` | ✅ | `appid` 决定产物目录名，缺了目录叫 `undefined/` |
| `app.json` | ✅ | `pages` 指页面路径 |
| `app.js` | ✅ | 用全局 `App({...})`，**不是** `export default` |
| `app.wxss` | ✅ | |
| `pages/<页>/index.js` | ✅ | 页面逻辑，与视图分离 |
| `pages/<页>/index.json` | ✅ | `{"componentFramework":"glass-easel"}` |
| `pages/<页>/index.wxml` | ✅ | 视图。**`.ux` 不被识别** |
| `pages/<页>/index.wxss` | ✅ | 页面样式 |
| `config.json` | ✅ | **编译器不产出**，宿主/发布方负责（我们往里写 `backend.endpoint` 与 `path`） |

事件绑定用 **`bind:tap`**，不是 `onclick`；容器标签用 `<view>`，不是 `<div>`。

---

## 5. 走过的弯路（别再走）

| 症状 | 真实原因 |
|---|---|
| 界面空白 | 手写 `Page({...})`，没有 render（渲染层是编译器从 `.wxml` 生成的） |
| 界面灰色 | `config.json` 缺 `path` + `MiniProgram(path = null)` |
| 页面产物 0 字节 | 用了 `.ux`（编译器只认 `.wxml`），视图层被静默跳过 |
| 按钮点了没反应 | 用了 `onclick`（自己编的），官方是 `bind:tap` |
| `path-version-mismatch` | 打包脚本版本号硬编码，与清单 version 不一致 |
| 以为要合并 `pages_*.js` | **错的** —— 逻辑与视图分开是官方设计，容器自己会加载 |

**共同点：都是没看官方示例就自己猜。** 官方示例在
`dsrc/examples/miniprogram/base`，Android 接入文档在 `didi/dimina` 的 `android/README.md`。

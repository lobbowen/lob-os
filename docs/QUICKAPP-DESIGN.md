# LobOS 快应用框架 · 落地方案

> **决策（2026-10-04）**：采用 **滴滴 dimina（星河小程序）** 作为快应用框架。
> 配套：`QUICKAPP-FRAMEWORK.md`（共识与现状基线）。

---

## 0. 为什么是 dimina（决策依据）

**决策理由**（产品判断）：

1. **免去后期维护** —— 框架由滴滴维护，我们跟随升级，不养一套自研 UI 框架。
2. **成本最低** —— 省掉自建界面框架的全部工作量（估约 2200 行 Kotlin/Java + 渲染引擎 + 组件库）。
3. **开发者友好** —— 开发者写的是微信小程序语法（WXML/WXSS/JS），
   Taro / uni-app 都能直接编译，现有生态和资料全部可用。

**核实的资质**（逐项查证，非转述）：

| 项 | 核实结果 |
|---|---|
| 许可证 | **Apache-2.0**（仓根 LICENSE 原文已读） |
| JS 引擎 | **QuickJS — MIT**（`third_party/quickjs/upstream/LICENSE`，Bellard 未改快照） |
| WASM 运行时 | **WAMR 2.4.5 — Apache-2.0**（`third_party/wamr/LICENSE`） |
| 引擎授权 | **无 copyleft 障碍**（对比：hapjs 的 `jsenv-runtime` 是 EPL-1.0，闭源不可用） |
| 活跃度 | 2025-04 创建，2026-10-03 仍在提交，★945 |
| 集成方式 | JitPack `com.github.didi.dimina:dimina`，一行 `Dimina.init()` |
| 完整度 | `examples/miniprogram` 1396 个文件（278 wxml / 356 wxss / 321 js / 49 wxs）——能跑复杂小程序 |

---

## 1. 框架给的是什么

```
小程序源码 (WXML / WXSS / JS)
        │  DMCC 编译器（逻辑/视图/样式并行）
        ▼
运行时资源包 (main/ + subpackage/ + static/)
        │
    ┌───┴────────────────────────────────────┐
    │  Service 逻辑层（QuickJS）              │
    │    App · Page · Component · wx API     │
    ├────────────────────────────────────────┤
    │  Container Bridge                      │
    │    消息路由 · 页面 bridgeId · 回调      │
    ├────────────────────────────────────────┤
    │  Render 渲染层（Vue runtime · DOM）     │
    │    跑在 Android WebView                │
    └────────────────────────────────────────┘
        │ invoke
        ▼
    原生能力（网络 · 存储 · 相机 · 定位 · 扩展模块）
```

**一次交互的完整路径**（它架构文档里的时序，逻辑层与渲染层不直接调用，全经 Bridge 路由）：

```
用户点击 → Render → publish → Bridge → Service 跑业务
  → invoke 调原生能力 → 返回
  → Service publish(setData) → Bridge → Render 更新 → 用户看到
```

---

## 2. 它在 LobOS 里的位置

```
┌── LobOS APK（lobos.os）──────────────────────────────┐
│                                                        │
│  ┌── 快应用框架：dimina（采用）──────────────────────┐  │
│  │  QuickAppHostActivity  ← 承载 dimina 的窗口      │  │
│  │  页面栈 / 渲染（WebView）/ 逻辑层（QuickJS）      │  │
│  └───────────────────┬─────────────────────────────┘  │
│                      │ 能力请求                       │
│  ┌───────────────────▼─────────────────────────────┐  │
│  │  能力闸  CapabilityBroker（已有，1583 行）        │  │
│  │  决定程序能用什么                                   │  │
│  └───────────────────┬─────────────────────────────┘  │
│                      │                                 │
│  ┌───────────────────▼─────────────────────────────┐  │
│  │  程序后端底座（已有，本阶段不动）                  │  │
│  │  独立进程 / 存储 / 端口 / 探活拉起                 │  │
│  │  安装验签落位 / 索引 / 升级回滚卸载                 │  │
│  │  工具链供给（node/git/curl，商店件 L1）            │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  ┌── 控制面板：第一个系统快应用（走 OTA）───────────┐   │
│  │  其中包含应用市场                                 │   │
│  └──────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────┘

存储：
  files/programs/<id>/<version>/   程序后端（多版本共存 + CURRENT 指针）
  files/quickapp/<id>/             程序前端资源包（dimina 格式）
  assets/jsapp/<id>/               dimina 约定：小程序包
```

---

## 3. 已知代价（接受，但必须记住）

### 3.1 渲染是 WebView，不是原生控件

dimina 的 `Render` 是 Vue runtime + DOM，跑在 Android WebView 里。
**程序界面的观感是 WebView 的观感，不是原生应用的观感。**

这是接受这条路的直接代价。后续若要换原生渲染，
换的是渲染层（`Render` 那一层），逻辑层/编译器/组件可以复用。

### 3.2 dimina 声明 18 条 Android 权限

核实自 `android/dimina/src/main/AndroidManifest.xml`，其中 **12 条敏感项**：

```
INTERNET, ACCESS_NETWORK_STATE, CHANGE_WIFI_MULTICAST_STATE,
ACCESS_WIFI_STATE, NEARBY_WIFI_DEVICES,
VIBRATE, WRITE_EXTERNAL_STORAGE(maxSdk 28),
── 敏感 ──
BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT,
ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION,
READ_CONTACTS, WRITE_CONTACTS, CAMERA, RECORD_AUDIO,
READ_BASIC_PHONE_STATE
```

**与我们的立场冲突**：我们已定「市场审批 + 一包共享」的信任模型，
所以砍掉这类敏感权限（对比：hapjs 的 features 模块声明 28 条，dimina 的 18 条已少得多）。

**处理方式**：在**我们自己的 manifest** 里用 `tools:node="remove"` 逐条剔除，
dimina 库本身不改（保持可跟随上游升级）。剔除清单要在 CI 里守，
防止上游新增权限时无声进来。

### 3.3 minSdk 26 / 仅 arm64-v8a

- 我们的 `minSdk 24` → 需要确认降到 24 会不会出问题，或把 minSdk 提到 26
- 仅 arm64-v8a：**我们本来就这样**（`abiFilters += listOf("arm64-v8a")`），无冲突

### 3.4 字节码缓存：不做

QuickJS 官方 `SECURITY.md` 明确：加载不可信字节码「等同执行不可信原生代码」，
且 `CVE-2025-46687/46688` 就在字节码读取器里。
**我们程序的前端代码来自开发者，不是我们写的** —— 所以每次从源码编译，不缓存字节码。

### 3.5 崩溃风险：前端跑在宿主进程

QuickJS 的内存 bug 会让 LobOS 整个崩（不是沙箱逃逸，是崩溃）。
**不可信逻辑放后端**（独立进程），**前端只做视图逻辑** —— 这正好对上「前后端分离」。

---

## 4. 接入步骤

| 步 | 做什么 | 验什么 |
|---|---|---|
| 1 | 引入依赖（JitPack），跑通 `Dimina.init()` | 编译过；APK 体积增量 |
| 2 | 剔除 18 条权限 + CI 门禁守住 | 合并后 manifest 里没有敏感权限 |
| 3 | `QuickAppHostActivity` 承载 dimina 窗口 | 能打开一个小程序，看到界面 |
| 4 | 小程序包落到 `assets/jsapp/<id>/` | 装好的程序能打开 |
| 5 | 接能力闸（`CapabilityBroker` ↔ dimina 原生模块） | 小程序能调到 `bridge:*` |
| 6 | 与后端联动（打开即起后端） | 前后端配合 |
| 7 | 桌面入口 | 装完点一下出图标 |
| 8 | 升级 / 卸载联动 | 换版本、回滚、清干净 |

**步 1 之前不改任何业务代码。**先证明它能在我们的构建里跑起来。

---

## 5. 待决

1. **`minSdk 24 → 26` 还是维持 24**（dimina 要求 26；降级可能出问题）
2. **JitPack 要不要镜像到内部仓库**（它是构建期外部依赖）
3. **上游升级节奏**：多久跟一次 dimina 版本
4. **控制面板用不用 dimina 写**（它是第一个系统快应用，技术上完全可行）

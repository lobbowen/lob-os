# 快应用框架落地方案

> 配套文档：`docs/QUICKAPP-FRAMEWORK.md`（共识与现状基线）。本文只讲**怎么落地**。
> 文中每条决策都标注了取证来源；未取证的一律标「未验证」。

---

## 0. 方案要点

三块，按依赖顺序：

| 块 | 内容 | 动代码量 |
|---|---|---|
| **一** | 嵌入 hapjs 引擎，让快应用能跑起来 | 中（Tier 0 为主，Tier 1 少量 fork） |
| **二** | 删三个死字段（`isolation` / `memoryMax` / `pidsMax`） | 小 |
| **三** | 前端纯 UI + 后端 A 方案（前端不碰系统能力） | 中（新增一个后端桥模块） |

**贯穿全案的两个原则**：

- **进程隔离 ≠ 安全边界**。进程隔离防的是崩溃、卡死、资源泄漏（稳定性）；
  安全边界靠**市场闭环 + 每次提交审批**（信任）。两者独立，不要混。
- **权限一套共享**。LobOS 拿到 Android 权限，里面所有程序共享一套。
  不做按 `programId` 分程序的授权。`programId` 只用于**识别、审计、限流**，不用于授权判断。

---

## 1. 两个已定的决策

### 决策一：接受 hapjs 自带的 V8（不自己编）

**取证**：
- 引擎带 `jsenv-1.2.8.aar`，静态链接 **V8 8.3.110.7**（arm64 `.so` ~15MB 原始）。
  符号表实测：full 变体 11025 个导出里 10788 个是 `v8::`。
- 有 `noV8Symbols` 变体，运行时 `dlopen("libv8-8.3.110.7…so")` + `dlsym`。
  但那是给「已经自带 V8 的 OEM」用的。

**为什么不自己编**：我们 node 的 V8 在 Linux 用户态（一个工具链件），不是 Android `.so`，
`dlopen` 不到。要自己编一份符号匹配的 `libv8-8.3.110.7*.so` 并长期维护——
工程量和风险都不划算，且偏离「符合框架规范」。

**代价**：APK 体积。当前约 9.3MB → 加 V8 后约 16MB（arm64 一份）。
这是「底座逻辑厚、APK 只是底座」的合理代价：16MB 的底座，
换来生态工具链（Taro / uni-app）能直接编译前端。

### 决策二：砍掉 `features` 模块声明的 Android 权限

**取证**（我亲自拉的 `features/src/main/AndroidManifest.xml`，28 条 `uses-permission`）：
`SEND_SMS` / `READ_SMS` / `RECEIVE_SMS` / `READ_CONTACTS` / `READ_PHONE_STATE` /
`CAMERA` / `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` / `WRITE_CALENDAR` /
`READ_CALENDAR` / `BLUETOOTH_ADMIN` / `RECORD_AUDIO` / `NFC` …

**为什么砍**：我们的信任模型是「市场审批过的才进来 + 一包共享」。
短信、通讯录、位置、日历、蓝牙**不是任何正常后端都要的基础设施**，
开放它们只扩大攻击面而不带来价值。砍掉不影响任何正常程序。

**与一包共享不冲突**：共享的是 LobOS 自己的一套权限，
不是把标准快应用那 50+ 项能力全放行。

**保留哪些**：`INTERNET` / `WAKE_LOCK` / `VIBRATE` / `FOREGROUND_SERVICE` /
`ACCESS_NETWORK_STATE` 这些是底座运行必需的，由我们自己的 manifest 声明，不依赖引擎。

---

## 2. 块一：嵌入 hapjs 引擎

### 2.1 依赖集（Tier 0 —— 可能不用 fork）

最小依赖（参照官方 `mockup/platform/android/app-impl/build.gradle`）：

```
:runtime        引擎核心（V8、桥、渲染、组件表）
:widgets        组件（~40 个 widget，含 <web> / <canvas>）
:platform       平台层（Launcher 进程、deeplink、分发、快捷方式）
```

**不依赖**：
```
:features                    ← 砍 50+ system.* / service.* + 28 权限
core/plugins/android/features/*  ← 第三方能力（alipay/wxpay/统计…）
```

依赖 `:runtime` 的注解生成器会**生成** `org.hapjs.bridge.MetaDataSetImpl`，
内含 `FEATURE_META_DATA_MAP` 等表。不依赖 `:features`，那些条目**根本不会生成**——
JS 侧看不到，Java 侧也实例化不了。这是「删能力」最干净的做法（不 fork）。

### 2.2 唯一必需的 feature：`system.decode`

**取证**：`core/framework/src/infras/dock/interface.js` → `initInterface(app)`
无条件调 `initTextDecoder` → `requireModule('system.decode')`，
而 `requireModule` 对未知名字**抛错**（`misc.js:219-221`）。
所以 `features: []` 的包会在创建时抛错。

**处理**：把 `system.decode` 加到引擎的 `assets/hap.json` 的 `features[]`
（引擎级，`HapConfig.isFeatureConfiged` 在 `isFeatureAvailable` 里**第一个**被检查）。
它是一个纯字符编解码器，无任何系统能力。

### 2.3 保留的「纯 UI 最小表面」

`ModuleExtension` **不受 manifest 管辖**，`onInvoke` 在 feature 检查失败后仍会走到 `mModuleBridge`。
所以这些**免费保留**（取证：`render/jsruntime/module/` 下 8 个文件）：

```
system.router         back / push / replace / clear / getLength / getPages / switchTab
system.page           finish / getMenubarRect / setMenubarData / setTabbarItem
system.app            getInfo / exit / createQuickAppQRCode
system.configuration   getLocale / getThemeMode / getFoldableState / getScreenOrientation
system.model           getComputedAttr / getComputedStyle / getBoundingRect / getComponent
```

外加 `system.resident` / `system.card` / `system.webview`（可选）。

**结论**：导航、页面栈、菜单栏、语言主题、计算样式——**全部照常，零成本**。
这就是 A 方案「前端纯 UI」够用的证明。

### 2.4 渲染层

**取证**：`Component<T extends View>` —— 泛型上界是 Android `View`，
每个组件都是 Android View 子类。布局用 Facebook Yoga（`YogaNode` / `YogaLayout`）。
`render/` + `component/` 共 231 文件 / 46941 行，**139 个 import `android.*`**。

- **不是 WebView**。WebView 只服务于 `<web>` 组件（`widgets/Web.java`），
  且 `card.json` 已有它的黑名单条目——证明可拆。
- CSS 解析 / 布局 / 动画全是 Java 实现（`render/css/`、`component/animation/`）。
- 唯一的 native 代码是 `<canvas>`（`widgets/src/main/cpp/canvas/`，约 5 文件）。

**这意味着**：我们的页面渲染不需要浏览器，APK 不用背 WebView 引擎。

### 2.5 必须 fork 的点（Tier 1）

| 文件 | 为什么 | 改动量 |
|---|---|---|
| `common/utils/ProcessUtils.java` | 引擎硬编码 `:Launcher0..4` 进程前缀；我们的 Activity 跑在默认进程，会被判成「非 app 进程」，JS 运行时**不预加载**。必须放宽 `isAppProcess` 或让 Activity 跑在 `:Launcher0` | 2-5 行 |
| `PlatformRuntime.java` | 在 `onAllProcessInit` 注册我们的后端桥模块 | ~5 行 |
| `ExtensionManager.java` | 可选：接 `FeatureInvokeListener` 做全局 invoke 否决（现有 no-op-unless-set 拦截点，`ExtensionManager.java:164-166, 370-376`） | 10-30 行 |
| 三个 manifest | 删 `:LauncherN` 多余进程条目、deeplink（`hapjs.org` / `qr.quickapp.cn`）、`GET_TASKS`、按需 unexport `CacheProvider`/`SettingsProvider` | manifest 删除 |

**Tier 2（大概率不需要）**：`MetadataGenerator.groovy`——只有要改「MetaDataSet 如何生成」时才动。
它是 groovy codegen 插件，所有 feature/widget 模块依赖它输出，最后再考虑。

### 2.6 引擎自己需要什么（宿主清单）

- 5 个 Launcher 进程约定（或我们改成一个）
- `LauncherActivity` + `DispatcherActivity`（deeplink / QR 安装入口）
- `HybridProvider`（DB authority）、`SettingsProvider`、`CacheProvider`
- `DistributionService` + `PlatformInstallService`（分发/安装——我们用自己的供给链，**可能整块可省**）
- `ShortcutService` + `ShortcutReceiver`（桌面图标）
- `ResidentService`（保活，我们已有自己的保活策略，**可能可省**）

---

## 3. 块二：删三个死字段

**取证**：`isolation` / `memoryMax` / `pidsMax` 在 `ManifestSchema` 定义并被校验，
但**全仓无任何代码执行它们**（`grep memoryMax|pidsMax|IS_NAMESPACES` 在 `container/` 下零命中）。

**为什么删**：
- 设内存上限挡不住的问题比挡住的更多（GC 抖动、死循环、同步 IO 卡死、缓慢泄漏）。
- 真正有效的隔离**已有**：
  - 后端独立进程（`ProcessSupervisor.spawn(owner = OWNER_PROGRAM)`）
  - 崩溃不带崩别的
  - **卡死检测 + 自动拉起**（`/status` 探活 → 连续失败 3 次 → break → 重启 + backoff，
    `SupervisorPolicy.HEALTH_FAIL_THRESHOLD = 3`）
- Android 自身在同一 uid 下已提供进程级隔离。

**动作**：删字段 + 校验 + 文档写明「不做进程内资源限制」及理由。
**不能留**——schema 里有、文档里可能宣称、实际一行不执行，正是「文档说有、其实没有」。

---

## 4. 块三：前端纯 UI + 后端 A 方案

### 4.1 A 方案的可行性已被取证

`@system.*` 本身就是按 app 白名单管控的，**两道闸**：
- Java：`ExtensionManager.java:179-206` — 不在 `features` 就不查
- JS：`misc.js:219-221` — `import` 时抛错

所以 `features: []` 的前端**本来就碰不到任何系统能力**。A 方案不需要新机制，
只需要**不依赖 `:features`** + 留 `system.decode`。

### 4.2 前端怎么调到后端——不发明新通道

**取证**：这个仓里**没有** `hap.io.MessageChannel`（我早前说「有」是错的，那是另一套更新的文档）。

**正解**：新增一个我们自己的模块，走完全一样的 `ExtensionManager` 路径：
```
@ModuleExtensionAnnotation(name = "system.lobos")
  → 动作调 CapabilityBroker 的 socket
前端：import lobos from '@system.lobos'    ← 用法与 @system.fetch 完全一致
```
`Extension.Mode.SYNC_CALLBACK` 能把「同步 JS 调用」桥到「异步 socket」。

**后端侧**：走 `CapabilityBroker`（已按 `programId` 授权：
`beginSession(programId)` / `declaredCapabilities(programId)` / `holder.granted`）。
**但决策是「一包共享」**——所以后端桥**不做按程序授权**，
`programId` 只用于审计和限流。

`ApiSpec` 现有两个能力组（实测）：
- `lobos:dev`（`GROUP_DEV`）—— 开发者 API，**可自动授予**
- `lobos:sys`（`GROUP_SYS`）—— 系统 API，**需经 `os.permissions` 授权后授予**

这套「dev 自动、sys 需授权」的分层正好可复用为**产品定位分层**
（你之前说的系统级 / 程序集 / 合作厂商），不必另造概念。

### 4.3 生命周期语义

按共识文档 3.2 与你的定义：
- **打开 → 同步起后端**（图标点开 → 前端起 → 后端进程拉起）
- **允许后台保活**（`system.resident` / 已有 `ResidencyPolicy`）
- **被清（进程被杀）→ 后端进程结束**
- **未被清 → 一直存活**（`lifecycle.restart` + `maxRestarts` + `backoff`）

**后端是独立进程**（`ProcessBuilder` + `ENV_CLEAR`），不是 Android Service——
这是「真程序」的基础，现有机制已支持。

---

## 5. 落地顺序

每步跑 15 道门禁 + CI 编译，验完再下一步。

| 步 | 做什么 | 验什么 |
|---|---|---|
| 1 | 删三个死字段 + 文档 | 门禁全绿；`ManifestSchema` 无孤字段 |
| 2 | 引入 hapjs（依赖 `:runtime`/`:widgets`/`:platform`，不依赖 `:features`），`hap.json` 加 `system.decode` | CI 编译过；合并 manifest 里**无** `SEND_SMS` 等 25 权限 |
| 3 | fork 四个点（`ProcessUtils` 等） | 引擎能在 LobOS 里起来（先空跑一个页面） |
| 4 | 新增 `system.lobos` 后端桥模块 | 前端能通过它调到后端（端到端） |
| 5 | 桌面图标（`ShortcutService` 接我们的 launcher） | 装完出图标、点开能进 |
| 6 | 前端纯 UI 端到端 | 一个真快应用装上、点开、跑通 |

**步 2 是最大风险**（首次引入外部引擎 + 体积跳变），建议单独一轮。

---

## 6. OTA：一条作业，两类消费者

### 6.1 模型

**OTA 不是一个"只装控制面板"的通道，它是一个功能模块——下载更新的能力。**
它下面挂两类消费者，走**同一个作业**、**两套策略**：

```
QuickAppInstaller.install(pkg, id, source)     ← 共用作业
  ├ 验签（program-verify.js）
  ├ 落位（ProgramDir，先挪后落，失败回退）
  ├ 切 CURRENT + FLOOR（回滚窗口）
  └ 旧版清理

OtaPolicy（控制面板）  源 = program-feed.json   触发 = 我推 tag，自动
MarketPolicy（市场）    源 = 市场索引           触发 = 用户在市场点更新
```

**不新增第二个安装器。**差别在「源 + 目标 id + 触发者」，不在作业本身。
一条线抽成多个是错的——那会变成没有规则的并存。

### 6.2 清单归属（关键）

**APK 侧唯一写死的是控制面板升级这条路。**

| 清单 | 放在哪 | 为什么 |
|---|---|---|
| **控制面板升级清单** | **APK 内**（`assets/program-feed.json`） | 控制面板是预装的系统应用，它的更新通道是底座的一部分 |
| **市场程序清单** | **市场侧**（不写死在 APK） | 市场要不断更新产品、用户提交、我们审批放行 —— 写进 APK 就没法更新了 |
| **工具链清单** | APK 内（`assets/supply/channel.json`） | 已实现并验证过；工具链是底座环境的一部分 |

> **为什么程序清单不能写死在 APK**：市场是活的（开发者提交 → 我们审 → 放行 → 上架）。
> APK 一旦发布，里面的清单就冻结了。所以清单必须在市场侧，APK 只存"去哪取"。

### 6.3 现状与要拆的地方

`ProgramOtaUpdater` 现在把三件事焊在一起：

1. **取件**（读 `program-feed.json`，自己拼 URL）
2. **决策**（比 revision、判降级、按 `installId` 选目标）
3. **执行**（验签 → 落位 → 切版本）

1 和 2 是「这一类消费者特有」的策略，**3 是通用的**。
现在 3 被埋在 1、2 里面，所以市场想用就得复制一遍 —— **这就是要拆的边界**：

| 部分 | 归属 |
|---|---|
| 取件 + 决策（源、版本、降级策略、灰度） | 各自的**策略**：`OtaPolicy` / `MarketPolicy` |
| 执行（验签 → 落位 → 切 CURRENT → 回滚窗口） | **共用作业**，一份 |

`QuickAppInstaller` 的雏形已在（`ota/ProgramInstaller.kt` 148 行，签名已收窄到
`Source.OTA` / `Source.NONE`），要补的是把它从 OTA 的策略层里**独立出来**。

**注意**：`ProgramInstaller` 现在只有 OTA 一个源，**没有"市场源"**。
加 `Source.MARKET` 是自然的一步 —— 不是新安装器，是同一个安装器多一个来源标记。


1. **`features: []` 是否真的在创建时抛错**——未跑引擎验证。
   低风险（保留 `system.decode` 即绕过），但值得步 2 时实测。
2. **`DistributionService` / `PlatformInstallService` 能否整块省掉**——
   我们有自己的供给链和安装链。未验证二者是否解耦。
3. **`ResidentService` 能否省掉**——我们已有 `ResidencyPolicy` 和探活。
4. **AAR 的 `abiFilters` 是否能正确裁掉 `jni/<abi>/`**——Gradle 对预编译 `.so`
   的处理微妙，未实测。可能只能保留 arm64 一份。
5. **OTA 是共用的下载更新作业，两类消费者走同一个作业、两套策略。**（已定，见 §6）

---

## 7. 待决 / 未验证
6. **`system.decode` 之外**：`Display` 是普通 Java 类（不需 module），
   但 `Display.java` 是否依赖某个 feature 需步 2 时确认。

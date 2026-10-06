# 跨快应用导航：官方能力边界与我们的做法

依据：didi/dimina 官方仓库 `main` 分支源码（`android/dimina/src/main/kotlin/com/didi/dimina/`）。

## 一、`navigateToMiniProgram` 的参数名

`wx.navigateToMiniProgram({ appId, path, extraData, envVersion, noRelaunchIfPathUnchanged, shortLink })`。

`appId` 与 `path` 是标准字段名，与微信小程序规范一致。

证据：
- `fe/packages/container-sdk/src/core/appManager.ts:235-308`（Web 容器）
- `android/.../api/route/RouteApi.kt:190-220`（Android 宿主）
- `android/.../api/route/MiniProgramRouteContract.kt`（参数校验契约）

## 二、官方硬约束：目标必须打包在 APK assets 里

Android 端解析目标 appId 走 `android/.../core/BundledMiniProgramResolver.kt`：

```kotlin
val config = context.assets.open("jsapp/$appId/config.json")
```

该类注释原文：

> Resolves only packages shipped under assets/jsapp/<appId>/config.json.

`assets` 是编译期常量，APK 安装后固化，**运行时不可写**。

我们的快应用全部通过 OTA 下载到 `${filesDir}/jsapp/<appId>/`，因此直接调
`wx.navigateToMiniProgram` 必然得到：

```
navigateToMiniProgram:fail target mini program is not bundled
```

官方 `docs/Multi-Mini-Program.md` 描述的 A→B→C 导航模型确实支持多 appId 并存，
但入口是**宿主**（`startMiniProgram` + `Activity`），不是快应用自己。

## 三、但底层没有这个限制

`DiminaActivity.navigateToMiniProgram(target)` 内部只做两件事：

```kotlin
fun navigateToMiniProgram(target: MiniProgram) {
    suspendForMiniProgramNavigation()
    miniApp.openApp(this, target)
}
```

`miniApp.openApp` 与宿主入口 `Dimina.startMiniProgram(context, miniProgram)`
**是同一个调用**（`MiniApp.kt:140-159`）。`openApp` 完全不检查 assets，
对 filesDir 里的包没有偏见。

**结论：resolver 只是一层入口校验，不是运行时要求。宿主侧能开 filesDir 里的包。**

## 四、官方语义与 `startMiniProgram` 的差距

`navigateToMiniProgram` 额外做了三件事，都对 `filesDir` 包不生效：

| 官方语义 | 实现 | `startMiniProgram` 是否做 |
|---|---|---|
| 挂起来源（触发 Hide，运行时保留） | `suspendForMiniProgramNavigation()` | 否 |
| 记为 opener，返回时回传 | `queueOpenerReturn(extraData)` | 否 |
| 来源隐藏 ≠ 销毁，返回时 scene 1038 | `visibilityTracker` | 靠 Activity 栈，不保证 |

这两个方法在 `DiminaActivity` 里都是 **private**，`openMiniProgram` 是
**internal**。宿主拿不到 opener 语义，只能靠反射（依赖私有 API，升级即碎）
或在桥接层自己补。

**所以：官方 A→B→C 语义（含后台保留、返回恢复原页面栈）在 filesDir 包上拿不到，
这是 dimina 的能力边界，不是我们的实现问题。**

## 五、我们的做法

快应用侧走我们自己的桥接，不调 `wx.navigateToMiniProgram`：

```js
openSecond() {
  this.bridge('openApp', { id: 'com.lobos.second' })
}
```

链路：`wx.extBridge` → `LobosBridge.openApp` → `QuickAppHost.open` →
`Dimina.startMiniProgram`。

`openApp` 能力是既有的（`LobosBridge.kt` 的 `openApp` 事件），走
`Foreground.current()` 拿前台 Activity，持有 `INSTALLER_ID` 身份，
经 `invokeLocal` 同进程调用，不依赖 socket。

**语义差异需对开发者说明**：本能力是「宿主级应用切换」，不是微信的
「小程序内导航」。返回依赖 Android 任务栈，不是 dimina 的 opener 关系。

## 六、不可行的替代方案

| 方案 | 不可行的原因 |
|---|---|
| 改写 assets 目录 | `BundledMiniProgramResolver` 是 `internal object`，宿主无法覆写；`context.assets` 无 hook 点 |
| 改 `navigateToMiniProgram` 内部 | `DiminaActivity` 的相关方法是 `private` / `internal` |
| 每加一个快应用重打 APK | 与 OTA 动态分发模型直接冲突 |

## 七、已验证 / 待验证

**已验证**：两个快应用可共存（`com.lobos.fixture` 端口 41000、
`com.lobos.second` 端口 41001），`openApp` 能力通。

**待验证**：A 打开 B 后，A 的 `onHide` / `onShow` 实际时序、页面栈是否保持、
`globalData` 是否留存。这些需要在第二个快应用真实打开后取 logcat 确认。

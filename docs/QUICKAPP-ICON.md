# 快应用桌面图标

> 配套：`QUICKAPP-INSTALL.md`（安装与包结构）、`QUICKAPP-PLAN.md`（阶段）。
> 本文把这件事想透：能不能做、怎么做、约束在哪。

---

## 1. 现状：标准不管这件事

**dimina 的 33 个 API 里没有快捷方式能力**（`api/` 目录全查：没有 `shortcut` / `addToDesktop` 之类），
官方示例里一处都没用到。

**所以桌面图标得我们自己做。** 这不是"补一个缺失功能"，而是"标准之外我们自己加的一层"。

---

## 2. Android 的机制与硬约束

本机实测环境：**OPPO PLP120 · Android 17（API 37）**。

机制是 `ShortcutManager.requestPinShortcut()`（Android 8.0 / API 26 起）。
**四个约束，每一个都是硬的**：

| 约束 | 后果 |
|---|---|
| **用户必须确认** | launcher 弹窗。返回 `true` 只代表「launcher 支持该特性」，**不代表图标加上** |
| **必须有前台窗口** | 在无前台 Activity/Service 时调用抛 `IllegalStateException`。**不能在 `onCreate` 或后台做** |
| **进不了 app 抽屉** | 只能出现在桌面。抽屉只由 `getActivityList()`（已安装包的 Activity）喂养，快捷方式没有任何路径进抽屉 |
| **`INSTALL_SHORTCUT` 广播已死** | Android O 起被系统拦下（`BroadcastController` 直接 cancel），不要基于它设计 |

**第 1、2 条合起来决定交互形态**：图标**不可能「装完自动有」**，
必须是「用户在一个界面上点一下 → 系统弹窗 → 确认」。

---

## 3. 入口：两处都提供

### 甲：快应用自己的界面里

程序内提供「添加到桌面」入口。宿主监听这个调用，转成 `requestPinShortcut`。

- **符合「图标属于程序」**：是程序自己要求加的
- 每次想让图标出现，得先进那个程序

### 乙：控制面板统一管理

控制面板列出已装快应用，每项带一个「添加图标」。

- 一次性管理，不用逐个进程序
- 有些程序界面不好找，从控制面板加更直接

**两处都要**：乙是甲的补充，不是替代。

---

## 4. 图标图片从哪来

`ShortcutInfo` 需要一张位图。来源是**程序包里带的那张**——即清单里的 `ui.icon`
（件内相对路径）。宿主读它 → 按 launcher 需要的尺寸缩放 → 塞进 `ShortcutInfo`。

**这一步完全在我们手里，也是「图标是程序自己的」的落地点。**
底座不生产图标，只负责把它放到桌面上。

---

## 5. 状态能显示什么（重要，且要说实话）

`requestPinShortcut` **返回 true 不代表图标真加上了**。
我们能查的只有两件事：

| 能做 | 说明 |
|---|---|
| `isRequestPinShortcutSupported()` | launcher **是否支持**这个特性（可查） |
| `getPinnedShortcuts()` | **我们已经请求过、且 launcher 接受了的**快捷方式（可查，取决于 launcher 是否回填） |

**所以状态只有三档，全部是事实陈述，不含猜测**：

| 显示 | 依据 |
|---|---|
| `未添加` | `getPinnedShortcuts()` 里没有它 |
| `已添加` | `getPinnedShortcuts()` 里有它 |
| `无法添加` | `isRequestPinShortcutSupported()` 为 false（该 launcher 不支持） |

**不允许显示「添加中」「已请求」这类中间态**——用户点了之后到底成没成，
只有 launcher 知道。它回了「已添加」就说已添加，没回就说没回，不粉饰。

---

## 6. 卸载摘不掉：一条查证过的硬事实

我们原本计划「程序被卸 → 摘掉它的快捷方式」。**这条做不到**，理由三样，都已查证：

| 事实 | 出处 |
|---|---|
| `ShortcutManager` 全部 28 个公开方法里**没有任何 unpin / 取消固定的 API** | `ShortcutManager.java`（AOSP）逐个方法枚举 |
| `removeDynamicShortcuts` 返回 `void`，且**只删 dynamic 集合**；`requestPinShortcut` 造出来的是 **pinned** | `ShortcutManager.java:254` / `:598` |
| hapjs（真实上线的快应用框架）`uninstallShortcutAboveOreo` **直接 `return false`** | `DefaultSysOpProviderImpl.java:232` |

`com.android.launcher.action.UNINSTALL_SHORTCUT` 广播 hapjs 只在 pre-O 分支用，
我们 `minSdk=26` 走不到那条路（且它同样不是 O+ 的正规能力）。

**所以卸载时我们做的是 `disableShortcuts`**：入口在桌面上留着但点不动，
程序本体已删。这是平台给的上限，规范里要如实告知用户。

---

## 7. 线程约束（会 ANR，踩不得）

`requestPinShortcut` 与 `getPinnedShortcuts` 在 AOSP 里都标了 **`@WorkerThread`**，
内部 `getFutureOrThrow(AndroidFuture)` **同步等 Binder 返回**。

**在主线程调用会 ANR。** 所以 `DesktopIcons` 把所有平台调用都放在
`lobos-desktop-icon` 线程池上，回调再 `Handler(主线程)` 投递回去。

同一份 AOSP 源码也确认了 `requestPinShortcut` 另一条硬约束：
**「The caller doesn't have a foreground activity or a foreground service,
or the device is locked」→ 抛 `IllegalStateException`**。
这也再次说明「装完自动有图标」不可能（见第 2 节）。

---

## 8. 要落到哪些地方

```
① 清单          ui.name 必填、ui.icon 若填须为件内相对路径
② 能力面        甲：extBridge 三个事件
                  · desktopIcon.add      程序界面里「添加到桌面」
                  · desktopIcon.remove   程序界面里「从桌面移除」
                  · desktopIcon.state    问真实状态
                乙：控制面板直接调同一套
③ 宿主实现      读 ui.icon → requestPinShortcut → 回真实状态
④ 状态查询      isRequestPinShortcutSupported / getPinnedShortcuts
⑤ 卸载联动      程序被卸 → disableShortcuts（摘不掉，见第 6 节）
⑥ 门禁          tools/check-desktop-icon.js
```

**② 的关键**：「添加到桌面」对程序来说是**宿主能力**（跟它要文件、要通知一样），
所以走 dimina 的 `registerExtModule("lobos", …)` 即可。

---

## 9. 明确不做 / 做不到

| | 为什么 |
|---|---|
| 装完自动有图标 | 系统要求用户确认 + 要前台窗口，**平台性质** |
| 进 app 抽屉 | 抽屉只收已安装包的 Activity，快捷方式无路径进入 |
| 静默检测「用户到底点了确认没有」 | launcher 不回这个事件 |
| 卸载时真正摘掉图标 | 系统没给这个 API（第 6 节） |
| 自己造 launcher 接管桌面 | 那不是「在安卓里存在」，是换掉安卓 |

---

## 10. 已定

1. **图标尺寸**：交给 `ShortcutInfo.Builder` + `Icon.createWithBitmap`，
   让 launcher 自己按需缩放；不自造多密度。
2. **`ui.name` 必填、`ui.icon` 可选**：`ui.name` 是桌面入口唯一显示的字，缺了不该进桌；
   `ui.icon` 缺了仍可加（用 launcher 默认图标），只是不像「程序自己的图标」。

---

## 11. 实现落点

| 文件 | 职责 |
|---|---|
| `quickapp/DesktopIcons.kt` | 状态三档、线程池、读图标、request / disable |
| `quickapp/LobosBridge.kt` | 甲入口三个事件，读 `ui.name`/`ui.icon` |
| `quickapp/QuickAppLaunchActivity.kt` | 桌面图标落点（`EXTRA_ID`）→ `QuickAppHost.open` |
| `os/PackageInstaller.kt` | 卸载时 `DesktopIcons.withdrawNow` |
| `os/ManifestSchema.kt` | `UI_NAME` 必填、`UI_ICON` 限相对路径 |

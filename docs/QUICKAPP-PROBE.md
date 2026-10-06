# 能力探测：快应用能否打开其他快应用 / 能否给别人加桌面图标

> 配套：`QUICKAPP-PANEL.md`（控制面板要做成系统快应用）。
> 本文是实测的判定标准。先定标准再看结果，否则「看起来成功」不算数。

---

## 1. 要回答的问题

| # | 问题 | 为什么关键 |
|---|---|---|
| Q1 | 快应用能否**打开其他快应用** | 控制面板的核心动作。做不到，面板就只是"能看的列表" |
| Q2 | 快应用能否**给其他程序加桌面图标** | 同上；且这条同时验了 A 阶段的桌面入口 |

---

## 2. 已查证的事实（不是推测）

| 事实 | 依据 |
|---|---|
| `startMiniProgram(context: Activity, miniProgram)` 且 `@MainThread` | `DiminaAPI.kt:159` |
| 它内部调 `MiniApp.openApp(context, miniProgram)` | 同上 |
| dimina JS SDK **没有跨 appId 跳转** | 从 APK `assets/jssdk/main.zip` → `service.js`（178KB）全文查 |
| extBridge 真实签名是 `wx.extBridge({event, module, data, success, fail, complete})` | 同上，`globalThis` 注入处 |
| 宿主 `QuickAppHost.open` 已能打开快应用 | 面板「打开」按钮真机验证过，界面出来了 |

**推论**：前端 JS 拿不到 `Activity`，所以 Q1 **必须经宿主中转**。
这不是 workaround，是唯一路径。

---

## 3. 为这次实测改了什么

| 改动 | 为什么 |
|---|---|
| 新增 `quickapp/Foreground.kt` | **此前全仓没有"当前前台 Activity"这个设施**。`openApp` 必须有 Activity 才能调 |
| `LobosBridge` 加 `openApp` 事件 | 把宿主已有的 `QuickAppHost.open` 暴露成能力 |
| `QuickAppHost.open` 返回 `String` | **原来失败只记日志然后 return**，调用方分不出成功失败 —— 实测结论会不可信 |
| 测试包加 `pages/probe` 探测页 | 四个按钮分别打四个能力事件 |
| socket 监听失败降噪 | 修 `OK` 与 `FAIL` 并存的矛盾记录 |

---

## 4. 判定标准

### Q1 快应用打开其他快应用

| 结果 | 判定 |
|---|---|
| `openApp` 返回 `ok:true`，`activity` 是 dimina 容器类名，且**界面真的切换了** | ✅ 通 |
| `ok:true` 但界面没变 | ❌ 不通（调用成功但没起界面，等于不能用） |
| `ok:false` + `no-foreground-activity` | ❌ 跟踪机制没生效 |
| `ok:false` + 其他原因 | ❌ 记下具体原因 |

**关键**：不能只看返回值。`ok:true` 只说明 `startMiniProgram` 没抛异常，
**必须人眼确认界面切换了**。

### Q2 加桌面图标

| 结果 | 判定 |
|---|---|
| 系统弹确认框 → 确认后桌面出现图标 | ✅ 通 |
| 弹框出现但 `desktopIcon.state` 仍报 `not_added` | ⚠️ 半通（弹了但没生效，要查 launcher 回填） |
| 直接失败或无弹框 | ❌ 不通 |

---

## 5. 已知的三个不确定点（实测会给答案）

1. **手写页面能否被 dimina 接受** —— 编译产物用 `Module` + render 函数，
   我手写的是 `Page({...})`。`globalThis.Page` 在 JSSDK 里存在，
   但 native 侧的资源清单认不认，没把握。
   **兜底**：若页面不被认，改用宿主 `PanelActivity` 加一个按钮触发同一个
   `openApp` 能力事件 —— 结论等价（验的是宿主能力，不是页面写法）。

2. **`DiminaActivity` 作 Activity 是否合适** —— `open` 传的是当前前台那个。
   从快应用调时前台是 dimina 容器，传它能不能叠出页面栈，未知。

3. **弹窗交互** —— `requestPinShortcut` 的系统弹窗会盖在快应用界面上。
   交互上可接受，但要明确它必须是面板里的显式按钮。

---

## 6. 结论怎么用

| Q1 | Q2 | 意味着 |
|---|---|---|
| ✅ | ✅ | 控制面板可以做成快应用，方向成立 |
| ✅ | ❌ | 面板可做成快应用，但桌面图标那条要走别的路（宿主侧代劳） |
| ❌ | ✅ | 面板不能纯快应用化，「打开程序」得回宿主侧（SetupActivity 或桌面图标） |
| ❌ | ❌ | 面板必须留在宿主里，快应用只做只读展示 |

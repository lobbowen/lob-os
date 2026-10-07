# 架构审计报告：问题、后果与整改

审计对象：`container/app/src/main/java` 113 个 Kotlin 文件 / 16450 行 / 12 个包。
配套：`docs/GATE-CLASSIFICATION.md`（门禁分类）、`docs/INSTALL-CHANNEL.md`（安装通道规范）。

本文每条问题都给出**后果 → 风险 → 整改 → 防复发**，不能落地的结论不写。

---

## 怎么读这份报告

**问题分两类，不要混着看：**

| 段 | 含义 | 状态 |
|---|---|---|
| **第一段：老账** | 上一轮审计已经列出、**至今没做**的 | 你已确认过方向，只是没实施 |
| **第二段：新发现** | 本轮才查出的 | 需要你判断 |

第一段 9 条里，**有 2 条是我上一轮该做而没做的**（见下），
其余是当时定下方向但排期靠后的。

死代码已清零（34 个声明 / -192 行），废弃代码已合流安装通道，
这两项本轮已完成，不在问题清单内。

---

## 汇总

| # | 问题 | 来源 | 后果 | 风险 | 成本 |
|---|---|---|---|---|---|
| A1 | `ProgramVerifier` 用 node 校验包 | 上一轮 | 内置应用升级在无 node 设备上走不通 | 高 | 中 |
| A2 | 通知点击跳引导页，不是控制面板 | **新发现** | 配好通道后点通知仍被扔回"你还没配对" | 高 | ~~1 处~~ **本轮已改** |
| A3 | `ProgramDir` 放错包（7 包依赖） | **新发现** | `quickapp` 反过来依赖 `ota`，方向倒置 | 中 | 小 |
| A4 | `os` 反向依赖 `lifecycle`（2 处） | 上一轮 | 基础层依赖上层，无法独立测试 | 低 | 小 |
| A5 | `capability`/`permissions` 依赖 `bridge` | 上一轮 | 接口层与能力层耦合 | 中 | 中 |
| A6 | `KillAudit` 采集端未接线 | 上一轮 | 死因归因永远读不到 | 中 | ~~1 处~~ **本轮已改** |
| A7 | 应用程序不进商店目录 | 上一轮 | 安装要人工切通道 | 中 | 发布侧 |
| A8 | `os` 30 文件平铺 3690 行 | 上一轮 | 找东西靠 grep，波及面难判断 | 低 | 中 |
| A9 | `bridge` 混三类职责 | 上一轮 | 2876 行里三类东西同包 | 低 | 中 |

**本轮已实施 2 条**（合计约 20 行），剩下 7 条中 A3 成本最低。

### 我上一轮欠的两笔

| 事项 | 说明 |
|---|---|
| **误删 `KillAudit.auditOnce`** | 它是待接线的功能入口，不是死码。本轮已恢复（见 A6） |
| **删 `check-layer-direction` 而非用它** | 我写了分层门禁又删掉，理由是"分层方向未定"。但 A3/A4/A5 已经是确定要修的错位，修完就该接回 CI 拦新增 |

---

## 一、通知点击跳引导页（不是控制面板）

### 问题

`lifecycle/OsHostService.kt:283` 的通知点击 Intent 指向 `SetupActivity`：

```kotlin
Intent(this, SetupActivity::class.java)
```

`SetupActivity` 是**配对未完成时的引导页**（"Lob OS 工作台"，带 F1 配对按钮）。
而 `SetupActivity` 是 Manifest 里唯一的 `LAUNCHER`，`PanelActivity`（控制面板）
只能从引导页点按钮进去。

### 后果

用户配好通道、装好程序、日常从桌面图标进控制面板之后，
**点系统通知仍会被扔回引导页** —— 一个"你还没配对"的界面。

通道状态（`AdbChannelState`）与引导页完成度（`OnboardingFlow.readyToEnter`）
是两套判断，前者已就绪、后者仍可能显示"待办"，用户会以为系统坏了。

### 风险

- **主入口错位**：通知是系统级入口，比桌面图标更容易被点到
- 二阶段做快应用控制面板后，引导页会退役，这条路径要么删要么改成指向面板
- 现在能"跑"，但每次点通知都是一次错误的交互

### 整改

`OsHostService` 需要一个"该去哪"的判断，而不是硬编码：

```
CapabilityCatalog.evaluate(this) → verdicts
OnboardingFlow.readyToEnter(verdicts)  → true  ? PanelActivity : SetupActivity
```

`readyToEnter` 已在 `SetupActivity.kt:247` 使用，
`verdicts` 由 `CapabilityCatalog.evaluate(e)` 得出（`SetupActivity.kt:225`），
两者都是纯函数/静态方法，Service 侧可直接调用，**不需要另写一套判断**。

改动范围：`OsHostService.kt:283` 一处，加一个判断分支。

### 防复发

`check-layer-direction.js` 删了（见门禁分类），但**这类"UI 入口指向"的问题
不适合做成门禁** —— 合适的入口是产品形态决定的，二阶段还会变。
改为在 `docs/INSTALL-CHANNEL.md` 记一条："系统级入口（通知/快捷设置）
必须走 readyToEnter 判断，不能硬编码 SetupActivity"。

---

## 二、`ProgramVerifier` 用 node 校验包（鸡生蛋）

### 问题

`ota/ProgramVerifier.kt:36` 起，校验流程是：

```kotlin
val script = NodeProvisioner.ensureKernelVerifyScript(context)   // assets/node/program-verify.js
val args = mutableListOf(nodeBin.absolutePath, script.absolutePath, "--zip", …, "--pubkey", …)
ProcessSupervisor.spawn(command = args, …)                      // 用 node 跑那个 JS 脚本
```

`node` 本身是商店目录里的一类系统组件（`kind: runtime`），要装程序时按需拉。

### 后果

**内置应用的安装校验需要 node，而 node 自己安装也需要校验。**

真机已报过 `runtime-missing`（`docs/ARCHITECTURE-AUDIT.md` 旧版记录）。
设备上 node 缺失或 `$ORIGIN` 依赖没到位时，**所有安装全部失败**，
且失败原因报的是"运行时未就位"，与真实原因（依赖没落对位置）无关。

实际卡点在 `ota/ProgramInstaller.kt`：

```kotlin
nodeBin: File? = lobos.os.NodeRuntime.path(context),        // :37
if (nodeBin == null) {
    return InstallResult(false, …, "runtime-missing", …)    // :45-46
}
val verify = ProgramVerifier.verify(context, zip, manifest, nodeBin, manifestFile)  // :62
```

**安装器在落位之前就要求 node 在位**（因为要校验），而 node 自己也要走安装器。

### 风险

- 内置应用升级这条路在没有 node 的设备上完全走不通
- 出问题时错误信息误导排查方向（我这次就绕了很久，见 `docs/NODE-NATIVE-DEPS.md`）

### 整改

把签名校验移到 Kotlin 侧，不依赖任何运行时：

- `ProgramVerifier` 读 zip 内的 `program-manifest.json` → `Ed25519.verify(pubkey, canonical(manifest))`
- 逐文件 `sha256` 与清单声明比对（Java 的 `MessageDigest` 够用）
- `program-verify.js` 保留作交叉验证工具，但**不在安装路径上**

判据：改完之后 `git grep program-verify.js` 在 `container/app/src/main/java` 下应为零命中。

### 防复发

`check-ota-sequence-scope` 已在管序列号。新增一条判据：
安装路径上不得出现对 `NodeRuntime.path` 的依赖（它在 `ProgramOtaUpdater` 里有正当用法，
但 `ProgramInstaller` / `ProgramInstallPipeline` 里不该有）。

---

## 三、`ProgramDir` 放错包

### 问题

`ProgramDir` 管的是 `files/programs/<id>/` 目录（版本目录、CURRENT/FLOOR/PENDING、
quickapp 落位），属 `os` 域，却住在 `lobos/ota/` 包。

实测被 **7 个包**依赖：`os`(5 处) / `quickapp`(3 处) / `bridge`(1) /
`runtime`(1) / `lifecycle`(1) / `root`(ProvisioningProbe，1) / `ota`(自身 5)。

其中 `quickapp` 的 `QuickAppBinder` 直接调 `ProgramDir.quickAppDir()`——
**一个 quickapp 包的东西反过来依赖 ota 包的类**，方向倒置最明显的一处。

### 后果

`os`（基础层）反向依赖 `ota`（上层），方向倒置。后果是：
- `os` 包无法独立测试（要拉起整个 ota 才有意义）
- `ota` 一旦被裁剪或重构，`os` 跟着受影响

### 风险

中等 —— 现在能跑，但这是"基础层依赖上层"的典型样本，会持续诱发新违规。

### 整改

`lobos/ota/ProgramDir.kt` → `lobos/os/ProgramDir.kt`，改包声明与全部 import。
纯机械改动，无逻辑变化。

注意 `ota/ProgramOtaStateStore.kt` 与 `ProgramDir` 同域，一并考虑是否也归 `os`。

### 防复发

分层判据留在审计报告里，等整改完再考虑做成门禁（见门禁分类的原则 3）。

---

## 四、`KillAudit` 采集端未接线

### 问题

`KillAudit` 的职责是"系统退出史归因"，分三段：

| 段 | 内容 | 状态 |
|---|---|---|
| 采集 | `auditOnce(ctx)` —— 调 `ActivityManager.getHistoricalProcessExitReasons` 读系统退出史、落盘、去重（`MAX_RECORDS` 32 条 + 游标 `readCursor`/`writeCursor`） | **未接线**（原本 33 行，被本轮当死码删除，见下） |
| 转换 | `attribute(exits, unreadable, sinceMs, mainProcess)` —— 纯函数，把退出记录转成一句人话 | 在用 |
| 展示 | `attribution(sinceMs)` —— 读盘并归因 | 在用，被 `ResidencyAudit.kt:60` 调用 |

### 后果

读端永远读不到数据。真机上引导页显示的那句
**「死因未取证（还没读系统退出史）」**，就是这个缺口的直接表现 ——
系统在 App 被系统杀掉后，说不出自己为什么死。

### 风险

- 用户看到的是"功能没做完"，不是"被杀"
- 排查常驻失败（我们的核心卖点）时没有第一手证据，只能靠日志时间戳猜

### 需要先纠正一个我自己的错误

本轮清理时，我把 `auditOnce` 当成零引用死码**删掉了**（33 行）。

判断依据是"全仓无调用"——但它本来就是**待接线的功能入口**，
不是遗留代码。删它等于把"没接线"变成"不存在"，
连接线的地方都没了。**这是误删，本轮已恢复。**

### 整改

1. ~~恢复 `auditOnce`~~ —— **本轮已完成**
2. ~~在宿主启动时机调它~~ —— **本轮已完成**：`OsHostService.onStartCommand` 里
   `KillAudit.auditOnce(this)`（`onStartCommand` 早于任何 UI，采集在
   `interruption()` 被读之前完成）
3. 引导页那句"未取证"会变成具体归因 —— **待真机验证**

判据：`git grep "KillAudit.auditOnce"` 应有非零命中 —— **已达成**。

### 防复发

不设门禁 —— 这是"有没有接线"的功能问题。
但**清理流程要改**：扫描出的零引用项，在删之前必须先看 `git log` 里
该函数的历史，判断它是"遗留代码"还是"待接线的功能"。
后者归待办，不能删。本次就该先看历史再决定。

### 防复发

不设门禁 —— 这是"有没有接线"的功能问题，不是行为约定。

---

## 五、应用程序不进商店目录

### 问题

商店目录（`CatalogClient` 读的 `component-*.json`）里只有 `tools[]`
（node/curl/git/jq/npm/pnpm/sqlite3），**没有 `packages[]`**。

所以应用程序（我们的快应用）走的是 `program-feed.json` 那条单通道 OTA。

### 后果

真机已验证：切到 `second` 通道时升不了 fixture，切回 `canary` 装不了 second。
两个程序无法靠同一条通道并存安装，只能来回切。

根因是 `program-feed.json` 写死一个 URL，而 `SupervisorPool` 按程序逐个建
`InstanceHost`、每个都去拉那一个 URL。

### 风险

- 安装应用程序要人工切通道，不是产品能力
- 序列号跨通道互相压制（真机报过 `sequence=31 不高于已提交 32`）

### 整改

**发布侧**：商店清单发 `packages[]`，每项带 `id`/`version`/`url`/`sha256`/`kind`。
`CatalogClient.entries()` 已经会读 `packages`（`CatalogClient.kt:36`），代码不用改。

**代码侧**：`ProgramOtaUpdater` 改用 `entryFor(id)` 取目录项，不再自己拼 URL。
`lastSequence` 那套设备级状态随之删掉（版本比较不需要序列号，
那是发布方对单程序一次发布的概念）。

注意：内置件仍走 `program-feed.json`（它随 APK 交付，要能离线校验），
两条路保留，安装都汇到 `ProgramInstallPipeline`（已做，见 `docs/INSTALL-CHANNEL.md`）。

### 防复发

`check-install-single-path` 已在管"两条路一个安装点"。

---

## 六、`capability` / `permissions` 依赖 `bridge`

### 问题

| 依赖 | 位置 |
|---|---|
| `capability/AdbChannelComponent` → `bridge/AdbClientRunner` | 能力定义依赖接口层 |
| `capability/CapabilityAcquisitionRunner` → `bridge/AdbClientRunner` | 同上 |
| `capability/CapabilityCriteria` → `bridge/OsNotificationListenerService` | 同上 |
| `permissions/PermissionCenter` → `bridge/NotificationStore` | 权限中心依赖接口层 |
| `permissions/PermissionCenter` → `lifecycle/OsAccessibilityService` | 权限中心依赖保活实现 |

### 后果

- `bridge`（对外 JSON-RPC 层，2876 行）的任何改动都会波及 `capability` / `permissions`
- `capability` 无法脱离 `bridge` 独立测试
- 方向倒置：应该是 `bridge` 依赖 `capability`（接口层用能力层），不是反过来

### 风险

中等 —— 现在能跑，但这是分层里最容易被无意加剧的一类
（新写代码时看到现成例子就会照抄错方向）。

### 整改

把被依赖的实现下沉：

| 现在 | 应该 |
|---|---|
| `bridge/AdbClientRunner` | → `capability/AdbClientRunner`（它是能力获取的实现） |
| `bridge/OsNotificationListenerService` | → `capability/`（无障碍/通知能力的一部分） |
| `bridge/NotificationStore` | → `os/` 或 `permissions/`（通知存储是数据，不是接口） |

`bridge` 保留的只是 `CapabilityBroker`（对外 JSON-RPC 目录）与 mDNS 通道。

### 防复发

整改完再把 `check-layer-direction.js` 接进 CI，**豁免表逐条写理由**，
让豁免数当整改进度表。

---

## 七、`os` 反向依赖 `lifecycle`

### 问题

| 依赖 | 具体 |
|---|---|
| `os/DozeBackstop` → `lifecycle/DozeBackstopReceiver` | 基础层拿了 Android 组件 |
| `os/OsState` → `lifecycle/ResidencyPolicy` | 基础层用了上层的常量 |

### 后果

- `DozeBackstopReceiver` 是 BroadcastReceiver（Android 组件），`os` 不该知道它存在
- `ResidencyPolicy` 的常量（`WAKE_BACKSTOP_MS` / `REASON_NO_PROGRAM`）
  是领域模型，放在生命周期管理包里

### 风险

低 —— 两处都不复杂，改动小。

### 整改

1. `DozeBackstopReceiver` 已在 `lifecycle`（合理），改的是 `DozeBackstop` 的注册方式：
   `os` 只暴露 `schedule(ctx)` / `cancel()`，由 `lifecycle` 注册 receiver
2. `ResidencyPolicy` 的常量拆到 `os`（或新建 `domain` 包），`lifecycle` 引用它

### 防复发

同第六条。

---

## 八 / 九 / 十、物理分层三处

### 八、`os` 包 30 文件平铺（3690 行，占全仓 22%）

**后果**：找一个东西要全仓 grep；改动波及面难判断。

**整改**：按子域拆目录：

```
os/
├── registry/   ProgramIndex, ProgramRegistry, ProgramManager, ProgramMigration
├── state/      OsState, ProgramStatus, ResidencyStatus, StateFiles
├── runtime/    PortBroker, TaskRegistry, SessionRegistry, ProcessLedger
└── …
```

纯目录调整 + package 声明，无逻辑变化。

### 九、`bridge` 混三类职责

**后果**：2876 行里 JSON-RPC 目录（`CapabilityBroker`）、
Android 组件（`MdnsWatcher`/`OsNotificationListenerService`）、
通道实现（`AdbClientRunner`）同包，生命周期与依赖完全不同。

**整改**：随第六条一起做 —— 组件与实现下沉到 `capability`，
`bridge` 只留 `CapabilityBroker` + mDNS。

### 十、根包调试设施是全局耦合点

**问题**：`RuntimeDiagnostics` 被 9 个包 import（`MainActivity`/`OsApplication`/
`ProvisioningProbe`/`bridge`/`lifecycle`/`native`/`os`/`ota`/`runtime`）。

**后果**：几乎每层都依赖它，它成了事实上的全局耦合点，
**它改任何签名会波及 9 个包**。

**整改**：移到独立包（如 `lobos/diag/`），或改为接口 + 各层实现。
前者是纯移动，后者要重新设计。

---

## 整改顺序

按「后果明确 + 成本低」排。**标 ⭐ 的是本轮新发现的，其余是上一轮的老账。**

| 序 | 事项 | 成本 | 依据 |
|---|---|---|---|
| 1 ⭐ | 通知点击改指控制面板（A2） | 1 处调用点 | 用户每天点得到 |
| 2 | `KillAudit` 采集端接线（A6） | 一个调用点 | 补上"死因未取证"；`auditOnce` 已恢复，只差接线 |
| 3 ⭐ | `ProgramDir` 移到 `os`（A3） | 机械替换 | 解开 7 个包的方向倒置 |
| 4 | `os` 反向依赖 `lifecycle`（A4） | 2 处 | 与 3 一起做 |
| 5 | `capability`/`permissions` 下沉（A5） | 中 | 分层主干；随 6 一起收敛 `bridge` |
| 6 | `ProgramVerifier` 去 node 依赖（A1） | 中 | 解开鸡生蛋，内置应用升级的前提 |
| 7 | 商店发 `packages[]`（A7） | 发布侧 | 与 6 配套 |
| 8 | `os` 拆子目录（A8） | 机械但量大 | 做完 3 再做 |
| 9 | `bridge` 拆三类职责（A9） | 中 | 随 5 一起做 |

做完后把 `check-layer-direction` 接回 CI（A3/A4/A5 已定，豁免表逐条写理由，
豁免数当整改进度表）。

**第 1、2 项加起来不到 10 行代码，是当前性价比最高的两处。**

---

## 这份审计自身的局限

1. **静态扫描查不出"函数体被删"。** 本轮删死码删坏四次，四种形态，
   其中三种**括号完全配平、符号解析也过得去**，只有编译能炸。
   **「静态全绿」不等于「代码是好的」。**

2. **手写核对会漏。** 第四节列反向依赖时我手写 5 处，工具跑出 10 处。
   但工具也会有 bug（本轮那个报 0 的扫描器）。
   所以工具输出必须能被人核对，不能直接采信。

3. **本文的问题清单不是完备的。** 静态分析能查的是结构（依赖方向、包大小、
   引用计数）；"职责划分是否合理""参数是否太多""错误处理是否一致"
   这些要靠人读。**这份报告能指方向，不能替代读代码。**

4. **门禁不是架构质量的保证。** 分层方向、包结构、职责划分这些
   **尚未定论**的事，该写在这里等人决策，不该做成门禁 —— 否则会挡路
   （本轮已经因为这个删掉过两道门禁，见 `docs/GATE-CLASSIFICATION.md`）。
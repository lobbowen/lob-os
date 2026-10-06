# 架构审计报告

范围：`container/app/src/main/java` 113 个 Kotlin 文件 / 16450 行 + 28 个门禁脚本 + 14 份文档。
方法：全量静态扫描 + 人工核实，不用推测当结论。

---

## 一、死代码

### 1.1 已清理：-192 行 / 34 个声明

`tools/scan-dead-code.js` 扫出。删除过程手工编辑 diff
（`remove-dead.js` 因删坏三次已弃用，见 6.2/6.3），靠 `check-kt-structure.js`
+ CI 编译兜底。

| 类别 | 数量 | 例子 |
|---|---|---|
| 私有辅助函数 | 12 | `CapabilityAcquisitionRunner.sleepQuietly`、`KillAudit.readCursor` |
| 冗余访问器 | 7 | `ProgramManager.dirFor`（`stateDirOf` 的别名）、`ProgramDir.manifestFileName` |
| 未接线的功能入口 | 5 | `PermissionRoles.policyOf` / `autoHealOf` / `byOwner` / `byPolicy` / `byAutoHeal` |
| 未使用的常量 | 8 | `OnboardingFlow.F4`、`SetupActivity.SPRINT_FREEZE_MS`、`ManifestSchema.RUNTIMES`、`PipelineProjection.OPTIONAL` |
| 未使用的参数 | 1 | `OnboardingFlow.FlowStage.extraCapId` |
| 随主函数一起失效的孤儿 | 1 | `ExecBits.isSymbolic`（`repair` 已删） |

净变化：**21 文件 / -192 行 / +1 行**（+1 是 `KillAudit.auditOnce`
删除时补的空行分隔）。

### 1.2 保留：`KillAudit` 采集链（不是死码，是未接线的功能）

`KillAudit.attribute()` + `MAX_RECORDS` + `reportUnreadable` +
`isNewerThanCursor` + `readCursor` + `writeCursor` 整块零引用，
而 `attribution()`（读取端）正在被 `ResidencyAudit` 调用。

**采集端从未接线，所以死因归因永远读不到真实数据。**
这正是真机上那句「死因未取证（还没读系统退出史）」的来源。

保留整块、待接线（见第八节待办）。不按死码删 —— 删了功能就真的不存在了。

`PermissionRoles.policyOf` / `autoHealOf` / `byOwner` / `byPolicy` / `byAutoHeal`
原判为死码，**已删**：`PermissionLedger.register()` 直接从 `role.policy` /
`role.autoHeal` 取值（表的数据），这些方法确实是多余封装。

### 1.3 最终结果

`scan-dead-code.js` 复扫：**确认死 0，可疑 20**（可疑全是 Android 回调覆写，
`MdnsWatcher` 的 mDNS 回调、`OsAccessibilityService` 等，不能删）。

---

## 二、废弃代码

### 2.1 `SupplyProvisioner` 已退化为纯工具（上一轮完成）

原 484 行 → 183 行。删除 `ensure` 及 6 个私有辅助（`applyLinkFarm` / `farmBroken` /
`aliasesOf` / `TrueName` / `linkEntry` / `ensureEntry`）。
剩下全是：下载、哈希、解包、验签、路径。

### 2.2 `ota/ProgramInstaller` 与 `os/PackageInstaller` 已合流

能力互补的两套安装器，现在都走 `ota/ProgramInstallPipeline` 一个安装点。
详见 `docs/INSTALL-CHANNEL.md`。

---

## 三、重叠的业务逻辑

### 3.1 需要合并（同类，不同实现）

| 重叠点 | 位置 A | 位置 B | 风险 |
|---|---|---|---|
| ADB 通道重测 | `MainActivity.retestChannel` | `SetupActivity.retestChannel` | 两份相似但后续流程不同，UI 分叉 |

**本轮不动。** 合并要等产品形态定下来（引导页是否会被控制面板取代）。

### 3.2 合理同名（不同模块的私有辅助，不是重复）

`file`（15 处）、`clear`（6 处）、`snapshot`（6 处）、`run`（8 处）、
`append`（3 处）—— 都是各模块的私有文件操作辅助，签名与语义不同，不构成重叠。

---

## 四、逻辑分层审计

### 4.1 当前包依赖图

```
(root)     → bridge capability lifecycle os ota permissions runtime
bridge     → capability lifecycle native os ota runtime (+OsApplication +R +ProvisioningProbe)
capability → bridge lifecycle os ota permissions            ← 反向依赖 bridge
lifecycle  → bridge capability os ota permissions ui        ← 反向依赖 bridge
native     → os runtime
os         → capability lifecycle native ota runtime       ← 反向依赖 capability/lifecycle
ota        → os runtime (+BuildConfig)
permissions→ bridge lifecycle os                           ← 反向依赖 bridge
quickapp   → os ota
runtime    → lifecycle native os ota (+ProvisioningProbe)
setup      → capability
ui         → bridge capability lifecycle os permissions quickapp setup
ui.setup   → capability lifecycle setup ui (+R +ChannelStatusText)
```

### 4.2 十处反向依赖（基础层 → 上层）

下表来自 `check-layer-direction.js` 的实测输出（本轮新增又弃用，见第七节）。
**它比手写核对多报出一倍** —— 手写只列出 5 处，工具跑出 10 处。

| # | 反向依赖 | 具体 | 性质 |
|---|---|---|---|
| 1 | `os` → `lifecycle` | `DozeBackstop` → `lifecycle.DozeBackstopReceiver` | **错位**：`DozeBackstopReceiver` 是 BroadcastReceiver（Android 组件），不是领域逻辑。`os` 不该知道它。 |
| 2 | `os` → `lifecycle` | `OsState` → `lifecycle.ResidencyPolicy` | **常量放错层**：`ResidencyPolicy` 的常量（`WAKE_BACKSTOP_MS` / `REASON_NO_PROGRAM`）属领域模型。 |
| 3 | **`lifecycle` → `ui`** | `OsHostService` → `ui.setup.SetupActivity` | **最严重**：后台宿主服务依赖 UI 层，UI 一动服务就受影响 |
| 4 | `capability` → `bridge` | `AdbChannelComponent` → `bridge.AdbClientRunner`；`CapabilityAcquisitionRunner` → `bridge.AdbClientRunner`；`CapabilityCriteria` → `bridge.OsNotificationListenerService` | **方向错**：`capability`（能力定义与获取）不该依赖 `bridge`（对外接口层）。应是 `bridge` 依赖 `capability`。 |
| 5 | `permissions` → `bridge` | `PermissionCenter` → `bridge.NotificationStore` | **错位** |
| 6 | `permissions` → `lifecycle` | `PermissionCenter` → `lifecycle.OsAccessibilityService` | **错位**：权限中心依赖保活实现 |
| 7 | `os` / `runtime` → `ota` | `BootReconciler` / `InstanceHost` / `KillAudit` / `ProgramManager` → `ota.ProgramDir` | **`ProgramDir` 放错包**：它管 `files/programs/<id>/` 目录，属 `os` 域，却在 `ota` 包 |
| 8 | `os` → `capability` | `OsState` / `OsInit` → `capability.ProbeOutcome` | **可接受**：值对象（data class）跨层共享 |
| 9 | `ota` → `runtime` | `ProgramVerifier` → `runtime.ProcessSupervisor` / `runtime.NodeProvisioner` | **可接受**：通用工具 |
| 10 | `runtime` → `lifecycle` | `InstanceHost` → `lifecycle.OsHostService` | **可接受**：需要 Service 作为 Context 载体 |

### 4.3 分层结论

**`os` 是基础层（3690 行，最大），但它反向依赖 `capability` 和 `lifecycle` 两层。**
这是最需要修的一处——基础层一旦依赖上层，上层就再也不能独立替换或测试。

建议的目标方向（**本轮只记录，不动代码**）：

```
domain (值对象、常量)     ← 无依赖
   ↑
os (注册表、状态、目录)   ← 不再依赖 capability/lifecycle
   ↑
capability / lifecycle / ota / runtime
   ↑
bridge (对外接口)
   ↑
ui / quickapp
```

具体要做的：
1. 把 `ResidencyPolicy` 的常量拆到 domain（或 `os`），`lifecycle` 引用它而不是反过来
2. `DozeBackstopReceiver` 移到 `lifecycle`（它本就是 Android 组件），
   `os.DozeBackstop` 只暴露 `schedule` / `cancel` 接口，由 `lifecycle` 注册 receiver
3. `AdbClientRunner` / `OsNotificationListenerService` 从 `bridge` 下沉到 `capability`，
   `bridge` 改为依赖 `capability`

---

## 五、物理分层审计

### 5.1 目录与逻辑层的对应

| 包 | 文件 | 行数 | 职责 | 评价 |
|---|---|---|---|---|
| `os` | 30 | 3690 | 注册表、状态、目录、端口、任务、会话 | **过重**。3690 行占全仓 22%，且混了 8 个不同子域（Registry/Status/Port/Task/Session/Ledger/Settings/Migration）。 |
| `bridge` | 9 | 2876 | 对外 JSON-RPC 接口 + mDNS + 通知监听 | **过重且职责不单一**。`CapabilityBroker` 单文件承载能力目录的全部方法；同时含 mDNS 监听、通知监听这些 Android 组件。 |
| `ota` | 13 | 1885 | 下载、校验、安装 | 安装部分已合流。`ProgramOtaUpdater` 仍持有单通道清单逻辑。 |
| `runtime` | 9 | 1587 | 进程监督、PREFIX、node 供给 | 合理。 |
| `capability` | 14 | 1332 | 能力目录与获取 | 合理。 |
| `lifecycle` | 8 | 1188 | 保活、宿主服务、开机对账 | 合理。 |
| `ui` | 4 | 648 | Activity 与面板 | 合理。 |
| `quickapp` | 8 | 684 | 快应用宿主、桥、能力面 | 合理。 |
| `native` | 5 | 621 | 原生资产校验 | 合理。 |
| `permissions` | 5 | 469 | 权限台账 | 合理。 |
| `setup` | 3 | 262 | 引导流程 | 合理。 |
| `ui.setup` | 1 | 479 | 引导页 Activity | 479 行单文件偏大。 |
| (root) | 4 | 678 | Application / Activity / 诊断 / 入口 | `RuntimeDiagnostics` 与 `ProvisioningProbe` 是调试设施，混在根包。 |

### 5.2 物理分层的问题

**1. `os` 包需要拆子目录**（30 个文件塞在一个包里）

现状是平铺的：`ProgramIndex.kt` / `ProgramRegistry.kt` / `ProgramManager.kt` /
`ProgramDir.kt`(在 ota) / `ProgramStatus.kt` / `ProgramMigration.kt` …
建议：

```
os/
├── registry/   Index, Registry, ProgramManager, ProgramMigration
├── state/      OsState, ProgramStatus, ResidencyStatus, StateFiles
├── runtime/    PortBroker, TaskRegistry, SessionRegistry, ProcessLedger
└── ...
```

**2. `bridge` 混了三类东西**

- 对外 JSON-RPC 方法目录（`CapabilityBroker` 2876 行的主体）
- Android 组件：`MdnsWatcher`、`OsNotificationListenerService`
- 通道实现：`AdbClientRunner`

这三类生命周期与依赖完全不同，不该同包。

**3. 根包的调试设施**

`RuntimeDiagnostics`（诊断）与 `ProvisioningProbe`（探针）混在 `lobos` 根包。
它们被几乎所有包 import，是**事实上的全局耦合点**。

---

## 六、工具自身的缺陷（本轮发现并修正）

### 6.1 `scan-dead-code.js` 曾经报 0

两个 bug：
1. **正则 `lastIndex` 跨文件泄漏** —— 共享带 `g` 的正则对象，第二个文件起全部漏匹配
2. **Manifest 解析假设属性同行** —— 实际是多行排的 `<service\n android:name=".X"\n/>`，
   导致 Manifest 组件白名单提取到 0 个

**验证方式**：造一个已知的死码文件（`ProbeDead`），确认扫描器能报出来。
没有这一步，一个报 0 的扫描器会被当成"仓库很干净"。

### 6.2 `remove-dead.js` 删坏过代码（已删除该工具）

三次出手删坏两次；改到第四种形态后脚本自身静默退出，无法再信任。
详细的事故清单与最终做法见 6.3。

### 6.3 结论：静态删除代码的风险高于收益

**清理过程中删坏四次**，四种形态各不相同：

| # | 形态 | 现象 | 括号配平能查？ |
|---|---|---|---|
| 1 | 漏删外层 `}` | `ProgramInstaller` 的 object 未闭合 | 能 |
| 2 | 跨行表达式体 | 删 `hostDegraded`，函数体（下一行）残留成悬空表达式 | 不能 |
| 3 | 跨行参数列表 | 删 `recordAttempt`，`ctx: Context,` 等参数残留 | 不能 |
| 4 | 跨行 `listOf(` | 删 `ENTRY_ORDER` / `GATING`，头和 `)` 删了一半 | 不能 |

四次都是 **CI 编译才炸**，每轮往返十几分钟。后三次括号完全配平，
符号解析也查不出。

为此写的 `remove-dead.js` 在三次出手删坏两次，改到第四种形态后
**脚本自身静默退出**，已弃用（见 7.5）。

**最终做法：`scan-dead-code.js` 只找不删 → 手工编辑 diff →
`check-kt-structure.js`（三条结构判据）+ CI 编译兜底。**

更根本的教训：**一开始就该把干净版本捞出来逐文件比对**，
而不是一次次靠 CI 报错回头。

---

## 七、门禁

本轮从 29 道减到 **18 道**，另有 1 个工具（非门禁）。

减掉的 8 道全属同一类：**编译器能报但当时本机没编译器**的产物。
CI 编译本来就会报缺 import、符号不存在、扩展函数误用，
门禁重复一遍，只在代码重构时误报。

新增 1 道：`check-kt-structure.js`（判据来自上面四次删除事故）。

完整分类见 `docs/GATE-CLASSIFICATION.md`。

### 7.1 工具（非门禁，人工判断后跑）

| 工具 | 作用 |
|---|---|
| `tools/scan-dead-code.js` | 死代码扫描，带 Manifest / 布局 / 回调白名单。**只找不删。** |

（`tools/remove-dead.js` 已删除 —— 三次出手删坏两次，之后自身静默退出。）

### 7.2 保留的门禁（18 道）

协议契约类：`check-api-spec` / `check-protocol-version` / `check-dual-canonical` /
`check-spec-tables` / `check-port-range`

外部规范类：`check-dimina-spec` / `check-page-registration` / `check-quickapp-package`

真机踩过的坑：`check-big-artifact`（OOM）/ `check-node-native-deps`（$ORIGIN linker 失败）/
`check-install-single-path`（两条路一个安装点）/ `check-ota-sequence-scope`（序列号不共用）

结构完整性：`check-kt-structure`（括号配平 + 悬空参数 + 孤立 `)` 后的残留片段；
判据来自本轮四次删除事故，见 6.3）

构建物与能力面：`check-components` / `check-android-consts` /
`check-quickapp-capability` / `check-panel` / `check-desktop-icon`

### 7.3 门禁的判据标准

1. 判**行为事实**，不判代码形状
2. 编译器能报的不做门禁
3. 门禁红了先问：是破坏共识了，还是门禁过时了 —— **后者要改门禁**
4. 门禁数量不是目标

`check-layer-direction` 是本轮新增又删掉的：它如实报出 10 处反向依赖
（比我手写的 5 处多一倍），但分层方向还没达成共识，不该由门禁来定。
那些违规列在第四节的待办里。

---

## 八、待办（按优先级）

| 优先级 | 事项 | 依据 |
|---|---|---|
| 高 | `ProgramVerifier` 的 node 依赖（鸡生蛋） | 内置应用校验需要 node，node 自己安装也需要校验。真机报过 `runtime-missing` |
| 高 | **`lifecycle` → `ui`**（`OsHostService` 依赖 `SetupActivity`） | 后台宿主服务依赖 UI 层，UI 改动会波及服务。4.2 表里最严重的一条 |
| 高 | `os` 反向依赖 `lifecycle`（2 处） | 基础层依赖上层，上层无法独立替换或测试 |
| 高 | **`ProgramDir` 放错包**（在 `ota`，被 `os`/`runtime` 依赖） | 管 `files/programs/<id>/` 目录，属 `os` 域。7 个文件受影响 |
| 中 | `capability` → `bridge` 反向依赖（3 处） | 接口层被能力层依赖，方向错 |
| 中 | `permissions` → `bridge`/`lifecycle`（2 处） | 权限中心依赖桥接与保活实现 |
| 中 | `KillAudit` 采集端接线 | 死因归因功能实际不存在（真机显示「死因未取证」） |
| 中 | 商店目录发 `packages[]`（应用程序进商店） | 商店目前只发工具链，应用程序走的是单通道 OTA |
| 低 | `os` 包拆子目录（30 文件） | 3690 行平铺在一个包里 |
| 低 | `bridge` 包拆三类职责 | JSON-RPC / Android 组件 / 通道实现混同包 |
| 低 | `retestChannel` 两份实现合并 | 待产品形态定 |
| 低 | 根包调试设施独立 | `RuntimeDiagnostics` / `ProvisioningProbe` 是全局耦合点 |

---

## 九、审计方法本身的局限

1. **静态扫描查不出"函数体被删"**。本轮四次事故都属于这类 ——
   括号配平的、符号解析也过得去，只有编译能炸。
   所以「静态全绿」不等于「代码是好的」。

2. **手写核对会漏**。4.2 节我手写列出 5 处反向依赖，
   `check-layer-direction` 跑出 10 处。**工具比人可靠，但工具也会有 bug**
   （6.1 节那个报 0 的扫描器就是例子）。所以工具的输出必须能被人核对。

3. **门禁不是架构质量的保证**。门禁只能守住已达成共识的行为；
   分层方向、包结构、职责划分这些**尚未定论**的事，
   该写在报告里等人决策，不该做成门禁 —— 否则会像这次一样挡路。

# 架构审计报告

范围：`container/app/src/main/java` 113 个 Kotlin 文件 / 16450 行 + 28 个门禁脚本 + 14 份文档。
方法：全量静态扫描 + 人工核实，不用推测当结论。

---

## 一、死代码

### 1.1 已清理：-166 行 / 32 个声明

`tools/scan-dead-code.js` 扫出，`tools/remove-dead.js` 删除，全程两道门禁兜底
（括号配平 + 跨包引用），删一个文件验一次。

| 类别 | 数量 | 例子 |
|---|---|---|
| 私有辅助函数 | 12 | `CapabilityAcquisitionRunner.sleepQuietly`、`KillAudit.readCursor` |
| 冗余访问器 | 7 | `ProgramManager.dirFor`（`stateDirOf` 的别名）、`ProgramDir.manifestFileName` |
| 未接线的功能入口 | 5 | `PermissionRoles.policyOf` / `autoHealOf` / `byOwner` / `byPolicy` / `byAutoHeal` |
| 未使用的常量 | 6 | `OnboardingFlow.F4`、`SetupActivity.SPRINT_FREEZE_MS`、`ManifestSchema.RUNTIMES` |
| 随主函数一起失效的孤儿 | 2 | `ExecBits.isSymbolic`（`repair` 已删）、`QuickAppHost.quickAppDirOf` |

### 1.2 保留：5 个（不是死码，是未接线的功能）

`KillAudit` 的采集链 —— `attribute()` + `MAX_RECORDS` + `reportUnreadable` +
`isNewerThanCursor` + `readCursor` + `writeCursor` 全部零引用，
而 `attribution()`（读取端）正在被 `ResidencyAudit` 调用。

**采集端从未接线，所以死因归因永远读不到真实数据。**
这正是真机上那句「死因未取证（还没读系统退出史）」的来源。

`PermissionRoles.policyOf` / `autoHealOf` / `byOwner` / `byPolicy` / `byAutoHeal`
同理：`PermissionLedger.register()` 直接从 `role.policy` / `role.autoHeal` 取值
（表的数据），所以这些方法是多余封装。但它们表达的是**权限策略与自动修复的分组视图**，
一旦要接"按策略自动补权限"就会用到。**按冗余封装删除，不按功能缺口保留。**

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

### 4.2 五处反向依赖（基础层 → 上层）

| # | 反向依赖 | 具体 | 性质 |
|---|---|---|---|
| 1 | `os` → `lifecycle` | `DozeBackstop` → `lifecycle.DozeBackstopReceiver` | **错位**：`DozeBackstopReceiver` 是 BroadcastReceiver（Android 组件），不是领域逻辑。`os` 不该知道它。 |
| 2 | `os` → `lifecycle` | `OsState` → `lifecycle.ResidencyPolicy` | **常量放错层**：`ResidencyPolicy` 的常量（`WAKE_BACKSTOP_MS` / `REASON_NO_PROGRAM`）属于领域模型。 |
| 3 | `os` → `capability` | `OsState` / `OsInit` → `capability.ProbeOutcome` | **合理**：`ProbeOutcome` 是值对象（data class），跨层共享值类型可接受。 |
| 4 | `capability` → `bridge` | `AdbChannelComponent` / `CapabilityAcquisitionRunner` / `CapabilityCriteria` → `bridge.AdbClientRunner` / `bridge.OsNotificationListenerService` | **错位**：`capability`（能力定义与获取）不该依赖 `bridge`（对外接口层）。应该是 `bridge` 依赖 `capability`，现在反了。 |
| 5 | `ota` → `runtime` | `ProgramVerifier` → `runtime.ProcessSupervisor` / `runtime.NodeProvisioner` | **可接受**：`ProcessSupervisor` 是通用进程工具。 |

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

### 6.2 `remove-dead.js` 删坏过代码

删 `ProgramManager.dirFor` 时连带删了后面的 `levelOfKind`（正在被 `ProgramInstallPipeline` 调用）。
第二次删 `ResidencyPolicy.hostDegraded` 时，把跨行表达式体的函数体
（`= ` 结尾、函数体在下一行）删掉，留了悬空表达式。

两次都是"行号删除"这种做法的固有风险。修正：
- 区分单行声明 / 带花括号体 / **跨行表达式体**三种形态
- 每删完一个文件立刻跑两道门禁，失败即中止
- 加 `--dry` 模式打印将删内容
- 保留"行号不含目标名字就中止"的保护

**这两次都是靠读代码发现的，不是靠门禁**——
`check-brace-balance` 查不出悬空表达式，`check-cross-refs` 查不出函数体被删。

### 6.3 结论：静态删除代码的风险高于收益

本轮 32 个删除里，**2 次删坏**（6% 错误率）。
现在脚本有三重保护，但**这类操作仍应逐个 review diff，不该批量信任工具**。

---

## 七、门禁

本轮从 29 道减到 **17 道**，另有两个工具（非门禁）。

减掉的 8 道全属同一类：**编译器能报但当时本机没编译器**的产物。
CI 编译本来就会报缺 import、符号不存在、括号不配平、扩展函数误用，
门禁重复一遍，只在代码重构时误报。

完整分类见 `docs/GATE-CLASSIFICATION.md`。

### 7.1 工具（非门禁，人工判断后跑）

| 工具 | 作用 |
|---|---|
| `tools/scan-dead-code.js` | 死代码扫描，带 Manifest / 布局 / 回调白名单 |
| `tools/remove-dead.js` | 按行号删除声明，三重形态判定 + `--dry` |

### 7.2 保留的门禁（17 道）

协议契约类：`check-api-spec` / `check-protocol-version` / `check-dual-canonical` /
`check-spec-tables` / `check-port-range`

外部规范类：`check-dimina-spec` / `check-page-registration` / `check-quickapp-package`

真机踩过的坑：`check-big-artifact`（OOM）/ `check-node-native-deps`（$ORIGIN linker 失败）/
`check-install-single-path`（两条路一个安装点）/ `check-ota-sequence-scope`（序列号不共用）

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
| 高 | `os` 反向依赖 `lifecycle`（2 处） | 基础层依赖上层，上层无法独立替换或测试 |
| 中 | `capability` → `bridge` 反向依赖（3 处） | 接口层被能力层依赖，方向错 |
| 中 | `KillAudit` 采集端接线 | 死因归因功能实际不存在 |
| 中 | 商店目录发 `packages[]`（应用程序进商店） | 商店目前只发工具链，应用程序走的是单通道 OTA |
| 低 | `os` 包拆子目录（30 文件） | 3690 行平铺在一个包里 |
| 低 | `bridge` 包拆三类职责 | JSON-RPC / Android 组件 / 通道实现混同包 |
| 低 | `retestChannel` 两份实现合并 | 待产品形态定 |
| 低 | 根包调试设施独立 | `RuntimeDiagnostics` / `ProvisioningProbe` 是全局耦合点 |

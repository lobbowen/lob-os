# 运行记录：代码级结构审计

本文所有结论来自扫 `container/app/src/main/java` 的实际代码，
不使用此前任何 md 文档作为依据。

复现工具：`node tools/audit-runtime-logs.js`

---

## 一、事实一：没有"日志模块"这个目录

`container/app/src/main/java/lobos/` 的实际包：

```
MainActivity.kt           OsApplication.kt
ProvisioningProbe.kt      RuntimeDiagnostics.kt
bridge/         9 文件    capability/     14 文件
lifecycle/      8 文件    native/          5 文件
os/            30 文件    ota/            13 文件
permissions/    5 文件    quickapp/        8 文件
runtime/        9 文件    setup/           3 文件
ui/             5 文件
```

**11 个包，没有一个叫 `log` / `logging` / `record` / `telemetry`。**

类名带记录语义的共 22 个，散在 7 个包（`os` 7 个、`capability` 2、
`lifecycle` 2、`ui` 3、`ota` 2、`permissions` 2、根包 2、`runtime` 1）。

---

## 二、事实二：22 个类里，只有 4 个在写记录

按实际写入行为分类（判据：是否调 `Journal.append` / `RuntimeDiagnostics.append` /
`StateFiles.write*`）：

| 行为 | 数量 | 类 |
|---|---|---|
| 写 `Journal` | 8 | `Journal`·`RuntimeDiagnostics`·`CatalogClient`·`KillAudit`·`ProgramStatus`·`PairingProbeService`·`PermissionLedger`·`ProcessLedger` |
| 只写诊断 | 1 | `AccessibilityServiceState` |
| 只写自己（不写任何记录模块） | 4 | `ProvisioningProbe`·`ResidencyAudit`·`ResidencyStatus`·`ProbeJournal` |
| 不写盘 | 9 | `CapabilityCatalog`·`DeviceOwnerProbe`·`OsState`·`ProgramStateMachine`·`StateFiles`·`SelfCheckReport`·`PermissionCatalog`·`ProgramOtaStateStore`·`StatusTileService` |

那 9 个"不写盘"的是**读状态**或**纯计算**，不是记录，不在本问题内。

---

## 三、事实三：真正的结构问题（按严重度）

### 3.1 数据源唯一，但有 4 份视图、4 种格式

`os/Journal.kt:167` 是唯一数据源（`events.jsonl`，JSONL，512KB/500 行轮转）。

写入路径（实测 `RuntimeDiagnostics.kt:71` `appendEvent`）：

```
RuntimeDiagnostics.append(stage, ok, message, detail)   ← 45 种 stage / 113 处调用
   ├─→ diagnostics.txt        appendBounded   512KB/500 行   人读
   ├─→ os/diag.jsonl          appendBounded   512KB/500 行   结构化
   └─→ Journal.append(...)                                 ← 数据源
```

另有一份 `node-stderr.log`（`RuntimeDiagnostics.kt:135` `recordNodeStderr`，
同样 512KB/500 行）。

**四份都有界，但格式四种。** 捞一次问题要拉四个文件、对四种格式。

### 3.2 两个模块绕过了数据源

| 模块 | 落在哪 | 是否写 Journal | 性质 |
|---|---|---|---|
| `ui/ProbeJournal`（50 行） | `probe-journal.txt`，纯文本 | **否** | **配对调试专用**，带 6 个 `@Volatile` 时间戳供 `verdicts()` 算 browse→记录延迟 |
| `lifecycle/ResidencyAudit`（77 行） | `residency.txt`，自定义两行 | **否** | 存活时间记录，格式是"两个时间戳/行" |

实测确认：`ProbeJournal.kt:11` 声明 `FILE = "probe-journal.txt"`，
`ResidencyAudit.kt:14` 声明 `FILE = "residency.txt"`，
两者都没有任何 `Journal.` 调用。

**这两者的性质要分开看：**

- `ResidencyAudit` 记的是"上次活到什么时候"——**这是状态不是记录**
  （它要的是当前值，历史在 Journal 里）。应明确归类，不是"记录模块缺一块"。
- `ProbeJournal` 是**配对流程的调试探针**，不是通用日志。
  它的 `verdicts()` 依赖内存里 6 个时间戳，那些是状态、不能进 Journal。
  但它的 `append()` 记的那些行**是事件**，该走 Journal。

**所以第 2 条的改法要分开**：状态留内存、事件走 Journal，
不是整个模块并进去。

### 3.3 死因归因一件事，三个出口

| 环节 | 落点 | 走 Journal？ |
|---|---|---|
| 采集退出史 | `KillAudit.auditOnce` → `Journal.append` | 是 |
| 去重游标 | `kill-audit-cursor.txt` | 不适用（状态） |
| 快速查询 | 内存 `Reading` | **否**（绕过） |

全仓只有 `KillAudit.kt` 一个文件"既声明自己的落盘文件、又写 Journal"（实测确认）。

### 3.4 没有导出层

四个记录文件的读取者数量：

| 模块 | 引用者 | 出口 |
|---|---|---|
| `Journal` | 23 | 无对外 |
| `RuntimeDiagnostics` | 21 | 诊断页 |
| `ProbeJournal` | 2 | `SetupActivity` 第 430 行附近 |
| `ResidencyAudit` | 2 | `SetupActivity` 第 265/426 行 |
| `KillAudit` | 2 | `ResidencyAudit.interruption()` |

**没有"导出一次、格式统一、带设备元信息"的入口。**
用户报障时只能 adb 拉文件，我们自己解析四种格式、对齐时间戳。

### 3.5 `Journal` 的字段缺 `level`

现有字段（`Journal.kt:65`）：

```kotlin
data class Event(val seq: Long, val atMs: Long, val category: String,
                 val reason: Reason?, val detail: String)
```

`reason` 是"为什么退出"（`lowMemory`/`crash`/`anr`…），
**不是日志级别**（error/warn/info/debug）。

而 `RuntimeDiagnostics` 那边有三级（`Level.OK/FAIL/INFO`），
但它落在视图里、没进数据源。
**遥测要按级别筛，数据源现在给不出这个维度。**

**加 `level` 的实际成本（已核实）：**

- `Event` 只有两处构造，都在 `Journal.kt` 内部（`:109` 构造、`:144` 反序列化），
  **外部零构造点** —— 改字段本身很小。
- `Journal` 有两个入口：`note` 57 处、`append` 32 处。
- **`note` 已经带 `ok: Boolean?` 三态**（`Journal.kt:96`），但第 97-102 行
  把它拼成文本 `"（ok）"` / `"（failed）"` 就丢掉了。

**所以级别信息不是"缺"，是"有但没结构化"。**
两条路：

| 方案 | 做法 | 代价 |
|---|---|---|
| A | `Event` 加 `level` 字段，`note` 把 `ok` 映射成 level 传下去 | 改 1 处构造 + 1 处反序列化 + 1 处 `note`。**调用方零改动** |
| B | 69 处调用点全部显式传级别 | 准确但改动大，且要逐处判断该是 error 还是 warn |

**建议 A**：`ok=true` → `info`、`ok=false` → `error`、`ok=null` → `info`，
先把已有信息结构化，粒度不够以后再让重点调用点显式传。

---

## 四、目标结构

```
   运行事件（状态变化 / 探针 / 退出史 / 异常 / 诊断）
        │
        ▼
   唯一记录入口  ← Journal（已存在，字段与轮转都齐）
        │
        ├─→ 本机查询（诊断页 / 引导页）    现状已有
        ├─→ 用户导出（报障）              缺失
        └─→ 遥测                          下一阶段，位置预留
```

| 要求 | 现状 | 差距 |
|---|---|---|
| 唯一入口 | `Journal` 已被 23 个文件用 | `ProbeJournal` / `ResidencyAudit` 还在外面 |
| 字段齐 | `seq`/`atMs`/`category`/`reason`/`detail` | 缺 `level` |
| 有界 | 数据源与三份视图都 512KB/500 行 | 两个旁路模块无界 |
| 有出口 | 只有本机 UI | 缺导出层 |
| 遥测预留 | 无 | 靠补 `level` 预留 |

---

## 五、改法（按依赖顺序）

| 序 | 改什么 | 动哪个文件 | 大小 |
|---|---|---|---|
| 1 | `ResidencyAudit` 明确归类为**状态**（不是记录）；把"中断/归因"这类事件补写进 Journal | `lifecycle/ResidencyAudit.kt` | 小 |
| 2 | `ProbeJournal` 的 `append()` 改走 Journal；6 个 `@Volatile` 时间戳留在内存（状态，`verdicts()` 依赖） | `ui/ProbeJournal.kt` | 小 |
| 3 | `Journal.Event` 加 `level` 字段，`note` 把已有的 `ok: Boolean?` 映射进去（调用方零改动） | `os/Journal.kt` 内部 3 处 | 小 |
| 4 | `KillAudit` 去掉内存 `Reading`，`attribution()` 查 Journal | `os/KillAudit.kt` | 中 |
| 5 | 定导出层：一次捞齐、格式统一、带设备与版本 | 新增 | 中 |

第 1、2 条做完，"有新东西发生却不走记录入口"就清零了，合计不到 50 行。

第 3 条是遥测的前置。**`level` 加在 `Journal` 上，不是加在视图上** ——
否则遥测又要从视图取。而且级别信息已经存在（`note` 的 `ok: Boolean?`），
只是被拼成文本丢掉了，不用改 69 个调用点。

---

## 六、`kill-audit` 会不会被高频日志冲掉

这个问题**不需要真机数据，代码里就能算清**。

### 6.1 三个可能的周期写入源，全部有状态变化守卫

| 写入点 | 触发 | 守卫 |
|---|---|---|
| `os-phase` | `OsHostService` tick，15s 一次（`ResidencyPolicy.FAST_TICK_MS`） | `if (next != null)` —— 只在相位真变了才写 |
| `adb-channel` | `AdbChannelComponent.tick` | `if (changed)` —— 只在端口变了才写 |
| `kill-audit` | `KillAudit.auditOnce` | `isNewerThanCursor` —— 游标去重，同一条不重复写 |

另确认 `SupervisorPool.sync()` 是**事件驱动**（`start` / `onHostStart`），不是周期调用。

### 6.2 静态扫描结果

扫全仓「循环内的 `Journal` 写入」共 27 处，逐个核对：**没有无守卫的高频源**。

### 6.3 结论

**稳态下 `Journal` 增长接近 0** —— tick 虽然 15s 一次，
但三条写入路径都有「状态真变了才写」的守卫。

`kill-audit` 每条新退出记录只写一次（有游标），
512KB / 500 行的上限**足够容纳大量死因记录**。

**所以「死因归因会被冲掉」这个担心不成立。** 保留期按现状（512KB/500 行）不用改。

### 6.4 仍需真机才能确认的（与上面无关）

1. 各记录文件的**实际体积**（理论上界已知，实际多少要跑）
2. `Journal` 落盘的**实际吞吐**（有没有异常刷写）

这两条不影响上面的结论，只是体检数据。

### 6.5 没评估的

**记录内容质量**：只看了结构（谁写、写哪、谁读），
没看每条内容是否有用、是否含敏感信息。

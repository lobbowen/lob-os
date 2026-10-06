# 架构整改方案

基于 `docs/ARCHITECTURE-AUDIT.md`（架构问题）与 `docs/RUNTIME-LOGS-AUDIT.md`（运行记录结构）。
所有数据来自扫 `container/app/src/main/java` 的实际代码。

**这是方案，不是实施记录。** 每一项都给出动哪些文件、为什么、判据是什么。

---

## 零、当前结构的事实基线

| 项 | 实测 |
|---|---|
| Kotlin 文件 | 113 个 / 16450 行 |
| 包 | 11 个（`bridge` `capability` `lifecycle` `native` `os` `ota` `permissions` `quickapp` `runtime` `setup` `ui`）+ 根包 4 文件 |
| 最大的包 | `os` 30 文件 / 3727 行（占 23%） |
| 无日志包 | 没有 `log`/`logging`/`record`/`telemetry` 目录 |
| 运行记录 | 数据源唯一（`os/Journal`），四份视图四种格式 |
| 跨包依赖 | 10 处基础层反向依赖上层 |

---

## 一、目录怎么改

### 1.1 新建 `lobos/record/` — 运行记录模块

**理由**：`Journal` 是全仓唯一数据源（26 个文件引用），却藏在 `os` 包里，
和端口、注册表这些状态管理混在一起。它是横切关注点，不属于任何单一域。

```
lobos/record/
├── Journal.kt        ← 从 os/ 移入（166 行）
├── LogLevel.kt       ← 新增：日志级别
├── Exporter.kt       ← 新增：导出层（用户报障 / 遥测的共同入口）
└── Recorder.kt       ← 新增：把 RuntimeDiagnostics 的转发收进来
```

**命名冲突必须避让**（已核实，全仓现有两个 `Level`）：

| 现有 | 定义处 | 含义 |
|---|---|---|
| `os.Level` | `os/ProgramIndex.kt` | 程序层级：`INFRA` / `CAPABILITY` / `CHANNEL` / `APPLICATION` |
| `RuntimeDiagnostics.Level` | 根包 | 日志级别：`INFO` / `OK` / `FAIL` |

所以新增的日志级别叫 **`LogLevel`**（不是 `Level`），
并把 `RuntimeDiagnostics.Level` 一并收进 `record` 改名为 `LogLevel`，
否则会有三个同名 `Level`。

**判据**：所有"发生过什么"都从 `record` 进，不从 `os` 进。

### 1.2 `lobos/os/` 30 文件 —— **先不拆**

我原方案写"拆成 7 个子包"。**核实后撤回这一条。**

按职责分组确实做得到（30 文件全部归组无遗漏），但组间**互相依赖、不构成单向分层**：

| 子包 | 依赖 `os` 内哪些子包 |
|---|---|
| `registry` | `install` `lifecycle` `program` `runtime` `state` |
| `program` | `env` `install` `lifecycle` `registry` `runtime` `state` |
| `install` | `program` `registry` `runtime` `state` |
| `runtime` | `state` |
| `lifecycle` | `program` `registry` `runtime` `state` |
| `env` | `install` `program` `registry` |
| `state` | `registry` |

**每组都依赖 3-6 个其它组，拆开后立刻产生 20+ 处跨子包引用 ——
比平铺在一个包里更糟。**

耦合根源（实测，被 `os` 内多少类引用）：

| 类 | 引用次数 | 被几个 `os` 类引用 |
|---|---|---|
| `Journal` | 36 | 10 |
| `ProgramIndex` | 35 | 7 |
| `StateFiles` | 19 | 15 |
| `ProgramManager` | 15 | 5 |

`StateFiles` 被 15 个 `os` 类引用、`Journal` 被 10 个 —— **它们是 `os` 的公共底座**。

**结论**：`os` 是一个高内聚的"系统核心"包，30 文件 3727 行虽然大，
但它的内聚性是真的。**先不动它**，等它真的疼了再说。

如果将来要拆，正确的顺序是**先把 `Journal`/`StateFiles` 抽出去**
（它们已经在做这件事，见 1.1），剩下的按实际依赖重新分组，而不是照职责分组。

### 1.3 `os/KillAudit.kt` → `lobos/record/`

**理由**：它采集系统退出史、经 `Journal` 落盘，
是"记录的事件来源"，不是"os 域的状态"。

### 1.4 保持不动的

| 包 | 理由 |
|---|---|
| `runtime` | 已是基础工具层（进程监督、PREFIX、node 供给），职责单一 |
| `native` | 原生资产校验，5 文件职责单一 |
| `quickapp` | 快应用宿主 + 配对，8 文件 |
| `setup` | 引导流程，3 文件 |

---

## 二、逐个文件的调整

### 2.1 移动（纯改包声明 + import，逻辑零变化）

| 从 | 到 | 触发的 import 修改 |
|---|---|---|
| `os/Journal.kt` | `record/Journal.kt` | 6 处 |
| `os/KillAudit.kt` | `record/KillAudit.kt` | 1 处 |

**合计 7 处 import**（已实跑核实：跨包 `import lobos.os.Journal` 6 处、`os.KillAudit` 1 处）。

**`os` 其余 28 个文件不动** —— 见 1.2，拆分的条件不成立。

`Journal` 被 10 个 `os` 类引用，移动后这 10 处**同包无需 import**，实际改动比数字更少。
唯一要注意的是 `os` 内部 36 处 `Journal.` 全限定调用**不用改**（同包）。

### 2.2 新增

| 文件 | 内容 |
|---|---|
| `record/Level.kt` | `enum class Level { INFO, OK, WARN, ERROR }` |
| `record/Exporter.kt` | 一次捞齐四份视图 + 带设备与版本元信息 |
| `record/Recorder.kt` | `RuntimeDiagnostics` 的转发逻辑收进来 |

### 2.3 修改

| 文件 | 改什么 | 大小 |
|---|---|---|
| `os/Journal.kt` | `Event` 加 `LogLevel` 字段；`note()` 把已有的 `ok: Boolean?` 映射进去 | 3 处（`:65` 字段、`:109` 构造、`:144` 反序列化） |
| `record/Recorder.kt` | 取代 `RuntimeDiagnostics.appendEvent()` 的转发；`RuntimeDiagnostics.Level` 改名 `LogLevel` 收进来 | — |
| `ui/ProbeJournal.kt`（50 行） | `append()` 改调 `Journal`；6 个 `@Volatile` 时间戳**留在内存**（`verdicts()` 依赖，是状态不是记录） | 小 |
| `lifecycle/ResidencyAudit.kt` | 存活时间继续自己存（状态）；把"中断/归因"补写进 `Journal` | 小 |
| `lifecycle/OsHostService.kt:283` | 通知点击 Intent 改用 `entryActivity()` 判断 | 已改 |
| `ota/ProgramVerifier.kt` | 签名校验从"spawn node 跑 JS"改为 Kotlin 侧 Ed25519 + 逐文件 sha256 | 中 |
| `ota/ProgramDir.kt` | 移到 `os/`（7 个包依赖它，属 `os` 域，不是 `ota`） | 机械 |
| `capability/*`（3 文件） | `AdbClientRunner` / `OsNotificationListenerService` 从 `bridge` 下沉到 `capability` | 中 |
| `permissions/PermissionCenter.kt` | `NotificationStore` 从 `bridge` 下沉 | 小 |

### 2.4 删除

| 对象 | 依据（已核实） |
|---|---|
| `RuntimeDiagnostics` 的 `diagnostics.txt` | **无任何外部读取者**。`file()` 这个访问器只有 `RuntimeDiagnostics` 自己在用；真正被读的是 `events()`（结构化视图），只有 `CapabilityBroker` 一个调用者 |
| `KillAudit` 的内存 `Reading` 快路径 | 绕过 `Journal`，同一件事三个出口 |
| `kill-audit-cursor.txt` | 游标并入 `Journal` 侧管理 |
| `ProbeJournal` 的 `probe-journal.txt` | `append()` 走 `Journal` 后不再需要 |

**保留**：`node-stderr.log` —— 那是 node 进程的原始输出，
不是我们的事件，无法结构化。`os/diag.jsonl` —— `events()` 读它。

**核实结果**（四个访问器的外部调用者）：

| 访问器 | 外部调用者 |
|---|---|
| `file()` | 无 |
| `nodeErrFile()` | 无 |
| `structFile()` | 无 |
| `events()` | `bridge/CapabilityBroker` |

### 2.5 已完成（本轮之前）

| 事项 | 状态 |
|---|---|
| `SupplyProvisioner.ensure` 及其 6 个私有辅助 | 已删（-301 行） |
| 两套安装器合流到 `ProgramInstallPipeline` | 已做 |
| 死代码 34 个声明 | 已删（-192 行） |
| 门禁 29 → 18 | 已做 |

---

## 三、依赖方向

**目标**（箭头 = 依赖方向，基础层在下）：

```
        ui  quickapp
          ↓
       bridge
          ↓
  capability  lifecycle  ota    ← 三个中层互不依赖
          ↓
   record  os/{registry,install,runtime,state}  runtime  native
          ↓
   permissions  os/env  os/lifecycle
```

**要解开的 4 处反向依赖**：

| 现状 | 改成 | 动法 |
|---|---|---|
| `lifecycle` → `ui.setup.SetupActivity` | 通知入口用 `entryActivity()` 判断，不硬编码 | 已改 |
| `os` → `lifecycle.ResidencyPolicy` | 常量移到 `os/lifecycle/` | 机械 |
| `capability` → `bridge.AdbClientRunner` | 下沉到 `capability` | 中 |
| `permissions` → `bridge.NotificationStore` | 下沉到 `permissions` | 小 |

**`ProgramDir` 从 `ota` 移到 `os/registry/`** —— 它管 `files/programs/<id>/`，
被 7 个包依赖（含 `quickapp` 反过来依赖 `ota`），属 `os` 域。

---

## 四、实施顺序

| 阶段 | 内容 | 风险 | 判据 |
|---|---|---|---|
| **1** | `Journal.Event` 加 `LogLevel`（`note` 的 `ok` 映射进去） | 极小 | 现有 89 处 `Journal.` 调用点零改动 |
| **2** | `ProbeJournal.append` + `ResidencyAudit` 事件走 `Journal` | 小 | 「有新东西发生却不走记录入口」清零 |
| **3** | `KillAudit` 去内存快路径、游标并入 | 小 | 全仓只剩 `record` 一个落盘入口 |
| **4** | 建 `record/` 包（移 2 个文件）+ `LogLevel` + `Exporter` | 小（7 处 import） | 遥测位置就绪 |
| **5** | `ProgramDir` 移 `ota` → `os` | 小 | `grep -r "ota.ProgramDir"` 零命中 |
| **6** | `capability`/`permissions` 解耦 `bridge` | 中 | 反向依赖归零 |
| **7** | `ProgramVerifier` 去 node 依赖 | 中 | 内置应用升级不再需要 node 在位 |
| ~~8~~ | ~~拆 `os` 七个子包~~ | — | **撤回**：组间互相依赖，拆了更糟（见 1.2） |

**阶段 1-4 合计约 100 行，是性价比最高的一段**，且都是独立可验证的小改动。

---

## 五、每阶段的判据

不靠"感觉改好了"，每项给可查的判据：

| 阶段 | 判据 |
|---|---|
| 1 | `grep "Journal.append(" ` 的调用点签名不变；`Event` 落盘含 `level` |
| 2 | 全仓无 `probe-journal.txt` 写入；`ResidencyAudit` 的中断事件出现在 `events.jsonl` |
| 3 | `grep "Reading"` 在 `record/KillAudit.kt` 零命中 |
| 4 | `node tools/audit-runtime-logs.js` 输出"数据源外的残留 = 0" |
| 5 | （已撤回） |
| 5 | `grep -r "ota.ProgramDir"` 零命中 |
| 7 | `capability`/`permissions` 的 import 里不再出现 `lobos.bridge` |
| 8 | `grep "NodeRuntime" container/app/src/main/java/lobos/ota/ProgramInstaller.kt` 零命中 |

---

## 六、风险

| 风险 | 缓解 |
|---|---|
| `os` 拆包的 20+ 处跨子包引用漏改（若将来仍要拆） | CI 编译兜底；`check-kt-structure` 查结构 |
| `ProgramVerifier` 重写引入签名校验漏洞 | 保留 `program-verify.js` 作交叉验证，比对两边结论 |
| `capability`/`permissions` 下沉破坏现有调用 | 逐个编译；下沉是纯移动 |
| 导出层涉及隐私（要带设备信息） | **需要你定**：导出哪些字段、是否需要用户知情 |

---

## 七、需要你定的四件事

1. **`level` 的映射规则**：`note` 的 `ok: Boolean?` 三态映射成什么？
   （建议 `false→ERROR`、`true→INFO`、`null→INFO`）
2. **导出层给多少信息**：设备型号？Android 版本？是否含程序 id 列表？
3. **记录保留期**：现状 512KB/500 行，静态分析已确认够用（第六节），要不要按 category 差异化？
4. **`diagnostics.txt` 与 `diag.jsonl` 删哪个**：人读文本 vs 结构化 JSONL，
   诊断页现在用哪个要确认。
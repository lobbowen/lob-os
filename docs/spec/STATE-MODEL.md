# 系统状态规范

本文件定义整套产品里的「状态」到底有哪几种、各自是什么、由谁负责、界面该怎么显示。
目的：消除自造状态与概念混用（例如曾把空的 installed.json 当账本、把「缺记录」当状态）。

---

## 1 一条总规矩

**状态只有一个真相源，不许在界面层自造状态。**

每个状态必须能回答：谁维护它、依据什么事实、用户看到它后知道该做什么。
界面只呈现下面三类状态，不发明第四类。

---

## 2 三类状态（各司其职，不可混用）

### 2.1 系统整体状态（OsPhase）

**真相源**：`files/var/lib/state.json`，由 `OsInit` 维护。
**含义**：这套系统整体的生死状态，与某个具体程序无关。

| 值 | 含义 | 依据 |
|---|---|---|
| BOOTING | 正在启动 | 本次生命周期刚开始、读数未齐 |
| RUNNING | 运行中 | 自检读数齐、控制面在线 |
| DEGRADED | 降级 | 自检读数缺或控制面不在线 |
| STOPPING | 停止中 | 外部请求停止 |

**界面呈现**：首页状态环。环里只放这一状态，不放别的。
**谁改变它**：系统自己（控制面/自检），用户不能直接改。
**用户动作**：环下方是启动/停止按钮（走 OsHostService 真实 action）。

**降级（DEGRADED）的准确含义**：不是"某个程序没跑"，而是
**系统的自检读数不齐或控制面（CapabilityBroker）不在线**。
一个工具程序没运行，绝不导致系统降级 —— 这是丙修复后的正确语义。

### 2.2 组件状态（UnitState）

**真相源**：`files/var/lib/program-index.json` 的条目，由装配器/安装器/监督器维护。
**含义**：每个已装组件的实际状态。系统已有完整判定，界面必须直接用，不许另造。

分三个维度（`UnitState.kt`）：

| 维度 | 取值 | 含义 |
|---|---|---|
| **Load**（装载） | NOT_FOUND / BAD_SETTING / LOADED | 件在不在、清单有没有问题 |
| **Active**（活跃） | ACTIVE / INACTIVE / ACTIVATING / DEACTIVATING / FAILED / RELOADING / MAINTENANCE | 这个单元当前是否在活跃态 |
| **Sub**（子状态） | NA / STARTING / RUNNING / DEGRADED / STOPPING / FAILED / DEAD | 更细的运行状态 |

判定函数（直接用，不重写）：
- `UnitState.loadOf(entry)` —— 装载态
- `UnitState.activeOf(entry, processAlive, ...)` —— 活跃态
- `UnitState.subOf(entry, processAlive, healthy)` —— 子状态
- `UnitState.isRunnable(entry)` —— 是不是可运行单元（library/headers 不是）

**界面呈现**：
- 组件列表每项显示：名称、版本、**状态标签**（由上面三维度算出）、大小、角色
- **禁止**用"缺记录"这类自造词 —— 系统注册表里每个件都有 version/role/desired，
  所谓"缺安装记录"是拿一个从未写入的假账本（installed.json）去比真实注册表造成的错觉

### 2.3 运行时观测（Residency）

**真相源**：`files/run/residency.json`，由 `ResidencyPolicy` 从 ProcessLedger 统计。
**含义**：一段时间内"哪些程序在跑、为什么降级"的观测事实，**是 Observability，不是控制状态**。

字段：programsRunning / runningIds / installedPrograms / degradedReasons / actions / frozen。

**界面呈现**：状态环下方的详情行（"N 个已装 · M 个在运行"）、恢复页的诊断列表。
**重要**：residency 的 `degradedReasons=["没有程序在运行"]` 只是**客观观测**，
**不驱动系统整体状态**（那是 OsPhase 的事，丙修复后只看控制面）。

---

## 3 三类状态的关系（不可混淆）

```
OsPhase（系统整体）    <- OsInit，看控制面与自检
    与组件无关

UnitState（每组件）    <- program-index.json，看装载/活跃/子状态
    每个件一份，互不影响

Residency（运行观测）  <- ProcessLedger，看哪些进程活着
    只读观测，不驱动上面两者
```

**严禁**：
- 用 residency 的"没有程序在跑"去判系统降级（曾犯，已修）
- 用假账本 installed.json 去判组件状态（曾犯，应清）
- 在界面层自己拼装"看起来像状态"的字符串

---

## 4 已撤销的自造状态（反面案例）

| 自造物 | 问题 | 处置 |
|---|---|---|
| installed.json 账本 | 从未被写入，拿它比注册表导致 16 个件全显示「缺记录」 | 撤销，组件状态改用 UnitState |
| RuntimeState（进程内变量） | 显示"未启动"，与真实 OsPhase 脱节 | 撤销，环改读 OsPhase |
| hostd 探活空壳 | 只为补"服务实例"形式，无依赖方 | 撤销（见 ARTIFACT-CLASSIFICATION） |

**教训**：系统的状态框架本来就完整（OsPhase + UnitState + Residency），
自造第四套只会制造假象。先用现成的，不够再补。

---

## 5 界面该怎么显示（目标形态）

**状态屏（首页）**：
- 环：OsPhase 一个值 + 启动/停止按钮
- 详情行：residency 的观测（已装数/运行数）
- 三张卡：空间 / 组件 / 故障，点击下钻

**组件屏**：
- 已安装：名称 + 版本 + **Load/Active/Sub 状态标签** + 大小 + 角色
- 可安装：清单里的待装件
- 不许出现「缺记录」等自造词

**恢复屏**：
- 列出 residency 的 degradedReasons 与 actions（客观观测）
- 一键修复（重建库链接）

---

## 6 下一步（基于本规范）

1. 删除假账本逻辑（installed.json 相关代码与界面引用）
2. 组件屏改用 `UnitState.loadOf/activeOf/subOf` 显示真实状态
3. 首页状态环与详情已接真实 OsPhase / Residency，保持不变
4. 用设备实测：每个组件显示真实状态，不再有「缺记录」

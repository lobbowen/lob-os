# 运行记录体系审计

问题：系统运行记录没有统一模块，`KillAudit` 只是其中一个碎片，
挂在通知构建里，位置不对。

审计工具：`tools/audit-runtime-logs.js`（判据：谁往磁盘写、写哪、谁在读）。
配套：`docs/ARCHITECTURE-AUDIT.md`（架构）、`docs/INSTALL-CHANNEL.md`（安装通道）。

---

## 一、现状

### 1.1 两类东西被混在一起

| 类别 | 是什么 | 例子 | 判据 |
|---|---|---|---|
| **状态** | 当前值，重启后要恢复 | `ports.json`（端口占用）、`program-index.json`（程序注册表）、`state.json`（相位）、`process-ledger.json`（进程账本） | 有 schema、要原子写、**不该有历史** |
| **记录** | 发生过什么，只追加不修改 | `os/journal/events.jsonl`（事件流）、`diagnostics.txt` + `os/diag.jsonl`（诊断）、`residency.txt`（常驻）、`probe-journal.txt`（探针） | 追加、有时间戳、**该有保留期** |

**`os/Journal` 已经是标准化的记录模块**：JSONL 格式、单调 `seq`、
`atMs` + `category` + `reason` + `detail` 四字段齐全、
512KB 触发轮转并保留 500 行。缺的不是模块，是**没人把它当唯一入口**。

问题在于 `KillAudit` 为同一件事开了三个出口：
写在 `events.jsonl`（对）、游标另存 `kill-audit-cursor.txt`、
快速查询走内存 `reading` 绕过 Journal。

### 1.2 五套记录并行，格式不统一

| 落盘目标 | 格式 | 轮转 | 写手 | 消费端 |
|---|---|---|---|---|
| `os/journal/events.jsonl` | JSONL，字段齐 | ✅ 512KB/500 行 | `os/Journal` | 多处 |
| `diagnostics.txt` + `os/diag.jsonl` + `node-stderr.log` | **同一个类三份**（文本 + JSONL + stderr） | ❌ | `RuntimeDiagnostics` | 诊断页 |
| `probe-journal.txt` | 纯文本 | ❌ | `ui/ProbeJournal` | 引导页"最近动作" |
| `residency.txt` | 自定义两行格式 | ❌ | `lifecycle/ResidencyAudit` | `interruption()` |
| `kill-audit-cursor.txt` | 单行两数字 | 不适用（游标） | `os/KillAudit` | `readCursor` |
| 退出史内存副本 | `Reading` 对象 | 不适用 | `os/KillAudit.auditOnce` | `attribution()` |

**`Journal` 之外的四套都不轮转、不结构化、无字段约定。**
`RuntimeDiagnostics` 一个类写三份不同格式的文件，是"缺统一入口"的典型症状 ——
需要时就近加一个文件，最后自己长成了三套。

### 1.3 消费端：49 处引用，但只有"给人看"一种出口

读这些记录的地方 49 处，绝大多数是**写完自己读回来看**。

真正对外的出口只有两个：

| 出口 | 给谁 | 内容 |
|---|---|---|
| 通知 | 用户 | `ProgramStatusHub.summaryLine()` / `ProgramNotificationHub.summaryLine()` —— 状态摘要 |
| 引导页"最近动作" + 第 265/426 行 | 本机用户 | 探针日志尾部若干行、常驻中断与死因归因 |

**没有给"我们"的出口**：用户报障时，我们只能 adb 拉设备文件，
一次要捞 5 个文件、4 种格式，还要人工对齐时间戳。
也没有结构化上报通道 —— 想统计"多少人被低内存杀了"都做不了。

`KillAudit` 的位置问题**不是**"挂在通知上"（它没挂通知，
通知文案只有状态摘要）。问题是**为同一件事开了三个出口**：
`events.jsonl`（对）、`kill-audit-cursor.txt`（游标另存）、
内存 `reading`（快路径绕过 Journal）。

---

## 二、后果

| 后果 | 说明 |
|---|---|
| **同一件事三个出口** | 退出史写在 `events.jsonl`（对），但游标另存 `kill-audit-cursor.txt`、快速查询走内存 `reading`。要查"上次怎么死的"得同时理解三处 |
| **捞一次问题要 5 个文件 4 种格式** | 用户报障 → adb 拉 `events.jsonl` + `diagnostics.txt` + `diag.jsonl` + `residency.txt` + `probe-journal.txt` → 人工对齐时间 |
| **没有"给我们"的出口** | 死因归因只在引导页第 265/426 行显示给**本机用户**。用户报障时说不清，我们只能 adb 拉 5 个文件人工对齐 |
| **时间对不齐** | 各记录各用各的 `SimpleDateFormat`，跨文件关联靠人工 |
| **记录模块名不副实** | `RuntimeDiagnostics` 一个类写三份文件（文本 + JSONL + stderr），说明缺的是统一入口而不是接口 |
| **死因归因链路过长** | `KillAudit` → `ResidencyAudit.interruption()` → `OsInit` → `SetupActivity` 第 265/426 行。四层跳转才到用户眼前 |

**通知本身是干净的**：文案只来自 `ProgramStatusHub.summaryLine()` /
`ProgramNotificationHub.summaryLine()`，都是状态摘要，没有塞死因。
用户判断"通知为什么关了"要靠引导页，不是通知 —— 这点没问题。

**证据落盘是做到的**（`Journal` 有轮转、有序列号），
缺的是**统一**：谁该往 Journal 写、格式怎么定、怎么对外导出。

---

## 三、`KillAudit` 的处置

它有价值——系统退出史是 Android 提供的**唯一权威死因来源**，
没有它，被回收时只能说"未取证"。

**它的采集链路是对的**（走 `Journal.append` 落 `events.jsonl`、游标持久化）。
**位置是错的**：为同一件事开了三个出口。

| 现在 | 应该 |
|---|---|
| 退出史存两份：落盘 `events.jsonl` + 内存 `reading` | 只留落盘。`attribution()` 直接查 Journal |
| 游标单独一个 `kill-audit-cursor.txt` | 并入 Journal 的游标管理 |
| 自成一套 `reportUnreadable` 私有路径 | 走 `Journal` 的 reason 机制（已有 `Reason.UNREADABLE`） |
| 在 `lifecycle` 里被间接调用（`ResidencyAudit.interruption()`） | 归 `os` 的记录层，UI 只消费 |

**它不该是独立模块，而应该是 `Journal` 的一个"事件来源"** ——
和 `BootReconciler`、`CatalogClient`、`PackageInstaller` 一样，
写记录只调 Journal，不自己管落盘。

---

## 四、目标形态

### 4.1 一层薄接口，三个用途

```
                       ┌─────────────────────────┐
   运行事件 ──────────→│   统一记录模块（唯一入口）   │
   （状态变化/          │  · 结构化（时间/级别/域/事件）│
     探针结果/         │  · 追加写，不改历史          │
     退出史/           │  · 保留期与轮转              │
     异常）            │  · 一个游标（去重/增量）      │
                       └───────────┬─────────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ↓                    ↓                    ↓
        本地查询              用户反馈              遥测上报
     （诊断页/引导页）    （一键导出记录包）      （结构化出口）
```

### 4.2 记录事件的结构

统一成一种格式（沿用现有 `events.jsonl` 的 JSONL，不必引新库）：

```json
{"ts": 1791270865178, "at": "10-06 14:34:25.178", "level": "warn",
 "domain": "lifecycle", "event": "process-killed",
 "reason": "LOW_MEMORY_KILLER", "pid": 1234, "process": "lobos.os",
 "detail": "reason=3 importance=125", "seq": 412}
```

必需字段：`ts`（毫秒）+ `level` + `domain` + `event` + `detail`。
`at`（可读时间）由 `ts` 派生，不让各处自己格式化 —— 这是"时间对不齐"的根因。

### 4.3 `KillAudit` 在新形态里

```
auditOnce(ctx)
  └ 读 getHistoricalProcessExitReasons
      └ 每条转成一个记录事件（domain=lifecycle, event=process-killed）
          └ 落盘；游标推进

attribution(sinceMs)   ← 只读，不再自己管落盘
  └ 从记录流里查 process-killed
```

### 4.4 通知与记录解耦

通知只留**当前状态摘要**（哪些程序在跑、活了多久），
死因、异常、诊断**一律不进通知** —— 它们在记录流里，用户要详情时看诊断页或导出。

---

## 五、整改顺序

| 序 | 事项 | 成本 | 依据 |
|---|---|---|---|
| 1 | `KillAudit` 去掉内存 `reading` 快路径，`attribution()` 直接查 Journal | 小 | 消掉"同一件事三个出口" |
| 2 | `kill-audit-cursor.txt` 并入 Journal 的游标管理 | 小 | 少一个文件、少一套状态 |
| 3 | `residency.txt` / `probe-journal.txt` 迁进 Journal | 中 | 消除两种私有格式 |
| 4 | `RuntimeDiagnostics` 三份文件合一（stderr 可单独留但归同一模块管） | 中 | 一个类写三种格式 |
| 5 | 记录事件补 `level` 字段（现只有 `seq`/`atMs`/`category`/`reason`/`detail`） | 小 | 遥测要按级别筛 |
| 6 | 一键导出记录包（给用户报障） | 中 | "我们能知道根因"的前提 |
| 7 | 遥测上报出口 | 中 | 结构统一后才有意义 |

第 1、2 条合计不到 30 行，做完 `KillAudit` 就从"独立模块"变成
"Journal 的一个事件来源"，位置自然归位。

---

## 六、待你决策的点

1. **保留多久？** 本地记录（诊断/探针/常驻）与事件流（`events.jsonl`）可以不同策略。
2. **要不要遥测上报？** 涉及隐私与用户知情，需要先定边界。
3. **`RuntimeDiagnostics` 的 `node-stderr.log` 要不要并进去？** 它是 node 的原始 stderr，
   量大且格式特殊，可能值得单独留但归同一模块管。
4. **记录包给用户主动导出，还是我们通过 adb 拉？** 前者要 UI，后者只需权限。
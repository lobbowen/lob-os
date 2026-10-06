# 运行记录：全仓清点

先说清楚**真实状态**，再说该怎么做。这份只写清点结果和事实，不含未经验证的判断。

清点工具：`tools/audit-runtime-logs.js`

---

## 一、定位

运行记录（也叫日志、审计、诊断）在系统里承担三件事：

| 用途 | 给谁 | 长什么样 |
|---|---|---|
| **状态** | 系统自己读 | 当前值（端口占用、程序注册表）。要 schema、要原子写、**不该有历史** |
| **记录** | 人 / 我们 | 发生过什么。追加写、有时间戳、该有保留期 |
| **导出** | 用户报障 / 遥测 | 从记录里取一段、结构化、带上下文 |

三者混在一起时就会出现"碎片"。这次清点就是为了把它们分开看。

---

## 二、全仓清点结果

### 2.1 磁盘写入点：51 处 / 28 个文件

按用途分：

| 类别 | 写入点 | 说明 |
|---|---|---|
| **状态** | 约 35 处 | 端口、注册表、程序状态、账本、会话、任务、设置、OTA 指针、权限台账 |
| **记录** | 约 14 处 | 见下表 |
| **工具** | `os/StateFiles.kt` 自身 | 原子写/轮转的实现，不是记录 |

### 2.3 实际链路：唯一数据源是 `Journal`，但有三份视图

`RuntimeDiagnostics.appendEvent()`（`RuntimeDiagnostics.kt:71`）一次调用写三处：

```
RuntimeDiagnostics.append(stage, ok, message, detail)   ← 45 种 stage / 113 处调用
   ├─→ diagnostics.txt        appendBounded    人读的文本视图
   ├─→ os/diag.jsonl          appendBounded    结构化视图
   └─→ Journal.append(...)    ← 数据源（events.jsonl）
```

**所以不是"两个标准并行"** —— `Journal` 是唯一数据源，
另两份是它的视图。我先前判断"两个标准体量相当"，错了。

### 2.4 谁在写 `Journal`

| 路径 | 写入者 | 调用点 |
|---|---|---|
| 直接 `Journal.append/note` | 18 个文件 | — |
| 经 `RuntimeDiagnostics` 转发 | 7 个文件 | 113 |
| `KillAudit.auditOnce` | 1 | 退出史落盘 |

`Journal` 的 23 个写入者里，18 个是直接写、7 个是经诊断转发。

### 2.5 `RuntimeDiagnostics` 的 45 种 stage 是什么性质

113 处调用的 stage 全是**某件事的结果快照**，不是流水事件：

```
screenshot(13) supervisor(10) nodeprobe(6) runtime(5) probe(5)
accessibility(5) health(5) bridge(4) boot(4) program-stderr(4) quickapp(3) …
```

带 `ok: Boolean?` 三态（OK/FAIL/INFO）与 `detail`。性质上接近"诊断快照"，
与 `Journal` 的"事件流"不同 —— **但它已经在写 Journal 了**，
所以这两者不冲突，只是同一份数据的两种粒度。

---

## 三、问题在哪（基于上面事实）

### 3.1 数据源是唯一的，但视图有四份

数据源只有一个：`os/journal/events.jsonl`。

但同一份数据被渲染成四份不同格式：

| 视图 | 文件 | 谁在读 | 轮转 |
|---|---|---|---|
| 事件流 | `events.jsonl` | Journal 的 23 个读取者 | ✅ 512KB/500 行 |
| 人读文本 | `diagnostics.txt` | 诊断页 | ✅（走 `appendBounded`） |
| 结构化诊断 | `os/diag.jsonl` | `RuntimeDiagnostics.events()` | ✅ |
| node 原始输出 | `node-stderr.log` | 诊断页 | 需单独确认 |

用户报障时我们捞的是**视图**，不是数据源。要看全貌得捞 4 份再对齐。

### 3.2 同一件事有多个出口

死因归因（"上次为什么没的"）现在涉及四处：

| 环节 | 落在哪 |
|---|---|
| 采集退出史 | `KillAudit.auditOnce` → `Journal.append`（**对，走标准模块**） |
| 去重游标 | `kill-audit-cursor.txt`（**另存**） |
| 快速查询 | 内存 `reading`（**绕过 Journal**） |
| 存活时间 | `residency.txt`（**私有格式，没进 Journal**） |

要查"上次怎么死的"，得同时理解这四处。

### 3.3 没有"导出"这一层

记录写完只有两种去处：本机用户看（引导页）、我们 adb 拉文件。

一次要捞的文件：

```
os/journal/events.jsonl        JSONL   ← 数据源
diagnostics.txt                 文本     ← 视图
os/diag.jsonl                   JSONL   ← 视图
node-stderr.log                 原始 stderr
probe-journal.txt               文本     ← 独立，未进 Journal
residency.txt                   自定义两行 ← 独立，未进 Journal
kill-audit-cursor.txt           单行两数字
```

**4 种格式，3 个数据源**（Journal / probe-journal / residency）。

### 3.4 遥测位置：现在没有，但要预留

你说遥测是下一阶段的事，位置要留好。

**留位置的关键是先把"导出层"定下来。** 遥测是导出层的一种消费方
（另一种是用户报障导出）。导出层没有，遥测就没有落点 ——
只能再去 3 个数据源各接一次。

所以顺序是：**收拢数据源 → 定导出层 → 接遥测**。
不能跳到遥测，那样会变成在 4 种格式上各接一次。

---

## 四、要达到的状态

```
   运行事件 ──┐
   （状态变化 / 探针结果 / 退出史 / 异常）──→  唯一记录入口  ──→  唯一落盘格式
                                                            ├─→ 本机查询
                                                            ├─→ 用户导出（报障）
                                                            └─→ 遥测（下一阶段，位置预留）
```

| 要点 | 具体 |
|---|---|
| **唯一记录入口** | 所有"发生过什么"都写同一个模块；不再有第二个写手 |
| **唯一落盘格式** | 一套字段 + 轮转策略；可读时间从时间戳派生，不让各处自己格式化 |
| **记录与状态分开** | 状态（当前值）与记录（历史）不混文件、不混模块 |
| **单一出口** | 查询、导出、遥测都从同一处取，不各自去拉文件 |
| **遥测位置预留** | 记录事件预留 `level`（现只有 `seq`/`atMs`/`category`/`reason`/`detail`），遥测要按级别筛 |

---

## 五、整改顺序

数据源已定（`Journal`），不需要再决策"用哪个当入口"。

| 序 | 事项 | 成本 | 依据 |
|---|---|---|---|
| 1 | `residency.txt` 与 `probe-journal.txt` 迁进 Journal | 小 | 这两个还没进数据源，是"同一件事多个出口"的残留 |
| 2 | `KillAudit` 去掉内存 `reading` 快路径，`attribution()` 查 Journal | 小 | 同上 |
| 3 | `kill-audit-cursor.txt` 并入 Journal 的游标管理 | 小 | 少一个状态文件 |
| 4 | 记录事件补 `level` 字段（现只有 `seq`/`atMs`/`category`/`reason`/`detail`） | 小 | 遥测要按级别筛 |
| 5 | 定义导出层：一次捞齐、格式统一、带设备与版本元信息 | 中 | 用户报障与遥测共用 |
| 6 | 遥测出口（下一阶段） | — | 位置在第 5 条留好 |

第 1-3 条做完，"同一件事多个出口"就清干净了，合计不到 50 行。

**关于那三份视图（`diagnostics.txt` / `os/diag.jsonl` / `node-stderr.log`）**：
它们是数据源的渲染，**可以保留** —— `diagnostics.txt` 给人读，
`diag.jsonl` 给 `events()` 读。只要导出层直接从 `Journal` 取，
视图不轮转就不会成为问题（它们是快照型内容，不是流水）。

**三份视图全部有界，不会无限增长**（已逐一核实）：

| 视图 | 写入方式 | 上限 |
|---|---|---|
| `diagnostics.txt` | `StateFiles.appendBounded` | 512KB / 500 行 |
| `os/diag.jsonl` | `StateFiles.appendBounded` | 512KB / 500 行 |
| `node-stderr.log` | `recordNodeStderr` → `appendBounded` | 512KB / 500 行 |
| `events.jsonl`（数据源） | `Journal` 自己的 `rotate` | 512KB / 500 行 |

**所以视图层不需要整改** —— 它们有轮转、只是数据源的渲染，
导出层直接从 `Journal` 取即可。

**清理工作小结（第五节）：** 剩下的只有把三个还在数据源外面的
（`residency.txt`、`probe-journal.txt`、`kill-audit-cursor.txt`）收进来，
加上定义导出层。

---

## 六、已查清与未查清

### 已查清（逐条核实过）

1. **`Journal` 是唯一数据源。** `RuntimeDiagnostics.appendEvent` 一次调用同时写
   `diagnostics.txt`、`os/diag.jsonl`、`Journal.append` 三处，前两者是视图。
2. **三份视图全部有界。** 都走 `StateFiles.appendBounded`，上限 512KB / 500 行。
3. **`Journal` 没有混状态。** 32 种 category 全是事件名；
   `ports`/`index`/`settings`/`registry`/`state` 这几个看着像状态的，
   实际记的是"变了"这件事 —— 如 `"claim " + owner + " -> " + port`、
   `"upsert " + id + " desired=RUNNING"`、`"程序设置被拒（键不在白名单…）"`。
4. **`ResidencyAudit` / `KillAudit` 不写记录**，它们读写的是状态
   （`residency.txt` 存活时间、`kill-audit-cursor.txt` 游标）。
5. **`KillAudit` 的采集链路是对的** —— 退出史经 `Journal.append` 落盘。

### 未查清（需要真机数据，当前拿不到）

adb 通道已断：`connect ECONNREFUSED 127.0.0.1:44851`。
端口在宿主 `lobos.app` 重启后会变，重连要走无线配对（需在设备上确认 6 位码）。

**待取的两项数据：**

1. **各记录文件的实际体积** ——
   理论上界已知（512KB / 500 行），但"用户实际会不会撞到上界"要跑一段时间才知道。

2. **写入频率** ——
   `Journal` 有 32 种 category，若某个每秒写几条，500 行很快被刷掉，
   **关键的 `kill-audit` 事件可能被冲掉**。
   这条直接决定保留期该定多少行/多少字节，也可能需要按 category 差异化保留。

**第 2 条风险最实际**：死因归因是我们排查常驻失败的唯一证据，
如果它比高频日志更早被轮转掉，这个模块的价值就打折。
接回 adb 后第一件事应该是看 `events.jsonl` 里 `kill-audit` 的实际留存条数。
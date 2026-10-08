# 标准操作系统的注册表 —— 权威对照

> 来源（2026-10-08 实查）：
> - `systemd.unit(5)` https://www.freedesktop.org/software/systemd/man/latest/systemd.unit.html
> - `dpkg-query(1)` https://manpages.debian.org/bookworm/dpkg/dpkg-query.1.en.html
>
> 本文只写**官方文档明写的事实**，以及它们与 `docs/REGISTRY-SHAPE.md` 的差异。
> 推断的部分会标明「这是我的推断」。

---

## 一、dpkg：包数据库的形态

### 1.1 数据落在哪

`/var/lib/dpkg/status` —— 一个文件，**所有已装包**在里面。
另有 `/var/lib/dpkg/available`（可装但未装的）。

### 1.2 每条记录有什么（官方字段名）

`dpkg-query(1)` 的 `--showformat` 列出全部可查字段。按用途归类：

| 类别 | 字段 |
|---|---|
| **身份** | `Package`（含架构后缀 `binary:Package`）· `Version` · `Architecture` · `Source`（源码包名）· `source:Version` |
| **依赖** | `Depends` · `Pre-Depends` · `Conflicts` · `Breaks` · `Replaces` · `Provides` · `Recommends` · `Suggests` · `Enhances` |
| **装到哪** | `db-fsys:Files`（装出来的文件清单）· `Conffiles` · `Installed-Size` |
| **谁维护** | `Maintainer` · `Homepage` · `Bugs` |
| **状态** | `Status`（拆成 `db:Status-Want` / `db:Status-Status` / `db:Status-Eflag` 三段） |
| **分类** | `Section` · `Priority` · `Essential` · `Protected` |
| **来源** | `Filename`（.deb 在哪）· `MD5sum` · `Size` · `Tag` |

### 1.3 三条设计要点

**① 状态是三段，不是一个枚举**

`Status` 字段被拆成三个独立的虚拟字段：
- `db:Status-Want` —— 想要什么（`u`/`i`/`h`/`r`/`p`：unknown/install/hold/remove/purge）
- `db:Status-Status` —— 实际到哪一步了（`n`/`c`/`H`/`U`/`F`/`W`/`t`/`i`：not-installed/config-files/half-installed/unpacked/half-configured/triggers-awaiting/triggers-pending/installed）
- `db:Status-Eflag` —— 有没有问题（`R`：reinst-required）

**一个字段说三件事，所以官方提供三个虚拟字段分开查。**

**② `Multi-Arch` + 架构限定名**
`binary:Package` 带架构后缀（`libc6:amd64`），只在 `Multi-Arch: same/foreign` 时出现 ——
为了让包名无歧义。

**③ 文件清单是数据库的一部分**
`db-fsys:Files` + `db-fsys:Last-Modified`（1.19.3 加的）——
**「这个包装了哪些文件」被登记下来，不靠现场扫盘。**
`dpkg -S <file>` 反查「这个文件属于哪个包」就是查这张表。

---

## 二、systemd：unit 的形态

### 2.1 数据落在哪

不是单个数据库，而是**一组目录 + 每个 unit 一个 ini 格式文件**：

```
/etc/systemd/system/       管理员创建的
/run/systemd/system/       运行时的
/usr/lib/systemd/system/   发行版包管理器装的
```
按优先级叠加，前面的覆盖后面的。

### 2.2 三种扩展机制

| 机制 | 做法 | 用途 |
|---|---|---|
| **drop-in** | `foo.service.d/*.conf`，按文件名字母序合并 | 改配置不用改主文件 |
| **`.wants/` 目录** | 目录里所有 unit 文件隐式成为 `Wants=` 依赖 | 把 unit 挂进别人的启动流程 |
| **`.requires/` 目录** | 同上，类型是 `Requires=` | 必须先起的那些 |
| **mask** | 软链到 `/dev/null`，或空文件 | 彻底禁用，「连手动都起不来」 |

### 2.3 关键字段（官方明写的）

**依赖**（在 `[Unit]` 段）：

| 字段 | 含义 |
|---|---|
| `Requires=` | 需要（对方挂了自己也停） |
| `Wants=` | 想要（对方挂了照跑） |
| `Requisite=` | 必须已经存在 |
| `BindsTo=` | 绑得更紧（对方消失/超时立即停） |
| `After=` / `Before=` | **顺序**，不是依赖 |
| `PartOf=` | 启停传播 |

官方明确说了 `After=`/`Before=` 与 `Requires=`/`Wants=` **是正交的两件事**：
「这两组设置彼此独立，与 `Requires=`/`Wants=`/`Requisite=`/`BindsTo=` 的要求是正交的」。

**条件与断言**（`[Unit]` 段）：

`Condition…=` 不满足 → **静默跳过启动**，不进 `failed`
`Assert…=` 不满足 → **启动失败**，进 `failed`
`ConditionPathExists=` · `ConditionArchitecture=` · `ConditionVirtualization=` · `ConditionHost=` · `ConditionPathIsSymbolicLink=` …

**重要区分**（官方原文）：条件/断言**都不导致状态变更**，
且它们在 job 真正要执行时才检查 —— 所以不适合用来做条件化的依赖。

**启动频率限制**：

| 字段 | 作用 |
|---|---|
| `StartLimitIntervalSec=` | 检查窗口 |
| `StartLimitBurst=` | 窗口内最多起几次 |
| `StartLimitAction=` | 超限后做什么 |

官方明确说它与 `Restart=` 配合用，但**对所有启动都生效**（含手动启动）。

**失败/成功动作**：

`FailureAction=` · `SuccessAction=` —— 取值 `none`/`reboot`/`poweroff`/`halt`/`kexec`/`exit`/`soft-reboot` 及各自的 `-force`/`-immediate` 变体。

**其它**：
- `JobTimeoutSec=`（排队等状态的超时，与 unit 自己的 `TimeoutStartSec=` 正交）
- `CollectMode=` —— 卸载时（垃圾回收）连 `failed` 状态一起清，还是保留到用户 `reset-failed`
- `StopWhenUnneeded=` · `RefuseManualStart=` · `RefuseManualStop=` · `AllowIsolate=`
- `SurviveFinalKillSignal=` —— 关机最后阶段不给 SIGTERM/SIGKILL
- `SourcePath=` —— 这份配置是从哪个文件生成来的（给 generator 用）

### 2.4 两条设计要点

**① 顺序（`After=`）与依赖（`Requires=`）分开**

这是官方反复强调的。我们现在只有 `requires` + `deps`，**没有顺序概念**。

**② 「静默跳过」与「失败」是两种不同的结果**

`Condition` 跳过 → 干净状态，可被垃圾回收，**查询时可能看不出**（官方原话：
「the condition failure may or may not show up in the state of the unit」）。
`Assert` 失败 → 进 `failed`。

我们现在的 `invalid` 字段更接近 `Condition`（记录原因但不失败）。

---

## 三、跟我们 `REGISTRY-SHAPE.md` 的差异

| 维度 | dpkg | systemd | 我们现在 | 结论 |
|---|---|---|---|---|
| **落哪** | 单文件 `status` | 多目录 + 每 unit 一文件 | 单个 `program-index.json` | 可保持 |
| **一个单元的身份** | 包名+架构+版本+源码包 | unit 名 + 类型后缀（`.service`/`.socket`…） | `id` + `level` | **缺「类型后缀」这一层** |
| **状态** | 三段（want/status/eflag） | 状态机（`Run` 枚举）+ `desired` | `desired` 一个字段 | **我们把三件事糊在一个字段** |
| **依赖** | 9 种（Depends/Conflicts/Breaks/Replaces/Provides…） | 5 种（Requires/Wants/Requisite/BindsTo/PartOf）+ 顺序 | `requires` + `deps` | **缺顺序、缺「谁挂了会怎样」** |
| **条件** | — | `Condition`（跳过）vs `Assert`（失败） | `invalid` 一个字段 | **两者合一** |
| **文件清单** | `db-fsys:Files` 登记 | —（unit 不管文件） | 无 | **我们也没有** |
| **启动频率限制** | — | `StartLimitInterval`+`Burst`+`Action` | `maxRestarts` + `backoffMs` | 接近，可补 `Action` |
| **扩展机制** | — | drop-in / `.wants/` / mask | 无 | **缺，但也不急着要** |

---

## 四、三条我认为值得抄的（按性价比排序）

### ① 状态拆成三段（dpkg 的 `Status`）

**现在**：`desired: RUNNING|STOPPED|FROZEN` 一个字段，既像「期望」又像「实际」。

**抄**：拆成

```
want:  install | hold | remove | purge      ← 用户/系统想要什么
state: unpacked | installed | failed | …     ← 实际到哪一步
eflag: reinst-required                       ← 有没有问题
```

**为什么值**：我们现在回答不了「用户要停它，但它正在停（还没停掉）」这种中间态 ——
因为 `desired` 一个字段装不下。

### ② 顺序与依赖分开（systemd 的 `After=` vs `Requires=`）

**现在**：只有 `requires`（要什么），没有「谁先谁后」。

**抄**：加一个顺序关系字段。

**为什么值**：`git` 要 `bash` 与 `openssl`，但**谁先谁后**现在没表达 ——
装的时候靠顺序碰巧对，跑的时候靠不管。

### ③ 文件清单登记（dpkg 的 `db-fsys:Files`）

**现在**：不知道某个文件是哪个件带来的。

**抄**：装完登记「这一批字节铺了哪些路径」。

**为什么值**：删一个件时，现在无法精确删除它铺的文件；也无法回答「`/usr/bin/curl` 是哪个版本铺的」。

---

## 五、我**不**建议抄的

| systemd 的 | 为什么不抄 |
|---|---|
| drop-in（`.d/*.conf`） | 我们只有一份配置，没有「多方配置同一件」的需求 |
| unit 模板（`foo@inst.service`） | 我们的件不需要多实例 |
| `Condition…=` 一大族 | 我们的环境在 Android 上，架构/虚拟化等条件恒定 |

| dpkg 的 | 为什么不抄 |
|---|---|
| `Pre-Depends` / `Recommends` / `Suggests` / `Enhances` | 我们不做「可选依赖」分级 —— 没有选装概念 |
| `diverted-by`（文件转移） | 单文件系统的场景用不上 |
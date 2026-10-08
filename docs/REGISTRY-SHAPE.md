# 注册表最终结构

> 本文只写**核实到的事实**与**要改成什么**，不写实现。
> 每条结论都能追到 `文件:行号`。核实日期 2026-10-08。
> 配套：`docs/REGISTRY-DESIGN.md`（为什么这么改）

## 一、注册表是什么

**装进系统的每一个东西，运行时会用到的、装的时候定下来的信息。**

- 装一个 → 登记一条（`ProgramIndex.upsert`）
- 运行时 → 从这里读（不硬编码、不问包、不猜）
- 卸载/回滚 → 改这里

不是「件的清单」（那是包的事），是**「装了什么、它怎么跑、装的时候定了什么」**。

## 二、判据：什么该进，什么不该进

| 该进 | 不该进 |
|---|---|
| **装的时候定下来、之后不变** | **跑起来之后才变的** |

跑起来才变的（**已在 `ProgramStatus` 里，不进注册表**）：

| 字段 | 是什么 |
|---|---|
| `state` | 进程真的在跑还是没跑（`ProgramStateMachine.Run`） |
| `supervised` | 有没有被托管 |
| `startedAtMs` / `aliveMs` | 起了多久 |
| `restarts` | 重启了几次 |

---

## 三、结构

```
IndexEntry
│
├─ 身份 ──────────────────────────────────────────────
│  id                    这个东西叫什么（唯一键）
│  level                 它是什么 ── 唯一的判别字段
│  version               装的是哪一版（装完问它自己）
│  sha256                这一批字节的身份（内容寻址）
│  stateDir              它的文件在哪（相对 filesDir）
│  tier                  构建档位：self-c / upstream / soft
│
├─ 运行契约 ──────────────────────────────────────────
│  entry                 怎么启动它（相对 stateDir）
│  args                  启动参数
│  env                   给它的环境变量
│  libName               它在包里的文件名（内核件）
│  assetEntry            落到 $PREFIX 的名字（内核件）
│
├─ 依赖 ──────────────────────────────────────────────
│  requires              它要哪些东西（装它之前得先有）
│  deps                  它连带装了哪些（实际拉下来的）
│  capabilities          它向系统提供什么
│
├─ 恢复策略 ──────────────────────────────────────────
│  resident              是不是常驻
│  restart               挂了怎么起：on-failure / never / always
│  maxRestarts           最多起几次
│  backoffMs             退避间隔序列
│
├─ 控制意图 ──────────────────────────────────────────
│  desired               用户/系统想要的状态：RUNNING / STOPPED / FROZEN
│
├─ 校验 ──────────────────────────────────────────────
│  invalid               这一条为什么不合法（装的时候校验出来的）
│
├─ ui{} ──────────────────────────────────────────────  ← 只有 level=APPLICATION 有
│  uiPackage             桌面图标点开走哪个快应用
│  uiName                桌面图标显示的名字
│  uiIcon                桌面图标资源
│  onUiClosed            窗口关掉时：keep-alive / stop-with-ui / on-demand
│
└─ http{} ────────────────────────────────────────────  ← 只有 level=APPLICATION 有
   httpPort              健康检查打在哪个端口（0 = 系统分配）
   httpHealth            健康路径
```

---

## 四、`level` 的三个值 —— 唯一的判别

| 值 | 是什么 | 装哪、怎么跑 |
|---|---|---|
| **`INFRA`** | 内核件：库、原生桥、PTY | 落 `$PREFIX/lib` 与 `$PREFIX/bin`，被 `dlopen` / `exec`；不托管 |
| **`CAPABILITY`** | 能力件：命令、工具、运行时 | 落 `$PREFIX/bin`，被 `exec`；不托管 |
| **`APPLICATION`** | 应用（快应用） | 落 `stateDir`，被进程托管；有恢复策略与桌面图标 |

```
判别方法：看 ui{} 有没有值 —— 有就是 APPLICATION，没有就是内核件。
```

**唯一的判别字段**：`managed = (level == APPLICATION)`（`ProgramIndex.kt:44`，已在代码里）。

---

## 五、`desired` 是什么（更正我上一轮的说法）

我上一轮说它是「期望」并想改名 —— **那是错的**。

`CapabilityBroker.kt:718`：

```kotlin
val desired = if (running) Desired.RUNNING else Desired.STOPPED
```

它与「实际跑着没」有确定关系。三个值**都在用**：

| 值 | 使用处 | 核实 |
|---|---|---|
| `RUNNING` | 8 处 | `CapabilityBroker` `ProgramStatus` |
| `STOPPED` | 10 处 | 含 `BootReconciler.kt:101`（版本对不上时强制停） |
| `FROZEN` | 3 处 | `ProgramStateMachine.kt:34,53,54` 拿它判状态迁移：「冻结意图不该进入隔离 / 不该处于活跃态」 |

**所以它是一个合法状态枚举，不该改名，也不该删。** 分层上它属「控制意图」
（用户能改）而不是「身份」或「契约」。

---

## 六、删掉的字段（连同理由）

| 删什么 | 为什么 |
|---|---|
| `category` | 纯冗余：只有 2 处赋值，且都是 `if (level == APPLICATION) …`。而 `RUNTIME`/`TOOLCHAIN`/`LIBRARY` 三个值**从未被赋值**，只在解析函数里出现 |
| `asApplication` | 纯冗余：等于 `level == APPLICATION`，而 `managed` 已经是这个判断。它是我上一轮为删 `origin` 引入的，等于把 `origin` 换名重写 |
| `Level.CHANNEL` | 迁移遗留：只在 `ProgramMigration` 出现（迁移时统计「通道=几件」），运行时代码无处判 |

**保留** `invalid`：它是装的时候校验包得出的（`ProgramStatus.kt:115` 判
`spec.invalid == null` 得出 `manifestValid`），属「装的时候定的」，该进表。

**保留** `Desired.FROZEN`：它在 `ProgramStateMachine` 里真在用
（判状态迁移），不是死值。

---

## 七、每层的填写时机

| 层 | 谁填 | 什么时候 |
|---|---|---|
| 身份 | 安装流程 | 装的时候（`version`/`sha256` 是**装完**对落位文件实算/实问） |
| 运行契约 | 安装流程 | 从**包内清单**读进表 |
| 依赖 | 安装流程 | `requires` 包声明；`deps` 实际拉下来的 |
| 恢复策略 | 安装流程 | 从包内清单读 |
| 控制意图 | 用户 / 系统 | 装了之后可改（`ProgramManager.setDesired`） |
| 校验 | 安装流程 | 装的时候校验包得出 |
| ui{} / http{} | 安装流程 | 从包内清单读（`QuickAppRegistry.kt:36-45` 已在做） |

---

## 八、回到你之前问的两个问题

> 「期望和守护是什么玩意？」

- 「守护」是我硬套 systemd 的词 → 它是**恢复策略**（挂了怎么起），已改名。
- 「期望」是我硬套 systemd 的词 → 它是 `desired`（RUNNING/STOPPED/FROZEN），
  真在用，不改名，只是分层上归到「控制意图」。

> 「件的逻辑还要单独每个件写一堆文件」

按这个形态**不需要**。一件只需要：
- 装的时候被登记（一条记录）
- 想被问版本时提供 `--version` 之类的参数（它自己的事）

内核不写任何「某件特有」的逻辑。
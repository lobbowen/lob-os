# 注册表结构收拢方案

> 本文只写**核实到的事实**与**要改成什么**，不写实现。
> 每条结论都能追到 `文件:行号`。核实日期 2026-10-08。

## 一、问题

`ProgramIndex.IndexEntry` 是 28 个字段的一张平表，其中**三个字段在表达同一件事**：
「这是不是应用」。

```
level         INFRA / CAPABILITY / CHANNEL / APPLICATION
category      RUNTIME / TOOLCHAIN / LIBRARY / APPLICATION / NONE
asApplication Boolean
```

而且快应用专属的字段（`ui*` / `http*`）与内核字段（`libName` / `tier` / `assetEntry`）
平铺在同一层，**看表本身分不出谁是谁**。

---

## 二、核实结果

### 2.1 `category` —— 纯冗余，可删

| 事实 | 出处 |
|---|---|
| 只有 2 处赋值，且**都是从 `level` 推出来的** | `ProgramIndex.kt:93`：`category = if (level == Level.APPLICATION) Category.APPLICATION else Category.NONE`<br>`ProgramMigration.kt:109`：`category = Category.APPLICATION` |
| `RUNTIME` / `TOOLCHAIN` / `LIBRARY` 三个值**从未被赋值** | 只出现在两个解析函数里（`ProgramIndex.kt:66-70`、`ProgramMigration.kt:84-86`） |
| 真实读取只有 2 处 | `CapabilityBroker.kt:468`（`put("type", e.category)`）、`:650`（按 type 计数） |

**同名不同义的干扰**：`log/Journal.kt`（8 处）与 `log/KillAudit.kt`（1 处）也有
`category` 字段，但那是**日志分类**（字符串，如 `"index"`、`"native-ota"`），
与注册表的 `Category` 枚举无关。**删的时候不能碰这两个文件。**

### 2.2 `asApplication` —— 纯冗余，可删

| 事实 | 出处 |
|---|---|
| 它表达的是「是不是应用」 | 与 `level == APPLICATION` 同义 |
| 而 `IndexEntry` **自己已有**这个判断 | `ProgramIndex.kt:44`：`val managed: Boolean get() = level == Level.APPLICATION` |
| 使用点 4 处 | `ProgramIndex.kt`（声明/序列化/反序列化）、`ProgramManager.kt`、`ProgramMigration.kt`、`ProgramInstallPipeline.kt` |

**注**：`asApplication` 是我在上一轮为了删 `origin` 时引入的，等于把 `origin`
的语义换了个名字重写一遍。删掉它，`managed` 就是唯一判据。

### 2.3 `Level.CHANNEL` —— 迁移遗留，可删

只出现在 `ProgramMigration.kt`（第 61、80、85、175 行），用于迁移旧格式时统计
「通道=几件」。**运行时代码没有任何一处判它。**

### 2.4 快应用字段 —— 全在服务快应用，一个都不能删

我上一轮曾判 `ui*` + `onUiClosed` 为「死字段」，**那是错的**，现已核实：

| 字段 | 读取处 | 用途 |
|---|---|---|
| `uiPackage` | `QuickAppRegistry.kt:63` | 过滤出快应用 |
| `uiName` | `PanelActivity.kt:289` | 桌面图标显示名 |
| `uiIcon` | `PanelActivity.kt:292` | 桌面图标 |
| `onUiClosed` | 包内清单 → `QuickAppRegistry.kt:41` 写入 → `ProgramIndex.kt:149` 序列化 → `:198` 反序列化 → `CapabilityBroker.kt:912` 报出 | 窗口关闭策略（`keep-alive` / `stop-with-ui` / `on-demand`） |

### 2.5 `http*` 字段 —— 快应用专用，内核件用不到

`httpPort` 13 处 · `httpHealth` 8 处，横跨 `ProgramDir` `ProgramManager`
`ProgramMigration` `QuickAppRegistry` `GuestAdapter` `InstanceHost` `PanelActivity` `CapabilityBroker`。

---

## 三、要改成什么

### 3.1 判别收敛为一个字段

```
现在：level + category + asApplication     三个字段说同一件事
之后：level                                  唯一判别
```

`level` 保留三个值：

| 值 | 含义 | 谁用 |
|---|---|---|
| `INFRA` | 内核件（库、原生桥） | `BootReconciler` `ProgramManager` |
| `CAPABILITY` | 能力件（命令、工具） | `PackageInstaller` |
| `APPLICATION` | 应用（快应用机制） | `ProgramStatus` `SupervisorPool` `QuickAppRegistry` |

删 `Category` 枚举、`CHANNEL` 分支、`asApplication` 字段。
`CapabilityBroker:468/650` 改成用 `level.name`（它本来只是想给桥接一个类型标识）。

### 3.2 字段按用途分层

```
IndexEntry
├── 身份        id · level · version · sha256 · stateDir · tier
├── 运行契约    entry · args · env · role · resident · restart
│               maxRestarts · backoffMs · requires · deps · capabilities · libName · assetEntry
├── 期望        enabled · desired · invalid
├── ui{}        uiPackage · uiName · uiIcon · onUiClosed      ← 只有 APPLICATION 有
└── http{}      httpPort · httpHealth                        ← 只有 APPLICATION 有
```

**效果**：看表本身就知道谁是谁 —— **有 `ui` 就是应用，没有就是内核件**。
不需要再猜哪个字段是判别用的。

### 3.3 两个子对象与「装完就登记」对齐

`ui{}` / `http{}` 的值在装的时候从**包内清单**读进注册表（`QuickAppRegistry.kt:36-45`
已经在这么做了），内核运行时从注册表读。**这与包外那份清单无关**（包外只回答
「有没有新版可装」，见 `docs/EXECUTION-PLAN.md`）。

---

## 四、改动清单（按依赖排序）

| 步 | 改什么 | 文件 | 风险 |
|---|---|---|---|
| 1 | 删 `asApplication`，`managed` 成为唯一判据 | `ProgramIndex` `ProgramManager` `ProgramMigration` `ProgramInstallPipeline` | 低（纯替换，语义已由 `managed` 覆盖） |
| 2 | 删 `Category` 枚举与 `category` 字段；`CapabilityBroker:468/650` 改用 `level.name` | `ProgramIndex` `ProgramMigration` `CapabilityBroker` | 低（**不碰 `log/Journal` 与 `log/KillAudit`**，它们的 category 是日志分类） |
| 3 | 删 `Level.CHANNEL` 与相关统计 | `ProgramMigration` | 低 |
| 4 | `ui*` → `ui{}` 子对象 | `ProgramIndex` `QuickAppRegistry` `PanelActivity` `CapabilityBroker` `ManifestSchema` | 中（5 个文件的读写形状变了） |
| 5 | `http*` → `http{}` 子对象 | 上列 + `ProgramDir` `ProgramManager` `ProgramMigration` `GuestAdapter` `InstanceHost` | 中高（8 个文件） |

**建议 1→2→3 先做**（都是纯删除/替换，改完「判别只有一个字段」就成立），
4、5 单独评估 —— 它们是「字段分层」，让表自解释，但改动面大三倍。

---

## 五、验证方式

**本机验不了运行**（`packBundle` 的签名要构建期私钥 `keys/ota-private.pem`，仓内没有）。
可用：

| 门禁 | 查什么 |
|---|---|
| `tools/check-kt-structure.js` | Kotlin 结构（括号配平、悬空参数） |
| `tools/ktcheck.js` | 宿主 Kotlin 自检 |
| `tools/check-api-spec.js` | 若字段进 ApiSpec 会核到 |
| 人工：`grep -rn '<字段名>' --include='*.kt'` | 确认改动后的读写点都换了 |

**新增门禁建议**（防再次漂出三个判别字段）：

```
判别字段必须唯一 —— IndexEntry 里出现第二个表达「是不是应用」的字段就判红。
判据：level 之外不得有别的字段被用来做 level == APPLICATION 的等价判断。
```

---

## 六、本方案不做的事

- 不动 `ui*` / `http*` 的**语义**（它们是对的，只改归属层级）
- 不动 `env` / `entry` / `args`（运行契约已在注册表，机制正确）
- 不引入新表、新机制、新层
- 不碰 `log/Journal` 与 `log/KillAudit` 的 `category`（同名不同义）
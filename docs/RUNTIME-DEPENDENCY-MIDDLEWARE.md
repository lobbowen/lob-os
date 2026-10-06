# 运行时依赖与安装：宿主 / 程序 / 系统组件的关系

你问的是："我启动系统，为什么还要有 node？宿主跟 node 什么关系？"

**答案：宿主本身不需要 node。**

---

## 一、三者的真实关系

```
LobOS 宿主（APK 里的壳）
  ├─ 界面、控制面板、引导页           ← 纯 Kotlin
  ├─ 安装管理器、注册表、端口、进程账本  ← 纯 Kotlin
  └─ 快应用容器（dimina）               ← 用容器自带的 JS 引擎
                    ↑ 都不需要 node

某个程序（快应用的后端，JavaScript）
  └─ 它的宿主进程是 node                ← 这里才需要 node
```

**node 是"程序的运行时"，不是"系统的运行时"。**

实测（代码级核实，不是推断）：

| 层 | 文件 | node 引用 |
|---|---|---|
| 宿主 | `OsApplication.kt` | 0 |
| 宿主 | `MainActivity.kt` | 0 |
| 控制面板 | `ui/PanelActivity.kt` | 0 |
| 引导页 | `ui/setup/SetupActivity.kt` | 0 |
| 快应用容器 | `quickapp/QuickAppHost.kt`（只 import dimina） | 0 |
| 诊断 | `RuntimeDiagnostics.kt` / `ProvisioningProbe.kt` | 0 |
| **程序宿主** | `runtime/InstanceHost.kt` | **有**（349/807/862 行） |

`InstanceHost` 是**程序宿主**，不是系统宿主 —— 它用 node 起每个程序的进程。
所以"没有 node"的准确后果是：

- 宿主、控制面板、引导页、快应用界面：**一切正常**
- 用 JS 写的程序：**跑不起来**

之前那句 `SupplyProvisioner.ensure` 在 App 启动时自动装 node，
就是把"程序的运行时"当成了"系统的启动依赖"—— 这就是硬编码的本质。

---

## 二、你指出的问题：这是一类，不只是 node

现在代码里有三类硬编码，性质相同：**把"某个具体件的属性"写进了底层逻辑。**

| # | 硬编码 | 位置 | 造成过什么后果 |
|---|---|---|---|
| 1 | node 需要同目录 `.so` | `PrefixProvisioner.placeNodeDeps` 专门给它开后门 | `$ORIGIN` linker 失败，真机撞过 |
| 2 | node 的名字与路径 | `NodeRuntime.NAME = "node"`、`ProgramManager` 里拼 `bin/node` | 换名字/换路径要改多处 |
| 3 | 七个工具链 | `NativeAssetRegistry` 与 `components/` 逐个登记 | 商店上新要改代码 |

第 1 条是最要命的：**因为安装器不认识"这类包需要同目录依赖"这条通用规则，
才不得不为 node 单独写一段补依赖的代码。** 换成 curl、git 或任何带
`$ORIGIN` 的件，同样的坑要再踩一次。

---

## 三、根因：目录项没有表达"包需要什么"的字段

商店目录项现在是自由 JSON（`CatalogClient.normalize` 只补 `kind` 与 `name` 两个默认值），
安装器读的是：

```
url / sha256 / entry / aliases / version / kind / deps / tier
```

**缺的正是"这个包落地后还需要什么"。** 所以安装器只能猜，猜不准就出事故。

需要的字段（包自己声明，不是安装器认识某个具体包）：

| 字段 | 含义 | 谁填 |
|---|---|---|
| `runpathOrigin: true` | 本件的 ELF `DT_RUNPATH` 是 `$ORIGIN`，依赖必须与它在同一目录 | 发布方 |
| `sharedLibs: [...]` | 需要一并放在 `$ORIGIN` 的 `.so` 列表 | 发布方 |
| `runtimeFor: "application"` | 本件是"给程序用的运行时"，装它不需要先有程序 | 发布方 |
| `globalCommand: true` | 装完建全局入口（`usr/bin/<name>`），程序不需要 | 发布方 |
| `provides: [...]` | 本件提供的能力标识（`node.version` 等），消费方按标识取 | 发布方 |

---

## 四、要建的中间件

你要求"有一个中间件来处理这些东西"。缺的是这一层：

```
商店目录（包自己声明 runpathOrigin / sharedLibs / runtimeFor / provides）
        ↓
CatalogClient.entryFor(id)                    取目录项
        ↓
═══ 安装中间件（新增）══════════════════════════
  1. 取件              按 kind 决定落位形态
  2. 铺依赖            runpathOrigin=true → 把 sharedLibs 放同目录   ← 通用，不再认识 node
  3. 建入口            globalCommand=true → usr/bin/<name> 软链
  4. 登记              ProgramIndex：kind / version / provides
  5. 配对              快应用才走 QuickAppBinder
═══════════════════════════════════════════════
        ↓
ProgramIndex（注册表）+ $PREFIX 入口
```

**关键：中间件只读目录项里的字段，不认识"node"这个词。**
以后 curl/git/sqlite3 要同目录依赖，发布方在目录里写 `runpathOrigin: true` 即可，
安装器代码一行不改。

---

## 五、这轮已经做完的（不用重做）

| 事项 | 状态 |
|---|---|
| node 从商店目录下载 | ✅ 走 `kind: runtime`，不随 APK |
| 走统一安装管理器 | ✅ `ProgramInstallPipeline` 一个安装点 |
| 落位 + 登记 + 建入口 | ✅ pipeline 里都有 |
| App 启动不再自动装 node | ✅ `SupplyProvisioner.ensure` 已删 |
| 安装路径不再依赖 node | ✅ 校验已移到 Kotlin，鸡生蛋解开 |

**剩下的是"装完即完全可用、且不靠底层认识 node"** —— 也就是上面第三节那三类硬编码。

---

## 六、待你定的一件事

`runpathOrigin` / `sharedLibs` / `provides` 这些字段加在**商店目录项**里，
由发布方（我们自己的发布脚本）填。

要确认的是：**发布侧现在发的那份 `userland-manifest-2.json` 里，
node 那一项要不要现在就把这些字段补上？** 补了之后，
`PrefixProvisioner.placeNodeDeps` 这个后门就能删掉。

补字段是发布侧的事（写 JSON），代码侧只改安装中间件。
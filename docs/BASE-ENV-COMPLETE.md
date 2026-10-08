# 基础环境件清单（当前口径 · 2026-10-08）

本文是**基础环境件的唯一依据**。

- 分类与版本从 `scripts/component-sources.json`（钉值表）读出并逐件核实
- 每件的现状都核到具体文件行
- 与旧文档（`BASE-ENV-DESIGN.md` / `BASE-ENV-INVENTORY.md` / `BASE-ENV-DECISIONS.md` /
  `BASE-ENVIRONMENT.md`）**冲突处以本文为准** —— 那些是讨论过程，其中已被后续决定推翻的部分不再有效

**原则（已定）**：基础环境件与程序、运行时、工具走**同一套逻辑** —— 登记在同一张注册表里，
经控制面板分发，随 APK 首发内置、可 OTA 更新。**不存在「APK 原生件」这个独立类别。**

---

## 一、基础环境件（13 件）

### 命令（`$PREFIX/bin`，进全局 PATH）

| 件 | 作用 | 版本 | 现状 |
|---|---|---|---|
| `bash` | 命令解释器 | **5.2.15** | ✅ `NativeAssetRegistry.kt:35`（`libbash.so`） |
| `busybox` | 多命令工具（tar/gzip/grep/sed/ls/cp/mv…） | **1.36.1** | ✅ `NativeAssetRegistry.kt:80`（`libbusybox.so`） |
| `ripgrep` | 快速搜索（glob/grep） | ⚠️ **钉值表无此项** | ✅ `:43`（`liblobosrg.so`）**无版本号 → OTA 更新不了** |
| `jq` | JSON 处理 | **1.8.2** | ⚠️ 已编（`build-jq.yml`）**但不在 `NativeAssetRegistry`** |

### 库（`$PREFIX/lib`，进全局库搜索路径）

| 件 | 作用 | 版本 | 现状 |
|---|---|---|---|
| `libc++_shared.so` | C++ 运行库 | NDK 30.0.16248370 | ✅ `:9`（NDK 自带，`required=true`） |
| `libssl.so` | TLS | **3.6.3** | ✅ `:99` |
| `libcrypto.so` | 加密 | **3.6.3** | ✅ `:106` **但无版本号 → OTA 更新不了** |
| `libz.so` | 压缩 | **1.3.2** | ✅ `:92` |
| `libcurl.so` | HTTP 客户端 | **8.22.0** | ✅ `:112` |
| `libpcre2-8.so` | 正则（jq / grep 系） | — | ❌ **没有**，现静态链进 jq |
| `libiconv.so` | 字符编码转换（git） | — | ❌ **没有**，现静态链进 git |
| `sysroot` | **C/C++ 头文件**（`stdio.h` 等） | NDK 30.0.16248370 | ✅ `PrefixProvisioner.kt:84` 已在铺 `$PREFIX/include` 软链 |

> `sysroot` 是**数据不是工具** —— 它提供头文件，任何编 C/C++ 的程序都要它，
> 与 clang/pkg-config/make/cmake 那类「编译工具」不同类。它走件安装路径
> （`ProgramManager.stateDirOf(ctx,"sysroot")` + `currentVersion()`），
> 实现形态已经是全局基础件该有的样子。

### 数据

| 件 | 落位 | 现状 |
|---|---|---|
| `ca-bundle.pem` | `$PREFIX/etc/` | ✅ `PrefixProvisioner.kt:33` |
| `zoneinfo/` | `$PREFIX/share/zoneinfo/` | ❌ **没有** |
| locale 定义 | `$PREFIX/lib/locale/` | ❌ **没有** |

---

## 二、不属于基础环境件（已定，不再讨论）

| 件 | 归属 | 依据 |
|---|---|---|
| **`llvmtoolchain`** | **系统组件（tool 筐）** | ✅ **已定并已落地**：`bucket_for` 改为 `tool-llvmtoolchain`，链名 `Build Component · llvmtoolchain`，Release tag `tool-llvmtoolchain`。它不是基础环境件。 |
| `node` / `python3` | rt 筐（运行时） | 三筐分类 |
| `git` / `sqlite3` / `npm` / `pnpm` | tool 筐（工具） | 三筐分类 |

### 构建工具链 —— 已定：编译工具归 tool 筐，sysroot 留base 筐

**判据（已定）**：是不是「编译工具」。

- **是编译工具** → 与 `llvmtoolchain` 同类，归 `tool` 筐
- **是服务全局的数据/能力** → 基础环境件

逐件核实结论：

| 件 | 是编译工具吗 | 判定 | 依据 |
|---|---|---|---|
| `llvmtoolchain` 21.1.0 | ✅ 编译器本体 | tool | 已落地 |
| `pkg-config` 3.0.7 | ✅ 编译参数查询 | **tool** | 见下 |
| `make` 4.4.1 | ✅ 构建自动化 | **tool** | 设备端零消费者 |
| `cmake` 4.4.4 | ✅ 构建系统 | **tool** | 设备端零消费者 |
| **`sysroot`** | ❌ **不是工具，是头文件数据** | **base** | `PrefixProvisioner.kt:84linkSysrootInclude` 已在铺 `$PREFIX/include` 软链，且走件安装路径（`ProgramManager.stateDirOf`）—— 已是全局基础件的形态 |

**为什么 pkg-config 是编译工具**（逐段拆解编译命令）：

```
clang  main.c  -o  main  -I$PREFIX/include  -L$PREFIX/lib  -lz  -lssl  -lcrypto
 │      │                │              │        │      │      │
 │      │                │              │        └──────┴──────┘
 │      │                │              └ 库路径 -L
 │      │                └ 头文件路径 -I
 │      └ 编译器本体（llvmtoolchain）
 └ 源码
```

`pkg-config` **不在这条命令的任何一环里**，它只是「帮你算出 `-I`/`-L`/`-l`」的查询器。
去掉它，上面那条命令完全照样能跑：
`clang main.c -I$PREFIX/include -L$PREFIX/lib -lz -o main`

**它为什么在 Linux 上重要，在我们这儿不重要**：Linux 的库散落在
`/usr/lib/x86_64-linux-gnu`、`/usr/local/lib`、`/opt/...`（路径不统一）才需要查表，
且 `.pc` 文件在 **-dev 包**里（`zlib1g-dev`，不是 `zlib1g` 运行时包）。
**我们是统一前缀 `$PREFIX`，路径本来就一处** —— pkg-config 解决的是我们没有的问题。

它唯一的实际用途：用户编译**内部调用 pkg-config 的第三方库源码**（autotools 的
`./configure` 会硬调它，查不到就报错退出）。这是「编译别人的库」，不是「编自己的程序」。

**筐变更影响**：`make` / `cmake` / `pkg-config` 的 Release tag 从
`base-*` 变成 `tool-*`，旧预制品作废需重编。缓存 key 不含筐名，增量编的缓存仍命中。

---

## 三、系统自带能力（**不是件**）

### 终端 PTY —— 已实现，是本地实现

**这一条纠正旧文档的重大过时结论。**

旧 `BASE-ENVIRONMENT.md:80-104` 写「`shell.exec` 走 ADB 往返，不是本地终端」。
**该结论已过时**（它写于本地 PTY 落地之前）。

现在的真实实现：

| 组件 | 位置 | 规模 |
|---|---|---|
| `PtySession.kt` | `container/app/src/main/java/lobos/runtime/` | 366 行，含 `openSession` / `runToCompletion` / `probe` |
| `pty-session.c` | `container/native/d3/` | 有 `TIOCSWINSZ` / `termios` / `setsid` / `TIOCSCTTY` |
| `librivospty.so` | `NativeAssetRegistry.kt:68`（`ptysession`） | 随 APK |
| `TerminalActivity` | `container/app/src/main/java/lobos/ui/` | 148 行，完整终端 UI |
| `PanelActivity` | 同上 | 第 78 行「终端」按钮 → `PtySession.probe()` → 拉起 `TerminalActivity` |
| `LocalExec.run()` | `runtime/LocalExec.kt:36` | 走 `PtySession.runToCompletion` |

**结论：本地 PTY + 终端 UI 已经是系统自带能力，不是件，也不依赖 ADB。**
`LocalExec.viaAdb()`（第 111-117 行）是**另一条可选路径**，不是终端能力的实现方式。

**旧文档里因此判为「缺口」的两条，已不成立**：
- ~~终端 PTY + termios 是缺口~~ → 已实现
- ~~`ptyprobe`（`liblobosptyprobe.so`）只是探针、终端没实现~~ → 探针与终端宿主是**两个独立件**，各自注册

### 其它自研基础设施（`self-c` 档，随代码走，不是可分发件）

`flock`（`d2/flock.c`）· `posix`（`d1/link-interpose.c`）· `ptyprobe`（`d2/pty-probe.c`）
`node-pty` = node-pty 的 `pty.node`（`build-native-capabilities.sh:219-228` 用 node-gyp 编出后改名）

这些**要进注册表**（否则能力下放时找不到），但**不是基础环境件**。

---

## 四、缺口（现在真的没有的）

| # | 缺什么 | 为什么必须 | 旧文档依据 |
|---|---|---|---|
| 1 | `libpcre2-8.so` | jq / grep 系需要正则，现在是静态链进 jq | `DESIGN.md:179,225` |
| 2 | `libiconv.so` | git 需要字符编码转换，现在静态链 | `DESIGN.md:180,224` |
| 3 | `zoneinfo/` | 程序算本地时间没依据（`new Date().toString()` 会出 GMT+0800 而非本地时区） | `INVENTORY.md:87` |
| 4 | locale 定义 | `setlocale()` 静默降级 —— **比报错更难查** | `INVENTORY.md:88` |

**已从缺口清单移除**：终端 PTY（已实现，见第三节）。

### 版本号缺口

| 件 | 问题 | 后果 |
|---|---|---|
| `ripgrep` | 钉值表无 `ripgrep`/`rg` 项；`NativeAssetRegistry.kt:43` 无 `version` | 版本不可考 → OTA 更新不了 |
| `crypto` | `NativeAssetRegistry.kt:106` 无 `version` | `publish-native-manifest.js:117` 过滤掉 → **永远进不了 OTA 清单**（它与 openssl 同次编译，该是 3.6.3） |

---

## 五、四份口径必须收敛成一份

| 口径 | 件数 | 状态 |
|---|---|---|
| ① `bucket_for` base 筐 | 9 | 与③ 只重合 4 件 |
| ② `NativeAssetRegistry.kt` | 13 | 含 node（rt 筐，不该在这） |
| ③ `native-capabilities.txt`（CI 门禁核这份） | 12 | 从②生成，②错了跟着错 |
| ④ `supply/seed.json` | 7 | ❌ **无任何读者**，且内容与共识矛盾 |

**收敛后**：四份删除或改为从注册表生成。
**门禁**：四份口径并存即判红 —— 现在只有 4 件重合，这件事本身就是判红条件。

---

## 六、已知的方向性错误（`DISTRIBUTION-PLAN.md` 待重写）

| 那篇写的 | 正确方向 |
|---|---|
| 第 4 层「拆掉 APK 原生件这个类别」 | 基础环境件是**随 APK 首发内置 + 走同一条分发路径**，不是「拆掉」 |
| 「消费方全改为读注册表」 | 旧 `DESIGN.md:139-149` 已否掉「让安装器按清单办事」与「运行时探测」。注册表用于**声明与核对**，不是运行时决策。 |

那篇方案在写时不知道已有定论，方向与本文冲突。重写前以本文为准。
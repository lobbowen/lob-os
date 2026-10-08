# 基础环境件清单（按已有定论整理，2026-10-08 核实）

本文的**结论全部来自仓内已有文档**，不是新提案。出处：
`BASE-ENV-DESIGN.md`（最终构成清单 + 明确不进）、
`BASE-ENV-INVENTORY.md`（逐项对照 Linux 的最终盘点 + 已判 5 项）、
`BASE-ENV-DECISIONS.md`（时区/locale//etc/services 三项判断）。

版本号从 `scripts/component-sources.json` 逐件读出并与 `NativeAssetRegistry.kt` 对照过。

**原则（用户已定）**：走统一逻辑 —— 基础环境件与程序、运行时、工具一样，
登记在同一张注册表里，经控制面板分发，随 APK 首发内置、可 OTA 更新。
**不存在「APK 原生件」这个独立类别。**

---

## 一、清单

### 1. 命令（`$PREFIX/bin`，全局 PATH）

| 件 | 作用 | 版本 | 现状 |
|---|---|---|---|
| `bash` | 命令解释器 | **5.2.15** | ✅ 已编（`libbash.so`，`NativeAssetRegistry:35`） |
| `busybox` | 多命令工具（tar/gzip/grep/sed/ls/cp/mv…） | **1.36.1** | ✅ 已编（`libbusybox.so`，`:80`） |
| `ripgrep` | 快速搜索（glob/grep） | ⚠️ **钉值表无此项，版本不可考** | ✅ 已编（`liblobosrg.so`，`:43`）**但无版本号 → OTA 更新不了** |
| `jq` | JSON 处理 | **1.8.2** | ⚠️ 已编（`build-jq.yml` 独立链），**但不在 `NativeAssetRegistry`** |
| `node` | 程序运行时 | **24.21.0** | ✅ 已编（`build-node.yml`）。**属 rt 筐，不是基础环境件** |

> 注：`DESIGN.md:173` 那棵树把 `node` 画在 `bin/` 下，标注是「程序运行时 · 商店下载」——
> 定性是对的（运行时、商店侧），它列在树里是为了示意「装好后全局可用」的样子，
> 不表示它是基础环境件。`INVENTORY.md:74` 那行「已有：bash rg jq（待挪）node libc++_shared ca-bundle」
> 把 node 混进了底座清单，是文档自己的疏漏。**以三筐为准：node 属 rt。**

### 2. 库（`$PREFIX/lib`，全局库搜索路径）

| 件 | 作用 | 版本 | 现状 |
|---|---|---|---|
| `libc++_shared.so` | C++ 运行库（`required=true`） | NDK 30.0.16248370 | ✅ 来自 NDK（`:9`） |
| `libssl.so` | TLS | **3.6.3** | ✅ 已编（`:99`） |
| `libcrypto.so` | 加密 | **3.6.3** | ✅ 已编（`:106`）**但无版本号 → OTA 更新不了** |
| `libz.so` | 压缩 | **1.3.2** | ✅ 已编（`:92`） |
| `libpcre2-8.so` | 正则（jq / grep 系） | — | ❌ **根本没有**，现在是静态链进 jq |
| `libiconv.so` | 字符编码转换（git） | — | ❌ **根本没有**，现在是静态链进 git |
| `libcurl.so` | HTTP 客户端 | **8.22.0** | ✅ 已编（`:112`） |

### 3. 数据与配置

| 件 | Linux 路径 | 现状 |
|---|---|---|
| `ca-bundle.pem` | `/etc/ssl/certs/ca-certificates.crt` | ✅ `usr/ca-bundle.pem`（`PrefixProvisioner:33`） |
| `zoneinfo/` | `/usr/share/zoneinfo/` | ❌ **没有**。已判「进」（`INVENTORY.md:87`）：程序调 `new Date().toString()` / `Intl.*` 就踩空 |
| locale 定义 | `/usr/lib/locale/` | ❌ **没有**。已判「进」（`:88`）：缺失是**静默降级**，比报错更难查 |

### 4. 自研基础设施（`self-c`，不是「件」，随代码走）

`flock` · `posix` · `ptyprobe` · `ptysession`（`container/native/d2`、`d3` 的 C 源码）
`node-pty` = node-pty 的 `pty.node`（`build-native-capabilities.sh:219-228` 用 node-gyp 编出后改名）

**这些不是可分发的件，是系统自己写的代码。** 不进基础环境件清单，
但必须进注册表（否则能力下放时找不到它们）。

### 5. 明确不进的（`DESIGN.md:190-198`）

`glibc libstdc++.so.6`（Bionic ABI 不兼容）· 自编 `linker64`（系统的一部分）·
`libpthread`（Android 16 起并入 libc）· `/etc/passwd` `/etc/group`（单用户系统无此语义）·
`libexpat`（当前无消费者）· `/etc/services`（零消费者，`DECISIONS.md` 三项判断之三）

---

## 二、构建工具链（文档已判「进（必备）」但未归位）

`INVENTORY.md:86`：**「C/C++ 编译器（gcc/g++ + sysroot）—— 进（必备）。用户已定：无选装概念，要完整稳定的环境」**

对应件与版本（均从钉值表核实）：

| 件 | 版本 | 现状 |
|---|---|---|
| `llvmtoolchain`（clang/lld/binutils） | **21.1.0** | 🔄 正在编（独立链 `build-llvmtoolchain.yml`），属 tool 筐 |
| `sysroot` | NDK 30.0.16248370 的头文件集 | ✅ `build-sysroot.yml`，属 base 筐 |
| `make` | **4.4.1** | ✅ `build-make.yml`，属 base 筐 |
| `cmake` | **4.4.4** | ✅ `build-cmake.yml`，属 base 筐 |
| `pkg-config` | **3.0.7**（pkgconf） | ✅ `build-pkg-config.yml`，属 base 筐 |

**这五件的定义就是「编 C/C++ 需要的东西」**，按 `INVENTORY.md` 的判据属基础环境。
但它们现在分散在 base/tool 两个筐里，且没有一处文档把它们收进基础环境清单。

---

## 三、缺口（已定要补但现在没有的）

| # | 缺什么 | 为什么必须 | 依据 |
|---|---|---|---|
| 1 | `libpcre2-8.so` | jq / grep 系需要正则；现在是静态链进各件 | `DESIGN.md:179,225` |
| 2 | `libiconv.so` | git 需要字符编码转换；现在静态链 | `DESIGN.md:180,224` |
| 3 | `zoneinfo/` | 程序算本地时间没依据 | `INVENTORY.md:87` |
| 4 | locale 定义 | `setlocale()` 静默降级 | `INVENTORY.md:88` |
| 5 | 终端（PTY + termios） | 「能执行命令」≠「能开终端」。`BASE-ENVIRONMENT.md:64-104` 已核实：全仓零 `termios`/`winsize`/`isatty`，`shell.exec` 走的是 ADB 不是本地 PTY | `DESIGN.md:77,241` |

### 版本号缺口

| 件 | 问题 |
|---|---|
| `ripgrep` | 钉值表**无 `ripgrep`/`rg` 项**，Kotlin 也无版本号 → 版本不可考，OTA 更新不了 |
| `crypto` | Kotlin `NativeAssetRegistry.kt:106` 无 `version` → `publish-native-manifest.js:117` 过滤掉 → **永远进不了 OTA 清单**（而它与 openssl 是同一次编译产物，该是 3.6.3） |

---

## 四、四份口径的分歧（必须收敛成一份）

现状有四份「基础环境件清单」，互相不认：

| 口径 | 件数 | 内容 |
|---|---|---|
| ① `bucket_for` base 筐 | 9 | sysroot · make · cmake · pkg-config · bash · rg · busybox · jq · curl |
| ② `NativeAssetRegistry.kt` | 13 | libcxx · **node** · bash · ripgrep · flock · posix · ptyprobe · ptysession · busybox · zlib · openssl · crypto · curl |
| ③ `native-capabilities.txt`（CI 门禁核这份） | 12 | ② 去掉 libcxx/node，多一个 node-pty |
| ④ `supply/seed.json` | 7 | **无任何读者**，且写着「运行时与工具一律经商店安装」与共识矛盾 |

**①∩③ 只有 4 件**（bash · busybox · curl · rg）。

本文即是要收敛成的那一份。收敛后：
- ①②③④ 四份口径删除或改为从注册表生成
- 门禁：**四份口径不允许并存** —— 只有 4 件重合这件事本身就是判红条件

---

## 五、与 `DISTRIBUTION-PLAN.md` 的冲突（那篇要按本文重写）

| `DISTRIBUTION-PLAN.md` 写的 | 本文/已有定论 | 冲突性质 |
|---|---|---|
| 第 4 层「拆掉 APK 原生件这个类别」 | `DESIGN.md:153`「底座是**随 APK 交付、内容固定、自带验证判据**」 | **方向相反** |
| 「一张注册表登记全部件，消费方全改为读它」 | `DESIGN.md:139-149` 已否掉「让安装器按清单办事」与「运行时探测」两个方向 | **踩了已否掉的方向**。理由：清单错了照样装错，底座不该是安装流程的产物 |

**修正方向**：不是「让安装器按注册表演」，而是
「注册表登记系统里有什么 → 打包按它装 → 装完即生效 → 每件自带验证判据」。
注册表是**声明与核对**用的，不是**运行时决策**用的。
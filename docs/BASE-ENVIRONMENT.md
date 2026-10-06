# 基础环境梳理

调查对象：`PREFIX`（`files/usr`）—— 宿主自己用的那一层，跟商店里给程序用的件是两回事。

复现：`node /data/user/0/lobos.app/files/work/_scripts/survey-base-env.js`

---

## 一、结论先说

**基础环境有七样东西，分两个来源，但管理方式是两套。**

| 件 | 来源 | 位置 | 管理方 | 全局可用 | 状态 |
|---|---|---|---|---|---|
| `bash` | **APK 原生库** | `usr/bin/bash` | `PrefixProvisioner.provision` | ✅ | 正常 |
| `rg`（ripgrep） | **APK 原生库** | `usr/bin/rg` | 同上 | ✅ | 正常 |
| `libc++_shared.so` | **APK 原生库** | `usr/bin/` | 同上 | ✅ | 正常 |
| `liblobospty.so` | **APK 原生库** | `usr/lib/pty.node` | 同上 | ❌ **没人用** | **待清** |
| `ca-bundle.pem` | **APK assets** | `usr/ca-bundle.pem` | 同上 | ✅ | 正常 |
| `node` | **商店下载** | `usr/lib/toolchain/node/` + `usr/bin/node` 软链 | `PrefixProvisioner.linkNode` | ✅ | 正常 |
| `liblobosptyprobe.so` | **APK 原生库** | APK `lib/arm64/` | `InstanceHost.runPtyProbe` | — | **只是探针** |

**"两个来源"是对的**（随 APK 的是宿主自己要的，商店的是程序要的）。
**"两套管理"是问题** —— 见第三节。

---

## 二、`PrefixProvisioner` 是什么

`files/usr`，一个前缀式根目录。仿 Linux 的 `/usr`：

```
usr/
├── bin/          可执行入口（PATH 里）
│   ├── bash      ← 从 APK 原生库搬
│   ├── rg        ← 从 APK 原生库搬
│   ├── libc++_shared.so
│   └── node      → 软链到 ../lib/toolchain/node/bin/node
├── lib/
│   ├── toolchain/{node,npm,pnpm,curl,git,jq,sqlite3}/…   ← 商店下载的件
│   └── pty.node                                          ← 从 APK 原生库搬，但没人用
└── ca-bundle.pem
```

`provision()` 做四件事：从 `nativeLibraryDir` 搬三类件、铺 CA 证书、给 node 建软链。

`expected()` 是"应该有什么"的清单，`RuntimeEnvironment` 拿它和 `provision()` 的实际结果比对，差了就报缺件。

---

## 三、问题：三处

### 3.1 `pty.node` 铺了但没人用

`liblobospty.so` 被搬到 `usr/lib/pty.node` —— 这是 **node 的原生模块**（给 node 用的 `.node` 扩展），不是终端模拟器。

全仓只有 `PrefixProvisioner` 提到它，**没有任何代码 require 它**。

它还在 `expected()` 的清单里，所以"缺了会报警"，
但它是**永远不会被用到的东西**。

判据：要么有程序 require 它（那要在商店清单里声明依赖），要么删。

### 3.2 `liblobosptyprobe.so` 是探针，不是终端

`CompatSemantics` 里写着：

```kotlin
Item("pty", "伪终端语义", "done", "d2/pty-probe.c")
```

标成 `done`。但实际情况：

- `InstanceHost.runPtyProbe()` 只在程序宿主启动时跑一次 `liblobosptyprobe.so`
- 它只把输出写进 `RuntimeDiagnostics`（诊断面板）
- **全仓零 `termios` / `winsize` / `TIOCSWINSZ` / `isatty`** —— 交互式终端（行编辑、窗口大小、Ctrl-C）**没有实现**

**"done" 指的是"探针能跑"，不是"终端能用"。** 这个状态标注有误导性。

### 3.3 终端能力对外是 `shell.exec`，但走的是 ADB 不是本地 PTY

`ApiSpec` 里暴露了四个方法：

```
shell.status / shell.pair / shell.forget / shell.exec
```

`shell.exec` 的实现是：

```kotlin
AdbClientRunner.shell(this, full, null, null, timeoutMs)
// uid = 2000（shell），privileged = true
// note: "以 shell uid(2000) 经内置 ADB 客户端（无线调试）执行。"
```

**它不是本地终端** —— 是把命令送到设备 shell 那边执行，通过无线调试通道往返。

后果：
- 要先配对 ADB 才能用
- 非交互（没有 tty、没有交互式输入）
- 输出是 `stdout` 字符串，不是终端会话

所以"基础环境有终端"这个说法**不准确**：有的是"能执行 shell 命令"，不是"能开终端"。

---

## 四、`SHELL` 环境变量的实际值

```kotlin
put("SHELL", root.bashBin?.absolutePath ?: "/system/bin/sh")
```

给程序的 `SHELL` 指向 `usr/bin/bash`（我们自己编的 bash），
拿不到时回落 Android 的 `/system/bin/sh`。

这条是对的 —— 但前提是 `bash` 真的在。`bash` 现在从 **APK 原生库**搬，
不经商店（`NativeAssetRegistry` 里 `bash` 的 note 写着「P2 起 bash 改由前缀目录提供」）。

**注释说的"改由前缀目录提供"已经实现了**，但 `NativeAssetRegistry` 里那条 note 没跟着改。

---

## 五、与商店那套的关系

| | 宿主基础环境 | 商店给的件 |
|---|---|---|
| 根目录 | `files/usr` | `files/programs/<id>/<version>/` |
| 谁装 | `PrefixProvisioner.provision`（从 APK 搬） | `ProgramInstallPipeline`（商店下载） |
| 入口 | `usr/bin/<name>` | `usr/bin/<name>` 软链（`linkEntry`） |
| 依赖铺法 | 无（都是 APK 原生件，依赖已知） | `satisfyElfDeps`（读 ELF 段） |
| 登记 | 无（`expected()` 只比对列表） | `ProgramIndex` |

**两套入口路径相同（都在 `usr/bin`），但登记方式不同**：
`bash`/`rg` 不进 `ProgramIndex`（只是铺文件），而 `git`/`curl` 装完会登记。

这就导致：**没法统一回答"系统里现在有哪些可全局调用的东西"**——
一部分在 `ProgramIndex` 里，一部分不在。

---

## 六、要定的三件事

1. **`pty.node`**：删，还是在商店清单里声明给谁用？
2. **`CompatSemantics` 里 `pty: done`**：改成 `probe-only`？否则文档说终端好了、实际没有。
3. **基础环境要不要进 `ProgramIndex`**：
   - 进 → 能统一查询/卸载/升级，但 `bash`/`rg` 随 APK 交付，升级语义不同
   - 不进 → 保持现状，但要有一份清单说明"这些是随 APK 的，不参与商店升级"

---

## 七、这次没查的

1. **设备上的实际状态** —— adb 通道断（`ECONNREFUSED 127.0.0.1:44851`），
   没法看 `usr/bin` 里现在真有哪几件、软链有没有断。

2. **`bash` 是不是真的自足** —— 它从 APK 搬，`DT_NEEDED` 有没有缺的东西，没读。
   （有 `ElfFacts` 了，可以查，但它只对商店件调用，`provision()` 走的 APK 搬运动没接）

3. **`ca-bundle.pem` 的内容来源** —— 从 `assets/ca-bundle.pem` 搬，
   但没查它是构建时抓的哪份系统证书、有没有过期。
   环境变量的优先级是清楚的（不是并存冲突）：
   `SSL_CERT_DIR` → 系统三处目录（OpenSSL 语义，逐目录查）
   `SSL_CERT_FILE` / `CURL_CA_BUNDLE` / `GIT_SSL_CAINFO` → 我们的 bundle
   两组给不同工具看，不是同一件事的两个候选。
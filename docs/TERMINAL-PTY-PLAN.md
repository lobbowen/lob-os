# 终端（PTY）：必须做，且是底座能力

**用户定的**：终端必须存在。程序要调用终端时没有就直接报错——
所以终端不是"交互式外壳"，是**程序可依赖的系统能力**。

本轮只做设计与判据核实，不动代码。

---

## 一、现状核实

### 1.1 程序用终端时，现在发生什么

```
程序要执行命令
  → lobeos.bridge 的 shell.exec
  → 实现是 AdbClientRunner.shell（无线调试往返）
  → 前置条件：必须已配对 ADB
  → 未配对 = 直接报错        ← 这就是"没有就是报错"
```

**一个纯执行命令的能力，绑在"配对无线调试"上。** 这是错的分层：
无线调试是运维通道，不是程序执行通道。

### 1.2 交互式终端：零实现

| 查什么 | 结果 |
|---|---|
| `termios` | **零命中** |
| `winsize` / `TIOCSWINSZ` | **零命中** |
| `isatty` | **零命中** |
| `forkpty` / `openpty` / `posix_openpt` | 零命中（源码层） |

### 1.3 那两个 PTY 相关的原生件是什么

| 件 | 实际是什么 | 状态 |
|---|---|---|
| `liblobosptyprobe.so` | **探针**，57 行 C。只测 `grantpt` / `unlockpt` / `ptsname` / `tcgetattr` 能否成功 | 跑一次写诊断 |
| `liblobospty.so` → `pty.node` | **node-pty**（node-gyp 编译的 node 扩展），服务 node 生态 | 底座铺了，**无人 require** |

### 1.4 状态标注有误导

```kotlin
CompatSemantics: Item("pty", "伪终端语义", "done", "d2/pty-probe.c")
```

标成 `done`，但"done" 指的是**探针能跑**，不是"终端能用"。

---

## 二、Linux 的终端是怎么组成的

对齐的对象是 Linux 用户空间的这几层：

| 层 | Linux | 作用 |
|---|---|---|
| **PTY 设备** | `/dev/ptmx` + `/dev/pts/*` | 提供"伪终端"：程序以为自己在跟真终端对话 |
| **行规程** | `termios` | icanon/echo/信号处理/窗口大小 |
| **shell** | `bash` 经 `/dev/pts/N` 挂上 | 在 PTY 上跑命令 |
| **登录/会话** | `agetty` + `login` | 开 PTY、设置窗口大小、启动 shell |

**我们不需要 `agetty`（那是系统启动时开登录会话的）**，
程序要的是**能不能自己申请一个 PTY 起进程**。

---

## 三、我们要建什么

### 3.1 三层，缺一不可

```
程序
  │ ① 申请 PTY 对      posix_openpt / openpty
  │ ② 配置行规程       tcgetattr/tcsetattr（行编辑、echo、窗口大小）
  │ ③ 在 PTY 上起进程   posix_spawn + setsid + TIOCSCTTY
  ▼
slave 端跑 bash/程序
```

### 3.2 落在哪一层

| 能力 | 形态 | 理由 |
|---|---|---|
| **PTY 分配与管理** | `librivospty.so`（新建，`self-c`） | 底座原生件，随 APK |
| **终端窗口** | 内置终端 UI（快应用/原生 View） | 供人用；程序不需要窗口 |
| **shell.exec 改造** | 本地执行，**不再绕 ADB** | 程序要的能力不该依赖运维通道 |
| **TTY 语义** | 程序看到 `isatty()=true` | 让 `vi` / `top` / `npm` 之类能正常工作 |

### 3.3 关键：`liblobospty.so` 怎么处理

它是 **node 的原生模块**（`pty.node`），不是我们自己的终端能力。

但它里面很可能有可复用的 PTY 分配逻辑。**两件事要分清**：

| | 归属 | 谁需要 |
|---|---|---|
| PTY 分配 | `librivospty.so`（我们的） | 所有程序 |
| `pty.node`（node 扩展） | 可选，node 生态用 | 只有 node 程序 |

`pty.node` 若与新实现重复 → 删；若有独有功能（如 node-pty 的事件循环集成）→ 保留但**要在商店清单里声明**，由安装器按需铺，不能由底座硬塞。

---

## 四、`shell.exec` 必须改

现状（错）：

```kotlin
"shell.exec" → AdbClientRunner.shell(...)     // 绕无线调试
```

改后（对）：

```
shell.exec
  → 本地 POSIX 执行（ProcessBuilder / posix_spawn）
  → 要交互语义时走 PTY
  → 不依赖 ADB
```

**判据**：拔掉无线调试，程序仍能执行命令。

---

## 五、验收判据（可测）

| # | 判据 | 怎么测 |
|---|---|---|
| 1 | 断网/未配对 ADB 时，程序能执行命令 | 关掉无线调试，跑 `shell.exec` |
| 2 | `isatty` 为真 | 程序里打印 `process.stdout.isTTY` |
| 3 | 行编辑生效 | 在终端里按 `^C` 能中断、`^D` 能 EOF |
| 4 | 窗口大小 | 改窗口大小后 `TIOCGWINSZ` 返回新值 |
| 5 | 交互式程序可用 | 在终端里跑 `bash` 后能进交互模式、`vi` 能编辑 |
| 6 | 颜色与编码正确 | `ls` 带颜色、中文文件名不乱码 |

---

## 六、实施顺序

| 序 | 内容 | 依赖 |
|---|---|---|
| 1 | `librivospty.so`：PTY 分配 + termios（`self-c`） | 无 |
| 2 | `shell.exec` 改本地执行 + PTY 模式 | 1 |
| 3 | `pty-probe` 从"探针"升级为"能力自检"（`CompatSemantics` 的 `pty: done` 改 `probe-only`） | 1 |
| 4 | 内置终端窗口（UI） | 1、2 |
| 5 | `pty.node` 去留决策 | 1 |

**第 1、2 步做完，"程序要终端就报错"这个根本问题就解决了**；
第 4 步（窗口）是给人用的，不影响程序。

---

## 七、待你确认

| # | 问题 | 我的倾向 |
|---|---|---|
| 1 | `pty.node`（node-pty）**留还是删** | 已查：它是 node-pty，服务 node 生态，**不服务所有程序**。倾向删（底座不该硬塞商店件），要留就在商店清单里声明 |
| 2 | 内置终端窗口做**快应用**还是**原生 View** | 原生 View —— 终端要 termios 直通，WebView 做不到 |
| 3 | `shell.exec` 要不要支持 **stdin 交互**（现在只传 stdout） | 要。`build` 那种交互式构建工具离了这个没法用 |

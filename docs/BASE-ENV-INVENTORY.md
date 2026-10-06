---

# 附：底座完整清单（逐项对照 Linux，2026-10-06 补充）

本节是**最终盘点**，上文是决策过程。有冲突以本节为准。

判据：**能编译进 APK 的进底座；系统的靠系统；不可替换的不造。**
形式：底座件**原生打包进 APK + 有版本 + 走 OTA 更新**（见 `docs/NATIVE-COMPONENT-UPDATE.md`）。

---

## 1. 可执行工具（`usr/bin`）

| # | 件 | Linux 对应 | 现状 | 决定 |
|---|---|---|---|---|
| 1 | 命令解释器 | `bash` / `dash` | ✅ 自编 `libbash.so` | 已有 |
| 2 | **多命令工具** | `toybox` / BusyBox | ❌ | **进底座** |
| 3 | 快速搜索 | ripgrep | ✅ 自编 `liblobosrg.so` | 已有 |
| 4 | JSON 处理 | `jq` | ⚠️ 现在在商店清单里 | **升入底座** |
| 5 | **终端（PTY）** | `agetty` + PTY | ❌ 零实现 | **必须做**（不是件，是能力） |
| 6 | C 编译器 | `gcc` | ❌ | ❓**待判** |
| 7 | C++ 编译器 | `g++` | ❌ | ❓同上 |
| 8 | 文本编辑器 | `vi` / `nano` | ❌ | ✅ 由 BusyBox 的 `vi` 覆盖 |
| 9 | `curl` `git` `sqlite3` | 同名 | ✅ 商店 | 已有（改动态链） |
| 10 | `node` | 非标准 | ✅ 商店 | 已有 |

第 6、7 项待判 —— 代价见末节「范围」。

## 2. 共享库（`usr/lib`）

| # | 库 | 谁需要 | 现状 | 决定 |
|---|---|---|---|---|
| 1 | **C++ 运行库** | node、任何 C++ 件 | ✅ `libc++_shared.so`，`required = true` | **已有**（底座唯一必需要的一件） |
| 2 | **TLS / 加密** | curl、git、任何 https | ❌ 各自静态链 | **进底座**（`libssl` `libcrypto`） |
| 3 | **压缩** | curl、git、BusyBox | ❌ 静态链 | **进底座**（`libz`） |
| 4 | **正则** | jq、grep 系 | ❌ 静态链 | **进底座**（`libpcre2-8`） |
| 5 | **字符编码转换** | git、locale 工具 | ❌ 静态链 | **进底座**（`libiconv`） |
| 6 | **HTTP 客户端** | git 依赖 | ❌ 静态链 | **进底座**（`libcurl`） |
| 7 | XML 解析 | 部分工具 | ❌ 当前无消费者 | 不进 |
| 8 | glibc 的 `libstdc++.so.6` | — | — | 不进（Bionic ABI 不兼容）。**C++ 运行库本身已在底座** |

**C++ 那条更正**：早前版本把 `libstdc++.so.6` 标成"❌ 无"，会被误读成"底座没有 C++ 运行库"。
实际是：**C++ 运行库必须有，且已在底座**（`libc++_shared.so`，唯一 `required = true` 的一件）。
不能搬的只是 glibc 那个特定文件名，不是这个能力。

## 3. 数据与配置（`usr/etc` `usr/share`）

| # | 件 | Linux 路径 | 现状 | 决定 |
|---|---|---|---|---|
| 1 | **CA 证书** | `/etc/ssl/certs/ca-certificates.crt` | ✅ `usr/ca-bundle.pem` | 已有（建议归位 `etc/`） |
| 2 | **时区数据** | `/usr/share/zoneinfo/` | ❌ 代码零提及 | ❓待判 |
| 3 | **locale 定义** | `/usr/lib/locale/` | ⚠️ 只设了 `LANG=C.UTF-8` 字符串 | ❓待判 |
| 4 | 服务端口映射 | `/etc/services` | ❌ | ❓待判 |
| 5 | 用户 / 组 | `/etc/passwd` `/etc/group` | ❌ | **不进** —— 单用户系统无此语义 |
| 6 | 头文件 | `/usr/include/` | ❌ | 随编译器一起判 |

`/etc/passwd` 不进，但**很多 C 程序启动时调 `getpwuid()`**，拿不到会打警告
（git 会报 `detected dubious ownership`）。保证它不让程序失败，由代码统一兜底
（`HOME`/`SHELL` 已在 env 里），**不靠造文件**。

## 4. 机制（非文件）

| # | 机制 | Linux 对应 | 现状 |
|---|---|---|---|
| 1 | **全局库搜索路径** | `/etc/ld.so.conf` + `ldconfig` | ✅ `RuntimeEnvironment.libSearchPath` |
| 2 | **全局入口** | `/usr/bin` 在 PATH 里 | ✅ `usr/bin/` + 软链 |
| 3 | **底座件版本 + OTA 更新** | 包管理器 | ❌ 只校验不更新 —— **要建** |
| 4 | **依赖铺放** | 包的 `Requires` | ✅ `satisfyElfDeps` 读 ELF 段 |
| 5 | **终端能力** | tty + termios | ❌ **必须做** |

## 5. 汇总

**已有（6 项）**
`bash` `rg` `jq`（待挪） `node` `libc++_shared` `ca-bundle`
+ 三个机制：全局库路径 / 全局入口 / 依赖铺放

**已定要补（8 项）**
`busybox` · `libssl` · `libcrypto` · `libz` · `libpcre2-8` · `libiconv` · `libcurl`
+ **终端（PTY + termios）**
+ 底座件的版本与 OTA 更新机制

**已判（见 docs/BASE-ENV-DECISIONS.md）**

| # | 项 | 判断 | 依据 |
|---|---|---|---|
| 1 | C/C++ 编译器（gcc/g++ + sysroot） | **进（必备）** | 用户已定：无选装概念，要完整稳定的环境 |
| 2 | 时区数据 `zoneinfo` | **进** | 程序调 `new Date().toString()` / `Intl.*` 就踩空 |
| 3 | locale 定义文件 | **进** | 缺失是**静默降级**，比报错更难查 |
| 4 | `/etc/services` | **不进** | 代码零消费者；`https` 走协议不查这个表 |
| 5 | `usr/include/` 头文件 | 随编译器 | gcc/g++ 必需 |

**明确不进**
glibc 的 `libstdc++.so.6`（Bionic ABI）· 自编 `linker64`（系统的一部分）·
`libpthread`（Android 16 起并入 libc）· `/etc/passwd` `/etc/group`（单用户无此语义）·
`libexpat`（当前无消费者）

## 6. 需要你划的线：范围

Linux 底座不止"几个库 + 几个命令"：

```
binutils（as ld objdump readelf）
coreutils / findutils / gawk / sed / grep / tar / gzip
gcc / g++ / binutils / make / sysroot        ← 开发环境
dpkg / apt                                     ← 包管理
```

我们不可能全做。三层划法：

| 层 | 内容 | 判断 |
|---|---|---|
| **运行时底座** | shell + 终端 + 全局库路径 + 基础库 + 常用命令 | **必须做** —— 缺了系统不成立 |
| **开发环境** | gcc/g++ + sysroot + binutils + make | ❓ 见下 |
| **包管理** | dpkg/apt 那套 | 不需要 —— 我们有商店 |

**不做开发环境是可以的**，前提是所有程序都带预编译件，像现在 curl/git/jq 那样。
代价：用户不能在设备上写代码、编译 C/C++，程序也不能自己编扩展。

要做「用户能在设备上开发」，这一层就得补。**这是范围问题。**
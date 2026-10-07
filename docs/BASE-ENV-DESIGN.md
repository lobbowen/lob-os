# 底座该有什么：对着 Linux 的构成讨论

基准：Linux 一个可用的用户空间底座由什么组成。
目标：LobOS 对齐它，**缺的补上**，**不需要的明确说清为什么不需要**。

形式已定：**随 APK 源码编译**（与现在 `bash`/`rg` 同一套机制）。

---

## 一、Linux 底座的五层

### 1. 宿主与 ABI 层（系统自带，不可替换）

| 件 | Linux | Android 现状 |
|---|---|---|
| 系统调用 | `libc.so`（glibc/musl） | ✅ `/system/lib64/libc.so`，**不可替换** |
| 数学 | `libm.so` | ✅ 系统自带 |
| 动态链接 | `ld-linux-x86-64.so.2` | ✅ `/system/bin/linker64`，**不可替换** |
| 动态加载 | `libdl.so` | ✅ 系统自带 |
| 线程 | `libpthread.so` | ✅ Android 16 起并入 libc |

**这层不用我们做**，Android 已给。
但有个硬约束：**Android 的 Bionic 不是 glibc**。若要自己带 C++ 库，
必须按 Bionic ABI 编译，不能拿 Linux 上编好的 `.so` 直接用。

### 2. C/C++ 运行库（Linux 随发行版给，可换）

| 件 | 作用 | Android 现状 |
|---|---|---|
| `libstdc++.so.6` | C++ 标准库 | ⚠️ 文件名不同，但 **C++ 运行库已在底座**（`libc++_shared.so`） |
| `libgcc_s.so.1` | 异常展开、栈保护 | ❌ 无（Bionic 环境一般不需要） |
| `libc++_shared.so` | NDK 的 C++ 运行库 | ✅ **有**（已随 APK） |

**对齐的是"有 C++ 运行库"这件事，不是那个文件名。**

### 3. 动态库搜索路径（Linux 的 `ld.so.conf`）

Linux：`/etc/ld.so.conf` 列目录，`ldconfig` 建缓存。
**所有程序共用一份配置，装完 `.so` 立刻全局可见。**

Android：**没有这个机制**，每个进程只能靠自己的 `DT_RUNPATH`。
所以我们必须有等价物 —— 就是 `RuntimeEnvironment.libSearchPath`（上一轮补的）。

### 4. 用户空间工具链（Linux 发行版给，可换）

| 类 | Linux 典型 | 我们该有 | 现在 |
|---|---|---|---|
| 命令解释器 | `bash` / `dash` | `bash` 或 `dash` | ✅ 自编 `bash` |
| **终端** | `agetty` + `login` + PTY | PTY + termios | ❌ **零实现** |
| 文本处理 | `grep` `sed` `awk` | 至少 `grep` | ⚠️ 只有 `rg` |
| 文件工具 | `ls` `cat` `cp` `mv` | BusyBox 一件全有 | ❌ 无 |
| 归档 | `tar` `gzip` | 至少 `tar` | ❌ 无（代码里自己实现 unzip） |
| 网络 | `curl` / `wget` | `curl` | ✅ 商店 |
| 版本控制 | `git` | 有用 | ✅ 商店 |
| 结构化查询 | `jq` | 有用 | ✅ 商店 |
| 编辑器 | `vi` / `nano` | 至少一个 | ❌ 无 |

### 5. 数据与配置（Linux 有路径约定）

| 件 | Linux | Android 现状 |
|---|---|---|
| CA 证书 | `/etc/ssl/certs/ca-certificates.crt` | ✅ `usr/ca-bundle.pem` |
| 时区 | `/usr/share/zoneinfo/` + `/etc/localtime` | ❌ 零提及 |
| locale | `/usr/lib/locale/` + `LANG` | ⚠️ 只设了字符串，**无定义文件** |
| 用户/组 | `/etc/passwd` `/etc/group` | ❌ 无 |
| 随机源 | `/dev/urandom` | ⚠️ Android 有，程序能否读到未验证 |
| 服务映射 | `/etc/services` | ❌ 无 |

---

## 二、差距清单

### 必补（缺了系统不成立）

| 项 | 为什么 | 形式 |
|---|---|---|
| **PTY + 交互终端** | 没有它只有"执行命令"，没有"用终端" | 自己写 C（`liblobospty.so` 有雏形，但要真做 termios/winsize） |
| shell 至少一个 | ✅ 已有 `bash` | 已有 |
| 全局动态库路径 | 装完的件才全局可用 | 已有（`libSearchPath`） |

### 该补（缺了日常不顺手）

| 项 | 用途 |
|---|---|
| `tar` `gzip` | 装包、解压、日常 |
| `grep` | `rg` 不完全替代 |
| 基础文件工具 + 编辑器 | BusyBox 一件全有 |
| 时区数据 | 程序要 `Asia/Shanghai` 时从哪来 |
| locale 定义文件 | `setlocale()` 可能失败 |

### 待决策

| 项 | 情况 | 问题 |
|---|---|---|
| **OpenSSL** | curl/git 现在全靠商店静态构建 | 底座给不给？给 → 程序可动态链 https；不给 → 要 https 的件必须静态编 openssl |
| **BusyBox vs 各编各的** | — | 一件多命令 vs 清晰但量大 |
| **库命名对齐** | Android 是 `libc++_shared.so` | 建议按"需不需要这个能力"判，不按文件名对齐 |

### 明确不需要

| 项 | 原因 |
|---|---|
| 自己编动态链接器 | Android 的 `linker64` 是系统的一部分，不可替换 |
| `libpthread` | Android 16 起并入 libc |
| systemd / init | Android 是 `init`，不由我们提供 |

---

## 三、要你先定的三件事

1. **OpenSSL 进不进底座？**
   - 进 → 程序可动态链 https，curl/git 不必再静态重复带 openssl
   - 不进 → 所有 https 件必须静态编 openssl，底座保持极简

2. **基础工具用 BusyBox 一件，还是各编各的？**
   - BusyBox：一个二进制带 40+ 命令（`tar` `grep` `vi` `ls` 全有），底座只多一件
   - 各编各的：更清晰，APK 体积与构建复杂度上去

3. **`libstdc++` 这类"要不要"怎么判？**
   建议按"需要这个能力吗"判，不按文件名对齐。需要 C++ 就有（已有），不需要就没有。

---

## 四、一句话总结差距

现在：**3 件**（`bash` `rg` `libc++`）+ 一个没人用的 `pty.node`。

对齐 Linux 后应是：**shell + 终端 + 全局库路径 + 一件多命令工具 + 证书 + 时区/locale**。

**中间缺的是「终端」和「日常工具」，以及三件待决策的（OpenSSL / BusyBox 形态 / 库命名）。**

讨论清楚再动手，这轮不改代码。
---

## 五、两个被否掉的错误方向（留痕，避免再犯）

### 方向 A：声明式（已否）

提议：商店目录项加 `runpathOrigin` / `sharedLibs`，安装器按声明铺依赖。

**为什么否**：还是让安装器"按清单办事"。清单错了照样装错，
只是把"装错"推迟到运行时。底座不该是安装流程的产物。

### 方向 B：探测式（已否）

提议：运行时扫 `/system/lib`、`/apex/*/lib`，扫不到就当没有。

**为什么否**：底座变成"现场判断"。设备差异直接变成能力差异 ——
同一个 APK 在 A 设备能用、B 设备不能。**底座要稳，不能看设备脸色。**

### 正确方向

底座是**随 APK 交付、内容固定、自带验证判据**的一层：

- 有什么，写清楚（清单）
- 为什么在里面，写清楚
- 装完即生效，不探测、不看设备
- 每件有验证（跑 `--version` 就知道好坏）

---

## 六、最终构成清单

已定三件：**OpenSSL 进底座** / **BusyBox 一件** / **curl·git·jq 改动态链**。

```
usr/
├── bin/                    全局入口（PATH 里）
│   ├── bash                命令解释器          ✅ 已有（自编）
│   ├── busybox             多命令工具          🆕 已定待编
│   ├── rg                  快速搜索            ✅ 已有（自编）
│   ├── jq                  JSON 处理           🆕 已定：从商店件升为底座
│   └── node → …            程序运行时          ✅ 商店下载
├── lib/                    共享库（全局库路径里）
│   ├── libc++_shared.so    C++ 运行库          ✅ 已有
│   ├── libssl.so           TLS                 🆕 已定待编
│   ├── libcrypto.so        加密                🆕 已定待编
│   ├── libz.so             压缩                🆕 进底座（curl+git 重复）
│   ├── libpcre2-8.so       正则                🆕 进底座（oniguruma + BusyBox grep）
│   ├── libiconv.so         字符编码转换        🆕 进底座（git）
│   ├── libcurl.so          HTTP 客户端         🆕 进底座（git 依赖）
│   └── toolchain/…         商店下载的件         ✅ 已有（curl/git/sqlite3/npm/pnpm）
├── etc/                    配置
│   ├── ca-bundle.pem       根证书              ✅ 已有（建议归位到 etc/）
│   └── services            端口↔服务名         ❓待判
└── share/
    └── zoneinfo/           时区数据            ❓待判
```

**明确不进底座**：

| 项 | 原因 |
|---|---|
| glibc 的 `libstdc++.so.6` | Bionic ABI 不兼容。**C++ 运行库本身已在底座** |
| 自编 `linker64` | 系统的一部分，不可替换 |
| `libpthread` | Android 16 起并入 libc |
| **`/etc/passwd`、`/etc/group`** | **我们是单用户系统**（已核实：无多用户设计，所有程序共享一个 uid、一个 HOME、同一套文件目录）。Linux 上这两个文件的核心语义是"UID ↔ 多用户映射"，我们没有这个概念。**不造。** |
| `usr/include/` 头文件 | 看有没有程序要现场编译 C 代码 |

**jq 为什么升为底座**：与 BusyBox 无实质重叠 ——
BusyBox 有 `awk sed grep cut sort uniq tr wc head tail`，但**没有 JSON 能力**。
而 LobOS 本身大量用 JSON（清单、配置、事件流、注册表），jq 是诊断与排障的通用工具。

### 单用户带来的一个连带问题

`/etc/passwd` 不进底座，但很多 C 程序启动时会调 `getpwuid()`：
拿不到会 fallback 或打警告（git 会报 `detected dubious ownership`）。

所以要保证的是 **`getpwuid()` 不让程序失败** ——
这由代码统一兜底（HOME / SHELL 已在 env 里设了），不靠造文件。

### 判据（可复用）

> 某个库被**两个以上件**需要，或被**程序运行时**需要 → 进底座。
> 只被一个件用且那个件可静态链 → 那个件自己带。

按这条：`sqlite3`（amalgamation 单文件）自己带；`libexpat` 当前无人用 → 不进。

### 改动态链的连带工作（发布侧）

| 件 | 去掉静态链 | 改为链接 | 重编 | 归属 |
|---|---|---|---|---|
| curl | zlib + openssl | `-lz -lssl -lcrypto` | ✅ | 商店 |
| git | zlib + openssl + curl | `-lz -lssl -lcrypto -lcurl` | ✅ | 商店 |
| jq | oniguruma | `-lpcre2-8` | ✅ | **底座**（已定升入） |

**归属与构建方式无关**：jq 升为底座件，构建仍在发布侧做（只是从商店清单挪到 APK 原生件清单）。
底座件 = 随 APK 交付 + 全局入口 + 参与库依赖，不等于「必须自己写源码」。

**依赖顺序**：底座库要先编好落进 APK，商店件才能链 —— 发布流水线要调顺序。

### 待你确认（3 项，我不替你定）

| # | 项 | 我的倾向 |
|---|---|---|
| 1 | 时区数据 `zoneinfo` | 进 —— 代码从不处理时区（`zoneinfo`/`TZ`/`TimeZone` 零命中），程序处理本地时间没依据 |
| 2 | locale 定义文件 | 进 —— `LANG=C.UTF-8` 已设（对 node 够用），但无定义文件时 `setlocale()` 可能失败 |
| 3 | `/etc/services` | 进 —— 小文件，端口号↔服务名查询常用 |

**已从清单移除**：`/etc/passwd` `/etc/group`（单用户无此语义）、`usr/include/`（除非将来有程序要现场编译）。
另外**终端（PTY + termios）** 是最早指出、至今零实现的，必须做 —— 它不在上面清单里是因为它不是"件"而是"能力"。

缺什么就**编进去**，而不是装的时候再想办法。

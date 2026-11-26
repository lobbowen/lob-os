# LobOS 系统能力对标审计与补齐方案

基线 fdf9f967 · 2026-11-26 · 只读审计，不改代码
方法：7 个维度并行深度读代码（进程/文件系统/Shell/权限/IPC网络/服务/工具链），交叉核对文档与实现。

## 一、结论先行

拆分清楚宿适配与系统本体后，可以客观回答"我们距离 Linux 还差什么"。三句核心判断：

1. **问题不是"薄"，而是"适配层堆砌"**。系统用三条互不统一的通路让软件"看起来能跑"：
   Java ProcessBuilder + argv 预改写、LD_PRELOAD 用户态 libc hook、独立 native PTY 宿主。
   这是针对具体二进制形态的特化适配，不是 Linux 的通用语义。通用能力差的根因在这里。

2. **语义不统一是当前最该正视的问题**，不是某一个命令缺失。三个典型：
   - exec 有三条路径，静态 ELF 走 raw_execve 完全绕过 hook
   - VFS 是"纯字符串前缀替换 + 白名单"，不是内核 VFS，绕得过
   - 身份双轨：guest `getpid()` 固定 4242，而 /proc、账本、kill 用真实 PID

3. **有明确的无 root 硬边界**，必须承认并绕行，不能假装能补。多用户/mount/chroot/CAP_* 在
   untrusted_app 下代码层补不出来。方案要区分"能补齐的通用能力"和"硬边界下的替代形态"。

## 二、能力缺口矩阵（对标 Linux）

难度：低=用户态小改动；中=需新组件或状态机；高=需重构执行模型；不可行=无 root 约束。

### 2.1 进程与作业控制
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| exec 语义 | 三通路，ExecEntry 读 256 字节识别形态；无 posix_spawn/fexecve 拦截 | Go/Rust/静态程序、用 posix_spawn 的运行时绕过兼容层；argv[0] 被改成 loader 路径；无 ENOEXEC->/bin/sh 回退 | 高 |
| wait/信号 | 仅 PTY 宿主有完整 waitpid；普通程序只有 exitValue | 被信号杀死的普通程序只显示退出码非 128+signo；无 WIFSTOPPED；无 SIGCHLD/subreaper | 中-高 |
| 进程树 | ProcessLedger 按需扫 /proc 建快照，非持续维护 | daemonize/双层 fork 脱离账本；扫描期间 fork 的进程成孤儿；kill 前不校验 PID 复用有误杀 | 中（缓解）/高（根治）|
| 发信号 | 只 SIGTERM/SIGKILL（+PTY 的 SIGHUP） | `kill -STOP/-CONT`、`timeout -s`、`killall -s` 不可用；同 uid 下 kill(pid,sig) 本就可行，是做而未做 | 低 |
| 作业控制 | PTY 的 session leader + controlling terminal 是真的，但 `TerminalProfile.kt:153` 显式 `set +m`；UI 只发整行文本 | Ctrl-C/Ctrl-Z/jobs/fg/bg 不可用；vim/less/top 这类 TUI 不可用 | 中 |
| rlimit/cgroup | 运行时代码全树零 setrlimit/getrlimit/cgroup | ulimit -n/-v/-t 无实现；失控 guest 可耗尽 FD/内存拖垮宿主 | 中（FD）/不可行（硬隔离）|
| ps/top/kill | BusyBox applet 白名单只有 30 项，仅 ps，无 top/kill/pkill/free | /proc 直通 Android 真实视图，PID 与 guest getpid 不一致；看到整机进程无隔离 | 中 |

### 2.2 文件系统与路径
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| FHS 目录 | ensureAll 只建 etc/usr/var/run/opt | 无 home/root/mnt/media/srv；/bin /sbin /lib 只有翻译别名无实体；`ls /` 看不到 FHS 布局 | 低（补目录）|
| HOME | = app filesDir（Android 绝对路径） | `~` 泄漏沙箱路径，不在标准树里；与 /usr 视图分离 | 中 |
| 符号链接 | 建链接用绝对真实路径；readlink 只翻输入不翻返回 | `readlink -f`/`realpath`/自定位程序拿到沙箱真实路径 | 中 |
| 硬链接 | link/linkat 被替换成复制 | st_nlink 恒 1、inode 不同一；ln/备份/包管理器硬链接优化退化 | 高（需确认为何不用真 linkat）|
| 权限位 | 9 位 rwx 在盘上，无主体语义 | 无 umask hook、无 sticky/setuid/setgid、无 chown/ACL；setuid 被 SIGSYS 假成功 | 部分低/多用户不可行 |
| /dev | guest 直通 Android 真 /dev；LobOS 自己的 /dev 仅控制面元数据 | 系统声明的设备模型 guest 无法验证；无 mknod；mkfifo 路径未翻译 | 低（mkfifo）/高（虚拟设备）|
| /proc | 直通真实 Android procfs，无虚拟合成 | environ/maps/status/mounts 无 LobOS 视图；/proc/self/exe 无法伪造（elfload 已删，raw exec 路径下正确） | 中（部分）/高（exe）|
| VFS 语义 | 纯字符串前缀替换 + 白名单，无 .. 消解、无符号链接展开 | `/usr/../../data/...` 可穿越虚拟根；statx/openat2 绕过 hook；虚拟根不是真边界 | 中（规范化）/不可行（真边界）|

### 2.3 Shell 与命令环境
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| bash 本体 | 真 bash 5.2.15，语言层完整 | termcap 打成桩（tgetent 恒 -1）；--disable-nls；TUI 显示/行编辑退化 | 中 |
| job control | `set +m` 主动关闭 | jobs/fg/bg/Ctrl-Z 不可用 | 中 |
| 核心工具集 | 29 applet 白名单 busybox + bash/git/jq/rg/openssl/curl | 缺 awk/find/xargs/head/tail/wc/sort/uniq/cut/tr/tee/chmod/touch/date/stat/du/dd、which、procps(top/kill/pkill) | 低（扩 applet）/中（GNU 保真）|
| 环境变量 | HOME/TMPDIR/LANG/TERM/PREFIX 等已设 | 无 LC_*、无 USER/LOGNAME；SHELL 指向靠排序偶然；HISTFILE 未显式设 | 低 |
| 启动文件 | 只有用户级 ~/.bashrc（动态生成函数包装） | 无 /etc/profile、profile.d、bash_completion；终端起 bash -i 非 -l；交互终端与非交互执行两套解析语义 | 中 |
| #! 解析 | exec-path 处理 shebang/env-style | node shim 路径硬编码 `/data/user/0/lobos.os/files/...`（换包名即断）；多参数 shebang 语义偏差；512 字节上限 | 低（去硬编码）/中（语义修正）|

### 2.4 用户与权限
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| 账号体系 | /etc 直通 Android；无 passwd/group；getgrent stub 成空 | 读 /etc/passwd 得 Android 账号表；id/whoami/su 不存在 | 中低（装饰性单 root）|
| uid 视图 | 文档要求 guest getuid=0，实际 node shim 填 1、native 没填（真值 10214） | 同一系统两种身份视图；依赖 id -u 分支的程序走错路 | 低（呈现值统一）|
| 权限执行 | Android 应用层 capability 组 + 底层 rwx 位，两套不通 | 验签不验权限、PermissionLedger 只登记不执行；PathGuard 只拦 RPC 不拦 guest 原生 open | 中 |
| root/提权 | 无 su、无 capability | sudo/su/chroot/unshare/mount/容器运行时跑不起来 | 不可行（硬边界）|

### 2.5 IPC 与网络
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| UDS | 只有宿主控制面私有通道（抽象命名空间 JSON-RPC），native 层零 socket hook | 程序间 UDS 通信无系统级命名/发现机制（nginx+php-fpm、postgres、daemon 风格）| 中 |
| DNS | 完全依赖 Android 真实 /etc/resolv.conf 直通 | 无 LobOS 视图；DNS 行为随宿主 | 中 |
| 端口 | PortBroker 软租约 41000-50999 | 无 <1024 特权端口语义（不可行）；软租约无内核强制 | 低（现状够用）|
| /etc/hosts、/etc/services | 无 | 依赖本地主机名解析的服务不可用 | 低 |

### 2.6 服务管理与 init
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| 服务模型 | SupervisorPool（最多 12 个 InstanceHost）+ 宿主进程内 lambda 内置服务 | RuntimeState 单例 object，多实例并发最后写入者胜出；非 per-unit 状态 | 低 |
| 重启策略 | Restart 枚举 ON_FAILURE/ALWAYS/NEVER，但只处理 NEVER | ON_FAILURE/ALWAYS 无语义差异 | 低 |
| 依赖图 | after 依赖环检测；conflicts 定义但未执行 | 无失败传播/反向 stop 顺序；无 socket activation/FD 传递 | 中 |
| daemon/PID文件 | 无标准 daemonize/PID 文件 | 守护进程靠 Android Service 包装，非 Linux 语义 | 中 |
| kill 审计 | KillAudit 按 Android 包级，不按 programId/generation | 无法回答"哪个 guest unit 被哪个信号杀的" | 低 |

### 2.7 运行时与工具链
| 能力 | 现状 | 差距/影响 | 难度 |
|---|---|---|---|
| libc/链接器 | glibc + musl 双 loader，PT_INTERP 路由；自带 ldconfig 逻辑 | ld.so.cache/RPATH/RUNPATH 语义靠环境变量模拟；无标准 ldconfig 命令 | 中 |
| 运行时 | node/bash/git/jq/rg/openssl/curl 已组件化；claude 用官方 musl 版 | 无 python/perl/ruby 通用运行时；版本靠组件钉值非包管理 | 中 |
| 包管理 | 组件供应体系（component-sources.json + pieces + 验签） | 是"产品预装"不是"系统包管理"：无依赖解析、无 apt/apk 式按需安装 | 高 |
| 编译工具链 | 设备上有 LLVM sysroot（为 koffi/node-pty 编译） | 无 make/cmake 通用构建入口；编译能力是产品特化非系统服务 | 中 |
| locale/时区 | LANG=C.UTF-8；/etc/zoneinfo 直通 Android | 无 iconv、无 locale 生成、无系统时区配置 | 中 |

## 三、无 root 硬边界（代码层补不出来，必须如实声明）

以下不是工作量问题，是 Android 应用进程安全模型直接禁止：

| 硬边界 | 证据 |
|---|---|
| 真正 uid 0 / 多 uid 切换 / setuid 位提权 | CapEff 全 0，CapBnd 无 CAP_SYS_ADMIN，userns 不可用（NATIVE-LINUX-APPROACH-ANALYSIS.md:15-21）|
| chroot / pivot_root / mount namespace / 隔离根 | chroot/unshare/pivot_root/CLONE_NEWNS 全仓零实现，通用 APK 无 CAP_SYS_CHROOT（CompatSemantics.kt:93-111）|
| mount / tmpfs / rootfs 切换 | mount tmpfs 失败（无 CAP_SYS_ADMIN）|
| CAP_SYS_ADMIN / CAP_NET_RAW 等 capability | CapBnd 内核给定，进程不能自行扩权 |
| 跨程序强制隔离（不同 uid 各一套文件）| 所有程序同 APK uid，rwx 位没有第二个主体 |
| /proc/self/exe 伪造 | 用户态无法伪造，靠 raw exec 保证指向真实程序（elfload 已因此删除）|

**设计含义**：LobOS 的"通用能力"目标应定义为"用户态能做到的 POSIX 语义尽量还原 + 硬边界用显式替代形态（如应用 capability 组）"，而不是"等价于 Linux 内核"。文档和 UI 都不能把 lobos:sys 能力组描述成 POSIX root。

## 四、确定性错误（优先修，不是"缺失"而是"错"）

1. **身份双轨**：getpid=4242 与 /proc/账本/kill 用真实 PID 并存——父子都报 4242，依赖 PID 作唯一键的程序（锁文件、TLS cache）出错，`kill 4242` 打错进程。
   最低成本修正：统一为宿主真实 PID，去掉 4242 合成；代价是放弃"隐藏宿主 PID"的假象。
2. **文档与实现不一致**（会误导后续判断）：
   - open-fallback：文档说"EACCES 回退 HOME"，代码已删（本轮修）
   - uid：文档要求 getuid=0，node shim 填 1、native 没填
   - setpgid：CompatSemantics.kt:60 断言"无 setpgid 权限"，但给自己的子进程调 setpgid 不需要特权，且无设备侧证据——应实测后再定设计取舍
3. **TerminalProfile 的 TMPDIR 写死 `$base/.tmp`**，与 RuntimeEnvironment 的 cacheDir 指向不一致（/tmp 分裂修过但 shell profile 这条残留）。
4. **node shim 路径硬编码**包名/uid，换包名或多用户即断。

## 五、补齐方案（分层 + 分阶段）

原则：**先统一语义地基，再扩基础环境，再建系统服务，最后碰高级能力**。
每阶段独立可验证，不跨阶段一次性大改。

### 阶段 0：语义地基（最高优先，解决"通用"的根）
- 0.1 身份统一：去掉 getpid=4242，全链路用宿主真实 PID；node shim 的 getuid/getgid 改 0 与文档对齐；native 补 getuid/geteuid/getgid LD_PRELOAD 返回 0（呈现值）
- 0.2 exec 统一：补 posix_spawn/execvpe 拦截；EXEC 找不到时统一走 VFS+loader 改写，不再 dlsym(RTLD_NEXT) 绕过；Java 侧补 PATH 查找与 ENOEXEC->/bin/sh 回退
- 0.3 VFS 规范化：翻译层加 `.`/`..` 消解；readlink 返回目标反向映射；补 statx/openat2 hook（openat2 被 seccomp 拦需 libc 回退）
- 验收：一套程序在交互终端与非交互执行下解析到同一程序；自定位程序拿到一致的虚拟路径；PID 相关工具不再错乱

### 阶段 1：基础环境（性价比最高，一次改动覆盖大量脚本）
- 1.1 BusyBox applet 扩充：加 which/awk/find/xargs/head/tail/wc/sort/uniq/cut/tr/tee/chmod/date/stat/id/whoami/top/kill/pkill（重跑件固化与判据库）
- 1.2 标准环境变量：补 USER/LOGNAME/LC_ALL/LC_CTYPE/LC_MESSAGES、显式 HISTFILE/HISTSIZE；SHELL 改显式策略不靠排序
- 1.3 系统级启动文件：建 /etc/profile、/etc/profile.d/、/etc/bash_completion；终端改 bash -li
- 1.4 去硬编码：node shim 路径从 env 读；TerminalProfile 的 TMPDIR 读统一来源
- 验收：标准 shell 脚本（含 awk/find/xargs/which）可直接跑；`id -u`/`whoami` 输出一致；node CLI 在非 lobos.os 包名下也能跑

### 阶段 2：系统服务（正确性与可控性）
- 2.1 通用 kill(pid,sig)：同 uid 下补 SIGINT/SIGSTOP/SIGCONT 发送与退出信号透传（128+signo）
- 2.2 进程树收敛：descendantsOf 改收敛式重复扫；所有 victim kill 前二次校验 starttime 防 PID 复用误杀
- 2.3 init 状态机：RuntimeState 改 per-program；Restart 三态落实；conflicts 执行；结构化 kill 审计按 programId/generation
- 2.4 rlimit FD 部分：fork 前设 RLIMIT_NOFILE 并合成 /proc/self/limits
- 验收：kill -STOP/-CONT 生效；flapping 程序隔离可诊断；FD 限制生效

### 阶段 3：高级能力（成本高，按需推进）
- 3.1 作业控制：先实测 setpgid/tcsetpgrp 在 app 域是否可用；可用则改 UI 逐字节输入 + 去 `set +m`，让 Ctrl-C/vim/less 可用；不可用则记录硬边界
- 3.2 /proc 顶层文件合成：uptime/stat/meminfo 等全局文件，配合 BusyBox top/free
- 3.3 PathGuard 下沉：把 RPC 层规则下沉到 native open/openat，堵住 guest 绕过
- 3.4 通用 UDS 注册与 /etc/hosts：为程序间通信提供命名约定
- 验收：vim/less 可用；top/free 显示 LobOS 视角；受保护路径原生访问被挡

## 六、优先级排序（考虑收益、风险、依赖）

| 序 | 项 | 收益 | 风险 | 阶段 |
|---|---|---|---|---|
| 1 | 身份统一（去 4242、uid 呈现值一致）| 消除确定性错误 | 低-中 | 0.1 |
| 2 | BusyBox applet 扩充 | 一次覆盖最多脚本 | 低（需重固化）| 1.1 |
| 3 | 标准环境变量 + 启动文件 | 大量脚本兼容 | 低 | 1.2/1.3 |
| 4 | 通用 kill + 退出信号透传 | 进程管理能力 | 低 | 2.1 |
| 5 | 去硬编码（node shim/TMPDIR）| 通用性（换包名/多用户）| 低 | 1.4 |
| 6 | exec 统一 + VFS 规范化 | 通用语义根 | 高（动执行核心）| 0.2/0.3 |
| 7 | 进程树收敛 + init 状态机 | 可控性 | 中 | 2.2/2.3 |
| 8 | 作业控制 | TUI 体验 | 中-高（依赖实测）| 3.1 |

## 七、与已完成工作的衔接

本轮之前已完成的分层与去 Android 依赖，是这次能力建设的前提：
- 宿适配（host/）与系统本体（kernel/services）已物理分开；kernel 层 Context 依赖已清零
- PathProvider/KernelLogger 等 SPI 已建立，阶段 0/1 的改动可沿用这些 SPI 注入能力
- Native ABI 契约（11 键）已建立，阶段 0.2 的 exec 改动有一致性校验兜底
- elfload 已删（它破坏 /proc/self/exe），阶段 0.1 的身份统一与这个决定一致

## 八、审计边界

- 所有结论来自静态逐文件阅读 + 文档核对，未做逐项真机验证（少数例外如 setpgid 需实测）
- serviceinit/toolchain 两个维度的结论已在 process 报告第八节、shell 报告第三节覆盖，未单独成篇
- 严重程度是工程判断，不是用户决策；本审计不改任何代码

# LobOS 系统能力审计复检：设计评价而非 Linux 对标

基线 c2838481 · 2026-11-26 · 只读复检，不改代码
本文对 SYSTEM-CAPABILITY-GAP-ANALYSIS.md 的方法论做修正。原审计用"Linux 有、我们没有"作判据，
把大量在 Android 无 root 环境下正确的设计误判为缺陷。复检后以本文为准。

## 一、方法论修正：三类判据

原审计的根本偏差：把"环境约束下的正确设计"当成"相对 Linux 的差距"。
Android APK 是生存环境（硬件层），我们不是 Linux 内核，也不该是。判断一项要不要改，
不看"Linux 怎么做"，而看它属于哪一类：

### A 类：必要的环境适配设计（不是缺陷，不因"和 Linux 不同"而改）
判据：在无 root、无 mount/chroot、单 APK uid 的约束下，这是可行且正确的解法。
对待方式：承认它是对的，不再列入"差距"。评估它的**精巧性、确定性、架构契合度**，
但不是"向 Linux 对齐"。

### B 类：方向正确但实现待精巧化的设计（真正的工作重心）
判据：设计思路成立，但存在判据分散、契约不明、状态不一致、覆盖不完整、职责错位等问题。
对待方式：这才是核心价值所在——让关键节点更确定、更自洽、更可验证。

### C 类：真正缺失的系统通用能力（吸收 Linux 的重要环节）
判据：Linux 有、我们也该有，且环境允许（不依赖特权），缺失导致通用软件跑不起来。
对待方式：补齐。这是"通用能力"的真正增量。

### D 类：硬边界（不可补，如实声明）
判据：Android 应用安全模型直接禁止，代码层补不出来。
对待方式：明确替代形态，不假装能补。

## 二、关键设计节点重新定性

下面四个节点是原审计误判最重的地方，逐一说明"为什么它是正确的设计"以及"真正该改进什么"。

### 2.1 用户态执行兼容（原判"三条通路不统一是缺陷"）
**重新定性：A 类，正确的系统级设计。**

这不是 hack。在 untrusted_app 下：
- 不能注册 binfmt_misc，不能 chroot，不能 mount
- 早期"app_data noexec"是真象：exec hook 注入了 bionic 的 LD_PRELOAD=liblobosposix.so，
  foreign/静态 ELF 加载不了这个 bionic so 才失败；raw_execve 实测证明内核**能**执行 app_data ELF
- 因此用 raw_execve + 清 LD_PRELOAD 放行静态 ELF 是让 /proc/self/exe 正确的**唯一正确解**
  （codex 自定位包资源依赖这一点）

三条通路（ProcessBuilder+argv 改写 / LD_PRELOAD hook / native PTY）不是错，是三类执行场景的合理分流。

**真正的问题（B 类）：**
- 执行形态判据分散在 Kotlin `ExecEntry`（`contains("ld-linux")`，ExecEntry.kt:117）与
  C `exec-path`（`strstr(interp,"ld-linux")`，exec-path.c:572）两处，两处各算一次——
  问题不是"有判据"，而是**判据没有单一事实源**，改一处漏一处就会行为分裂。
- exec-path 的 `execvp` 找不到时 `dlsym(RTLD_NEXT)` 直接调真函数（exec-path.c:719-723），
  绕过后续 VFS/loader 改写——这是**路由确定性问题**：同一类 exec 有的走改写有的不走。
- `argv[0]` 被改成 loader 路径——这是**契约问题**：需要明确"哪些程序依赖 argv[0] 时该保留原值"。

**不是问题（原审计误判）：** 静态 ELF 走 raw_execve 绕过 hook 是特性，不是缺陷。

### 2.2 虚拟路径根（原判"纯字符串替换、绕得过是缺陷"）
**重新定性：A 类，正确的系统级设计。**

用户态路径翻译 + 白名单是无法 mount/chroot 情况下唯一可行的文件系统虚拟化方式。
`/etc` 刻意不映射（vfs-root.h:104-107）是为了让真实 passwd/resolv.conf/zoneinfo 可见——
这是深思熟虑的取舍，不是遗漏。字符串前缀替换 + overlay 回退是对的设计。

**真正的问题（B 类）：**
- 翻译契约没有单一文档化来源：哪些路径直通、哪些映射、overlay 什么时候回退，
  散在 vfs-root.h 的规则表与注释里。需要固化成规格，让实现可验证。
- `readlink` 只翻输入不翻返回 target（tmp-paths.c:119-132），虚拟路径对不自省的程序透明，
  但对 `realpath`/自定位程序泄漏真实路径——这是**一致性/确定性问题**，
  不是"路径虚拟化做错了"。
- `..` 不消解（vfs-root.h:32-34 明说纯前缀替换）——由于 `/data` 本就在白名单直通，
  这不是安全边界漏洞，而是**结果可预测性**问题：翻译结果应该是明确定义的。
- `statx/openat2` 无 hook、被 seccomp 拦返回 ENOSYS——这是**覆盖度**问题：新 syscall 要跟进，
  但受 libc 回退限制，不能全靠 hook。

**不是问题（原审计误判）：** "虚拟根不是真内核边界"——本来就不该是，这是形态决定的，
不该按 chroot 标准评判。

### 2.3 用户与权限模型（原判"两套不通、无主体语义是缺陷"）
**重新定性：A 类为主，正确的环境适配设计。**

单 APK uid 是 Android 铁律。多用户/多 uid/setuid 位提权在 untrusted_app 下不可能。
因此：
- 应用层 capability 组（`lobos:dev`/`lobos:sys`/`lobos:cross-app`，ApiSpec.kt:11-14）是
  对"无法做 POSIX 权限边界"的**正确替代**，它管的是"程序能调哪些系统能力"。
- SIGSYS 对 setuid/setgid 假成功返回 0（app-sigsys.c:22-59）是让 glibc/Bun 不崩溃的
  **必要妥协**，不是"权限模型造假"。
- `getpid/gettid` 在 SIGSYS TRAP 路径返回固定值——是权宜之计，需要评估适用边界。

**真正的问题（B 类）：**
- PathGuard 只拦桥 API（CapabilityBroker.kt:1497-1599），不拦 guest 原生 open——
  这是**架构不一致**：同一套保护语义在两个层面执行力度不同。要么下沉到 native open/openat，
  要么明确声明"PathGuard 只管桥层，不管原生"，不能两者都是。
- node shim `getuid()=1`（TerminalProfile.kt:78-79）与 native 真值 10214 与文档要求 0——
  这是**呈现值不统一**的确定性问题，与"能不能做 root"无关。
- PermissionLedger 只登记不执行（PermissionLedger.kt:37-56,90-107）——
  这是**职责不完整**：它到底是审计记事本还是决策点，需要定性。
- PackageVerifier 验签不验 capabilities——这是**信任链不闭合**，但补法是"把能力元数据纳入验签"，
  不是"引入 POSIX 权限"。

### 2.4 PTY 与作业控制（原判"job control 关闭是缺陷"）
**重新定性：A 类为主，但有一处真问题。**

PTY 宿主做了 setsid + TIOCSCTTY + dup2（pty-session.c:235-251），
**session leader 和 controlling terminal 是真的**——这是正确的 PTY 实现。
`set +m`（TerminalProfile.kt:153）大概率是为了规避 Android 沙箱里进程组设置的未知行为，
是审慎选择，不能未经实测就判为缺陷。

**真正的问题（B 类）：**
- UI 只发整行文本（TerminalContract.kt:11 的 Input 只有字符串），VINTR/VSUSP 进不了 PTY——
  这是**能力缺失**：即使 job control 开了，也无法从 UI 发 Ctrl-C/Ctrl-Z。
- `CompatSemantics.kt:60` 断言"通用 APK 无 setpgid 权限"，但给自己的子进程调 setpgid
  在 Linux 语义下不需要特权，且仓内无设备侧证据——这是**未验证的断言支配了设计取舍**，
  应该实测后再定，不能拿它当设计前提。

## 三、逐维度复检归类

把原审计矩阵每一项重新归类。A=必要设计（不再当差距），B=待精巧化（工作重心），
C=真缺通用能力，D=硬边界。

### 3.1 进程与执行
| 项 | 原判 | 复检类 | 复检判断 |
|---|---|---|---|
| 三通路 exec | 缺陷 | A+B | 分流是对的；问题是判据无单一源、execvp 绕过改写 |
| raw_execve 绕过 hook | 缺陷 | A | 特性，保证 /proc/self/exe 正确，不改 |
| 无 posix_spawn 拦截 | 缺失 | C | 真缺，但要看实际软件是否用到 |
| shebang 语义偏差 | 缺失 | B | 环境所限的解析，问题是多参数/超长边界未定义 |
| wait 只在 PTY 完整 | 缺失 | B | 普通程序缺信号退出码，属确定性 |
| 发信号只 SIGTERM/KILL | 缺失 | C | 同 uid 可做，真缺 |
| 进程树按需快照 | 缺陷 | A+B | 无 subreaper 是环境所迫；问题是 kill 前校验 PID 复用 |
| job control 关闭 | 缺陷 | A+B | 关闭是审慎；真问题是 UI 发不了控制字符 |
| rlimit/cgroup | 缺失 | C(部分)/D | FD 限制可做；硬隔离不可做 |
| 身份双轨 getpid | "确定性错误" | B | 是假成功权宜之计；问题是全局固定值污染了正常路径，需实测边界 |

### 3.2 文件系统与路径
| 项 | 原判 | 复检类 | 复检判断 |
|---|---|---|---|
| FHS 目录不全 | 缺失 | C | 补目录是安全的真增量 |
| HOME=filesDir | 缺失 | A+B | 单用户下指向私有根是对的；问题是与虚拟 /usr 不在同一视图，需明确 |
| 符号链接真实路径 | 缺失 | B | 一致性问题，非设计错误 |
| 硬链接→复制 | 缺陷 | A+B | 可能是跨 mount/SELinux 的必然；需确认当初为何不用真 linkat，再决定是否精巧化 |
| 无 umask/sticky/setuid | 缺失 | D+A | 单 uid 下多用户语义无意义；umask 可补属 C |
| /dev 直通真 Android | 缺陷 | A | 无 mknod 权限，直通是正确解；LobOS /dev 是控制面元数据，定位不同 |
| /proc 直通 | 缺陷 | A+B | 无 mount 只能直通；问题是全局文件可合成以支撑 top/free |
| VFS 字符串替换 | 缺陷 | A+B | 正确设计；问题是契约未固化、readlink 不一致、新 syscall 覆盖 |
| .. 穿越 | 缺陷 | A+B | 非安全漏洞（白名单本就直通）；是可预测性问题 |

### 3.3 Shell 与权限（合并复检）
| 项 | 原判 | 复检类 | 复检判断 |
|---|---|---|---|
| busybox applet 少 | 缺失 | C | 真缺，且不依赖特权，收益最高 |
| termcap 桩 | 缺陷 | A+B | 交叉编译 bionic 依赖的取舍；问题是 TUI 显示退化需实测 |
| LC_*/USER 缺 | 缺失 | C | 真缺，低风险 |
| 启动文件只有 ~/.bashrc | 缺失 | A+B | 单用户下合理；问题是交互/非交互两套解析语义 |
| node shim 硬编码路径 | 缺陷 | B | 确定性/可移植性问题，真该改 |
| 单 uid/capability 组 | 缺陷 | A | 正确的环境适配，不是缺陷 |
| PathGuard 只拦 RPC | 缺陷 | B | 架构不一致，真该明确或下沉 |
| 权限登记不执行 | 缺陷 | B | 职责未定性 |

### 3.4 服务与工具链
| 项 | 原判 | 复检类 | 复检判断 |
|---|---|---|---|
| 按单元 stop 没有 | 缺失 | C | 真缺，且不依赖特权 |
| ON_FAILURE 不区分退出码 | 缺陷 | B | 逻辑 bug，正常退出被无限重启 |
| after 未填充 | 缺陷 | B | 字段存在但没写，是真 bug |
| 三套 libc 共存 | 缺失 | A+B | env+loader 改写是正确解；问题是判据分散 |
| ld.so.cache 无 | 缺失 | A | 宿主 linker64 不读 cache，补了也没用，不算缺陷 |
| gcc 无 | 缺失 | C | 真缺但需权衡体量 |
| tzdata/locale/iconv 无 | 缺失 | C | 真缺的标准件 |
| 门禁 required=false | 缺陷 | B | 机制根因，真该改 |
| manifest schema 不一致 | 缺陷 | B | 确定性 bug |

## 四、复检后的核心判断

1. **原审计最大的错误是把 A 类当缺陷**：用户态执行、路径虚拟根、单 uid 权限、PTY 实现、
   libc 共存——这些都是在 Android 无 root 环境下深思熟虑的正确设计，不该因为"和 Linux 不同"
   就列入差距。之前写的"适配层堆砌不是通用语义"这个判断，语气过重且方向偏了。

2. **真正的核心价值在 B 类**：关键节点的设计是对的，但存在五类可精巧化的问题：
   - 判据无单一源（exec 形态判断两处、libc 路由两处）
   - 契约未固化（VFS 翻译规则散在代码注释、能力组语义没文档）
   - 呈现值不统一（getpid/uid、PathGuard 两层力度、交互/非交互解析）
   - 职责未定性（PermissionLedger 是审计还是决策、LobOS /dev 是控制面还是 guest 视图）
   - 未验证断言支配设计（setpgid、linkat 的取舍）
   这些问题不解决，系统就"能用但不确定"——这才是"不够通用"的真正含义。

3. **C 类通用能力要补，但要克制**：busybox applet、标准环境变量、按程序 stop、通用 kill、
   门禁、tzdata/locale/iconv——这些是真缺且不依赖特权，应该补。但 posix_spawn 拦截、
   完整依赖图、apt 等价物这些要么高成本高要么环境收益低，要按实际软件需求排期，不能全量对标。

4. **身份双轨需要单独实测再定**：getpid=4242 是 SIGSYS 假成功的权宜之计，
   但"全局固定"是否污染了正常 getpid 路径、是否真造成多进程冲突，需要真机验证，
   不能仅凭静态分析就判"确定性错误"。这体现"对既有设计保持尊重，用证据说话"。

## 五、修正后的方案：两条线

### 线一：强化现有设计（B 类，核心价值）
不新增大能力，让已正确的设计更确定、更自洽。每项都可独立验证。

- 1.1 判据单一源：exec 形态判据（ELF/PT_INTERP/shebang）从 Kotlin+C 两处收敛为一份共享规格，
  两端都引用它；libc 路由判据同理。
- 1.2 契约固化：把 VFS 翻译规则（直通/映射/overlay 回退）写成规格文档 + 校验脚本，
  让实现可验证；明确"虚拟根不是安全边界"的形态定位。
- 1.3 呈现值统一：getpid/uid 的假成功边界实测后固化；node/native 的 uid 呈现值对齐；
  PathGuard 明确只管桥层还是下沉 native；交互/非交互执行语义对齐。
- 1.4 职责定性：PermissionLedger、LobOS /dev、能力组的定位各写清楚，消除"看起来有其实没有"的错觉。
- 1.5 未验证断言验证：setpgid、linkat 两处取舍做真机实测，用证据决定保持还是改。

### 线二：补真缺通用能力（C 类，克制推进）
只补不依赖特权、且被实际软件需要的：

- 2.1 BusyBox applet 扩充（which/awk/find/xargs/head/tail/wc/chmod/id/whoami 等）
- 2.2 标准环境变量（USER/LOGNAME/LC_*）与显式 HISTFILE、SHELL 策略
- 2.3 装机门禁：把运行时/构建器 required 从 false 改为 true 或引入基础能力分级
- 2.4 按程序独立 stop + ON_FAILURE 按退出码区分 + after 字段填充（服务三薄点）
- 2.5 通用 kill(pid,sig) 与退出信号透传
- 2.6 tzdata/locale/iconv 标准件
- 2.7 manifest schema 与 versions[] 对齐、node shim/TMPDIR 去硬编码

### 线三：高成本项按需求排期（不主动全做）
posix_spawn/execvpe 拦截、完整依赖图与 target、apt 等价物、gcc、无感 glibc exec 路由——
这些要么高成本要么受环境限制，等有明确软件需求时再立项，不列入当前通用能力建设。

## 六、优先级（复检后重排）

| 序 | 项 | 线 | 理由 |
|---|---|---|---|
| 1 | 判据单一源（exec/libc）| 线一 | 消除行为分裂的根，风险低收益高 |
| 2 | 装机门禁 | 线二 | "系统薄"的机制根因 |
| 3 | 服务三薄点（stop/退出码/after）| 线二 | 真 bug，低风险 |
| 4 | BusyBox applet 扩充 | 线二 | 一次覆盖最多脚本 |
| 5 | 呈现值统一 + 契约固化 | 线一 | 让系统"确定" |
| 6 | 标准环境变量 + 去硬编码 | 线二 | 通用性 |
| 7 | 通用 kill + 退出信号 | 线二 | 进程管理能力 |
| 8 | tzdata/locale/iconv | 线二 | 标准件补齐 |
| 9 | 身份双轨实测后处理 | 线一 | 需证据，不急判 |
| 10 | 高成本项 | 线三 | 按需立项 |

## 七、复检边界

- 本文是方法论复检，不推翻原审计的事实证据（file:line 仍然有效），只修正"要不要改、为什么改"的判断
- A 类项不再列入差距清单，但仍保留在原文供追溯；本文是评价标准的修正，不是事实的否定
- 所有"需实测"项（setpgid/linkat/getpid）明确标注，不以静态分析下结论
- 不改任何代码，产品状态未动

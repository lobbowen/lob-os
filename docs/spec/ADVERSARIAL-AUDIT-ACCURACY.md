# LobOS 对抗性审计：准确性评估与对既往判断的反证

基线 23f07d24 · 2026-11-26 · 只读审计，不改代码
方法：5 个独立审计角度（验收盲区/exec反证/路径权限反证/近期改动反向审查/第三方视角），
要求找反例、找失败边界，禁止用"环境所迫"做循环论证。本文结论与之前所有审计冲突时，以本文为准。

## 零、对方法论的二次修正（推翻我自己的复检）

我在 SYSTEM-CAPABILITY-RECHECK.md 里把"在无 root 下可行且正确"当成判据。对抗审计指出这是
**循环论证**：约束只能解释"为什么只能这样做"，不能证明"这样做是准确的"。
"环境所迫所以合理"不是正确性证据。这份复检本身犯了它想纠正的错。

同时，我在复检里把"getpid=4242 全局固定"降级为"需实测再判"，是迎合用户立场、降低警惕。
准确表述应该是：
- "触发 SIGSYS TRAP 的 getpid/gettid 返回固定 4242"是**静态确定的事实**（app-sigsys.c:77-79、
  sigsys-universal.c:117，handler 装进 liblobosposix.so 且 PTY host 显式加载），不需要实测
- "哪些 guest 程序因此实际出错"才需要实测
把两者混为一个"需实测"是不准确的，并把这个问题排到第9位会掩盖真实风险。这一点必须纠正。

本文不尊重任何"既有设计"，只尊重证据。

## 一、最尖锐的结论：目标与验收标准是矛盾的

目标是"准确的跑通"，但当前工程**没有任何一条自动链路在验证行为准确性**。

| 验证层 | 事实 | 证据 |
|---|---|---|
| Kotlin/native 单元测试 | 0 个；唯一测试是 1 个 Compose UI 点击测试 | find container -name '*Test.kt' 实测 |
| 提交前总校验 | 只查分层/目录/Native ABI 键名/OTA 锚；ABI 只验证键名出现，不查值语义 | verify-engineering-standard.sh:23-26；verify-native-config.sh:14-26 |
| 可执行行为判据 | check-exec-real-run.sh 真跑的只有一个 shebang 文件 `echo hi`；ELF-DYN/ELF-EXEC 明写"测不了，归设备侧" | check-exec-real-run.sh:238-256 |
| 期望值来源 | expected-behaviour.json 每条 source 都是"07 本轮当场定"，人写的期望，不是 Linux 基线 | expected-behaviour.json:11,24-70 |
| 设备侧判据库 | m0-criteria 131 键仅 2 个带探针；piece-criteria 81 键 20 个带探针，其中 14 个 required:false | run-m0-criteria.js:14 + python 计数 |
| 差分 Linux 对照 | 0 | 全仓无差分测试脚本 |
| 门禁有效性 | 验证脚本已与代码脱节且跑红无人拦：check-exec-shape-tiers.py 期待的旧 ExecEntry/ProcessSupervisor 写法已不存在 | 该脚本最后提交 1b8675d，源码最后提交 956c1dd |

**结论：所有"BUILD SUCCESSFUL、总校验 OK、终端 echo 回显"只能证明跑通，不能证明准确。
之前反复用这些作为验证闭环，是把跑通误当准确。**

### 1.1 当前验收会通过但行为实际不准确的五个具体场景（静态代码路径推断）

1. `sh` 被静默替换成 `bash`：exec-path.c:371-373，PATH 里找不到 sh 时用 bash 替代。标准 Linux 的
   `sh`（dash/busybox ash）与 bash 在 set -e、算术、echo 转义上有可观察差异。验收只查版本串。
2. `#!/usr/bin/env VAR=x prog` 的 env 语义错：exec-path.c:495-503 把 `VAR=x` 追加到 envp 尾部
   而不替换同名键（env_put :94-101），getenv 通常取第一个，结果取决于调用方，与 Linux 的覆盖语义不同。
3. `/tmp` 与 `/lib` 的视图随环境漂移：`/tmp` 按 TMPDIR 翻译、`/lib` 按沙箱节点存在性动态回退
   （vfs-root.h:70-79,136），Linux 中这些是固定路径语义。无任何路径行为测试。
4. guest 无法安装自己的 SIGSYS handler：sigsys-universal.c:163-209 对 SIGSYS 的 sigaction/signal
   一律假装成功但装的是 LobOS handler，sigprocmask 强制删除 SIGSYS。全仓无信号语义测试。
5. 所有 node 启动被注入私有参数：exec-path.c:440-460 无条件插入
   `--expose-internals --require .node-posix-shim.js`，`node -p 'process.argv'` 输出与 Linux 不同，
   验收九件里没有 node，不会触发。

### 1.2 建立"准确性"验证的最小判据

不是再加冒烟测试，而是引入**差分 oracle**：同一探针在可信 Linux/arm64 基线与 LobOS 各跑一次，
比对 (stdout bytes, stderr bytes, exit status, terminating signal) 四元组，全等才过。
每个维度配正向+反向样本（防止判据零命中）。六个最小探针：

- exec：argv0 回显；execve(envp={A=1}) 后打印 environ；execvp("sh") 看 shell 特性
- 路径：stat/open("/tmp/p")、"/lib/x"、"/usr/lib/x" 打印 realpath 与内容
- 权限：000 模式文件 open 记录 errno
- 信号：sigaction(SIGUSR1) + raise 看 handler；sigaction(SIGSYS) 看是否被替换
- 环境变量：子进程打印完整有序 environ；同名键覆盖；env -i 清空
- 退出码：0 / exit(42) / SIGTERM 杀死 / 不存在的可执行文件

硬边界项（seccomp、单 uid）显式标记豁免并写明理由，不能靠"环境所迫"一笔带过。

## 二、对 exec 判断的反证

既往判断"raw_execve + 清 LD_PRELOAD 是正确解"必须收窄：

- **仅对"单次执行静态 ELF、不再二次 exec、不依赖 SIGSYS handler"成立**。
- 静态 ELF 走 raw_execve 后，它内部若再 exec 动态程序，完全脱离 compat 层：
  hook 是 LD_PRELOAD/PLT 覆盖，raw_execve 用 syscall(__NR_execve) 绕过 PLT 且删了 LD_PRELOAD，
  新镜像不加载 hook，静态程序的 execve 通常是直接 svc 指令。父进程二次 exec 依赖 compat 路由的程序
  （如静态 bash 执行 node）会走错路径。证据强（代码路径），真机未测具体程序。
- **清 LD_PRELOAD 并非全局**：Kotlin ProcessSupervisor.kt:59 remove 之后 :60 putAll(env) 会重新加回；
  PTY host 是刻意注入（PtySession.kt:656-658）；native exec-path 多处 malloc fallback 不清；
  execvp 找不到时 dlsym(RTLD_NEXT) 直接调真函数完全绕过；posix_spawn/execveat/fexecve 零拦截。
- **raw_execve 成功但结果仍不准确**：static 分支用 orig_env 而非 merge 后的 envp，缺 LOBOS_* 键
  （路径翻译/诊断可能失效）；argv[0] 在不同分支语义不一；malloc 失败 fallback 的 errno 误导；
  PtySession.kt:794 在 done.await() 前算 droppedNote，输出截断但报告"无截断"。
- 删除 elfload 的边界：raw_execve 成功则无影响；EACCES 时旧的 loader 兜底消失，直接失败。
  "哪些程序确实因此失败"未验证，只有注释和 commit message 说"raw execve 实测"。

## 三、对路径与权限判断的反证

### 3.1 "字符串前缀替换是正确设计"被部分推翻

- **overlay 静默回退返回宿主文件**：vfs-root.h:136 在沙箱节点不存在时 return 原路径，
  判据是 F_OK（:20）只测存在不测类型/可读性。`open("/bin/sh")` 沙箱无节点时会打开 Android 的 sh；
  `open("/lib/libc.so.6")` 会拿到 bionic libc。对抗审计用 vfs-root.h 原样编 harness 实测复现。
- **`/etc` 不映射与验收标准直接矛盾**：VIRTUAL-ROOT-NAMESPACE.md:83 要求"只认标准路径的程序
  不加环境变量能找到 /etc 资源"，但 vfs-root.h:104-107 刻意不映射 /etc，同一逻辑对象走 RPC 与
  走 native open 落到两个文件。"有意取舍"能解释为什么不映射，不能解释为什么同时保留这条验收标准。
- **`/tmp` 落点与规范分裂**：规范写 `/tmp -> $VROOT/.tmp`，代码跟随 TMPDIR（cacheDir），
  TerminalProfile.kt:280 又写死 `$base/.tmp`——三种视图。最近改动只改了 native 侧。
- **statx shim 已存在却没下沉**：scripts/toolchain/node-native/compat/statx.c:7-31 有完整 statx→fstatat
  shim，fstatat 有 vfs hook，但 guest glibc 直接导出真 statx（nm -D 实测 T statx@@GLIBC_2.28），
  绕过全部 hook。这是"已有方案未推广"，不是"环境所迫不能全靠 hook"。
- **真 linkat 未实测即降级**：link-interpose.c:54-59 无条件 copy_excl，flags 丢弃。同 app uid 同
  文件系统下真 linkat 不需要特权，minSdk=29 支持；复制方案损失 st_nlink/inode 同一性/原子性。
  "需确认为何不用"拖了很久没做，属于被忽略的可行替代。

### 3.2 capability 组不是"正确替代"那么简单

具体越权场景：一个只声明 base capability 的程序，RPC fs.write 写 `$filesDir/etc/lobos-sysenv`
被 PathGuard 拒绝（CapabilityBroker.kt:1536-1539），但它是同 uid 进程，用自己的 libc open 写
同一路径毫无阻碍（open-fallback.c:21-32 无 PathGuard），而该文件正是 exec-path.c:103-147 注入
每个子进程 envp 的来源。应用层鉴权通过的程序完成了应用层明确拒绝的操作，并污染所有后续进程，
且 bridge-audit.log 不记录原生写入。这不是"Linux 有我们没有"的对标，是系统自己的安全声明
在两个执行面上力度不同，弱的一面覆盖强的一面要保护的对象。

PathGuard 的准确定性：它是 RPC 参数校验器，被过度命名为"完整性保护（S3）"。不能靠"下沉 native
open"治好——静态 ELF 走 raw_execve 天然不带 hook。准确做法是撤销该安全声明，或明确系统当前
不提供系统状态完整性边界。"职责未定性是表象，设计缺陷是实质"。

## 四、第三方视角：系统真实形态与误导点

客观描述：这是 Android 应用进程内的**用户态 Linux 语义翻译层**（Kotlin 22636 行 + C 垫片 2652 行），
作为普通 APK 安装。所谓 kernel 是用户态翻译器，路径靠字符串替换、exec 靠 argv 改写、
/dev 是 JSON 元数据树、进程靠 ProcessBuilder 和 /proc 快照。它能在 APK 壳内自成体系，但行为
正确性依赖宿主 linker64、宿主 /etc、宿主 seccomp 状态——说"独立"不准确，说"Linux 兼容"是部分事实。

**开发者会被误导的点**（看起来像 Linux 但实际不是）：
- 有"kernel"层（实际用户态 shim）；VFS 是文件系统边界（实际字符串替换）；`/etc` 是沙箱自己的（实际宿主）；
- `/dev`/`/proc` 是内核接口（实际合成元数据/直通）；权限是 POSIX（实际 setuid 假成功 + capability 组）；
- getpid/getuid 返回真值（实际 4242 / 1 / 文档要求 0 三值冲突）；能设置自己的 SIGSYS handler（实际被吞）；
- 路径保护是全局的（实际只拦 RPC）。

三个结构性风险（行为不准且无法被发现）：
1. 验证只测跑通/自洽，且人定期望反复被推翻——三轮审计互相否定（ARCHITECTURE-AUDIT 说跑通，
   DEEP 说脚本 PASS 不等于架构成立，RECHECK 又推翻前者判据，DEEP 又说大量 P0 是启发式误报）。
2. 隐式协议 + 静默降级——程序拿到自洽但虚假的世界模型（pid 4242、uid 1、platform linux），
   所有调用"成功"，上层无法用返回值发现异常。这正是"跑通但不准确"的定义。
3. 同一语义多份判据/实现——exec 判据两处、readlink 只翻输入、.. 不消解，两条路径不一致且无测试比对。

**如果只能做一件事提升准确性**：建立行为差分基准（真 Linux/arm64 基线 vs LobOS 对比
stdout/stderr/exit/errno），覆盖 exec/路径/身份/信号四类已知偏差面。对标的是"声称兼容的行为是否
一致"，不是功能清单——这与"不按 Linux 功能清单补齐"不矛盾。

## 五、近期改动反向审查：我引入的三个新问题

1. **PtySession 日志门闩从条件记变成全记**：937659e2 删 ctx 后，pty-diag、F_LENBAD、F_ERROR、
   F_NOTE、reportSlotNotFreed、note 六处 `ctx?.let{logger}` 变成无条件，且删了"宿主上下文缺失就报错"
   的建会话门槛。KernelServices.logger 未注册时是 NOOP，所以是否落盘取决于注册时机——这是行为变化，
   不是纯签名清理。
2. **/tmp 三视图未统一**：native 跟随 TMPDIR（=cacheDir），TerminalProfile.kt:280 仍写死 `$base/.tmp`（filesDir）。
   交互终端 source profile 后与 RuntimeEnvironment 给的子进程 TMPDIR 不同。我之前声称修好了，是不准确的。
3. **getpid 降级为"需实测"掩盖确定性事实**（见零节）。

NotifyRoute/ReadRoute 的 `ctx? -> enabled:Boolean` 改动是正确的显式化（原 null 调用方映射为 false，
非空映射为 true，逐点比对无错），elfload 删除和 KernelHooks 日志统一未发现回调链断裂。

## 六、最终判断（中立，不迎合任何人）

1. **"准确的跑通"这个目标，当前一个自动化判据都没有**。当务之急不是补功能，是建立差分基准——
   没有 oracle，任何人（包括我）说的"准确"都只是"看起来对"。
2. **之前所有"设计正确"的判断都需要加限定**：方向正确不等于实现准确；环境可行为不等于结果正确。
   我之前的复检用了循环论证，这次纠正。
3. **真正该做的工作排序变了**：
   - 第一优先：建差分基准（exec/路径/身份/信号六探针），让"准确"可测
   - 第二优先：修复差分基准必然暴露的问题（overlay 回退、sh 替换、env 语义、SIGSYS handler、
     statx 下沉、linkat 实测、getpid 边界、TMPDIR 三视图）
   - 第三优先：才是之前方案里的 B/C 类（判据单一源、门禁、busybox、服务三薄点）
4. **对用户思路的中立反馈**："不要全量对标 Linux"是对的（不按功能清单补），但"因此现有设计都对"
   是错的。正确姿势是：只对"声称兼容的行为"建差分一致性门槛，硬边界显式豁免，其余偏差用证据修。
   这两件事不矛盾，之前把它们对立起来是我没分清。

## 七、审计边界

- 所有"设备侧表现"是静态代码路径推断，本轮未在真机执行；但代码路径确定，且现有验收确实不覆盖，
  风险判断有依据。凡强证据（本机 harness/读码/实跑脚本）与推断分开标注。
- 本文推翻的是判断和优先级，不是所有事实；之前的 file:line 证据仍有效。
- 不改任何代码，产品状态未动。

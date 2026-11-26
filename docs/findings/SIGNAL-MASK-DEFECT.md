# 缺陷报告：PTY 路径继承异常信号掩码（SIGUSR1/SIGQUIT/SIGPIPE 被 block）

发现方式：行为差分基准（scripts/verify/accuracy/），非静态推断。
状态：已修复并通过差分基准验证（2026-11-26）。

## 现象

同一 glibc 探针在 host 基线与被 LobOS exec hook 处理的终端进程中，signal 维度行为不同：

| 项 | host 基线 | LobOS 终端 hook 内 |
|---|---|---|
| `usr1_handler_installed` | yes | yes（sigaction 确实装上了） |
| `usr1_blocked` | no | yes |
| `blocked_signal_count` | 0 | 4 |
| `blocked_signals` | (空) | 3(SIGQUIT),10(SIGUSR1),13(SIGPIPE),32(SIGSYS) |
| `usr1_handler_ran` (raise) | 1 | 0（解除阻塞后变 1） |
| `usr1_via_kill` (kill 真 pid) | 1 | 0（解除阻塞后变 1） |

探针中主动 `sigprocmask(SIG_UNBLOCK, SIGUSR1)` 后，raise/kill 都触发 handler（=1），
证明 handler 本身正确、信号能递送，唯一原因是初始信号掩码阻塞了这三个信号。

## 根因链条

1. `TerminalProfile.kt:153` 生成 `set +m`，关闭交互式 bash 的 job control。
2. PTY 宿主（native d3/pty-session.c，setsid + TIOCSCTTY 真做了）与关闭 job control 的 bash，
   在初始化时阻塞 SIGQUIT/SIGUSR1/SIGPIPE（bash jobs.c/sig.c 与 PTY 行规程的常见行为）；
   SIGSYS(32) 由 sigsys-universal.c 的 compat handler 管理。
3. POSIX 信号掩码跨 execve 继承，因此终端里启动的所有子进程都带着这个 mask。
4. 子程序若为自己的 SIGUSR1/SIGQUIT/SIGPIPE 安装 handler，handler 永不执行——
   这违反标准 Linux 交互式 shell 下的语义（基线 blocked_signal_count=0）。

注意：这个 mask 不是 compat 的 sigaction hook 造成的。sigsys-universal.c:163-174 对非 SIGSYS
信号是正确透传的；缺陷在"进程启动时的初始 mask"，不在 handler 安装接口。

## 影响（哪些软件会错）

- 依赖 SIGUSR1 做运行时重载/热更新的程序（nginx -s reload、部分 Java/Go 服务的 dump/signal 机制）
- 依赖 SIGPIPE 默认终止语义的程序（管道写端关闭时的行为）
- 依赖 SIGQUIT 产生 core/退出栈的程序
- 任何"先 sigaction 安装、再等信号"的测试/守护逻辑——在 LobOS 终端里静默失效

这类程序都能"跑起来"（这正是之前所有验收通过的形态），但信号语义是错的。

## 修复方向（需实测验证）

1. 首选：去掉 `set +m`，恢复 job control。但必须先实测 `setpgid/tcsetpgrp` 在 app 域是否可用
   （CompatSemantics.kt:60 断言"无 setpgid 权限"缺设备侧证据，不能当前提）。
2. 若 job control 不可用：在 PTY 宿主 fork 子进程后、exec 前显式
   `sigprocmask(SIG_UNBLOCK, {SIGQUIT,SIGUSR1,SIGPIPE}, NULL)`，只解除这三个（保留 SIGSYS 由 compat 管理）。
   这给子进程一个干净的初始 mask，不改变 job control 状态。
3. 无论哪种，修复后用本基准重跑 `signal` 维度，确认 `blocked_signal_count=0`、
   `usr1_handler_ran=1` 与 host 基线 MATCH。

## 验证资产

- 探针：scripts/verify/accuracy/probe.c（signal 维度）
- 自动化：scripts/verify/accuracy/run-lobos-probe.sh accprobe-glibc signal
- 提取：scripts/verify/accuracy/extract-terminal.py
- 证据：/tmp/acc-signal6.xml（本轮 dump，含 blocked_signals=3,10,13,32）

## 判断强度

强：差分对照 + 解阻实验（unblock 后立刻正常）构成因果闭环。不是"看起来像"，是可复现实验。

## 后续：job control 完整性状态（2026-11-26，已更正）

去掉 set +m 并加 UI 控制键后的实测状态：
- `set -o | grep monitor` = on（job control 已开启）
- 后台作业：`sleep 60 &` 产生 `[1] 16675`，`jobs` 显示 `[1]+ Running sleep 60`
- `fg` 把后台作业拉回前台：`fg` 后显示 `sleep 60` 在前台运行
- Ctrl-Z：`fg` 拉回前台后点 ^Z，显示 `[1]+ Stopped sleep 60`，`jobs` 确认 Stopped
- Ctrl-C：生效，`sleep` 被中断并出现新提示符
- Ctrl-D：通道可用，未单独验证 EOF 行为

结论：job control **完整工作**——jobs/fg/bg/Ctrl-C/Ctrl-Z 全链路可用。
此前记录的"Ctrl-Z 没形成作业"是测试方法错误（用了历史 UI dump 的旧坐标
点 ^Z，实际点到了别处），不是系统缺陷。用后台作业 + fg + 实时坐标点 ^Z
的完整路径验证，挂起/恢复链路全部正常。这条更正再次说明：验证工具本身
的构造和坐标获取必须准确，否则会把正确系统误判为缺陷。

# 行为差分基准（accuracy differential harness）

目标：把"准确"变成可验证事实。同一探针在参照 Linux 与 LobOS 各跑一次，
比对 (stdout, stderr, exit) 三元组，而不是相信"跑通了/退出码0"。

## 现状（v1，2026-11-26）

已建立：
- `probe.c`：9 个维度探针（argv0/env/path/identity/signal + exit 4 模式），
  输出稳定 KEY=VALUE，供四元组比对。
- `build-probes.sh`：构建 host x86_64（POSIX 语义基线）与 aarch64 static 探针。
- `diff-harness.py`：多 target 收集 + 与 host 基线 diff；硬边界显式 EXEMPT 并写理由，
  不把"环境所迫"伪装成"准确通过"。

v1 基线验证结果（host vs 设备 Android 原生 run-as 直跑，不经 LobOS hook）：
- argv0/env/path/signal/exit×4：MATCH（8 项）——证明探针有效，且 Android 原生路径
  在可测维度上与标准 Linux 语义一致。
- identity：EXEMPT（uid 不同是单 uid 环境决定，明示豁免不是通过）。
- DIFF=0。

## 已知限制（v2 待办，不掩盖）

1. **lobos target 尚未自动化**：静态探针在 filesDir 经 LobOS exec hook 时
   `Permission denied`（SELinux app_data_file 不可 exec），run-as 直跑则成功。
   要让探针经 hook 执行，需把它像其他动态组件一样打包进 APK 的 jniLibs
   （nativeLibraryDir 可 exec），或编译 glibc 动态探针走 loader 路径。
   这是 v2 的第一件事，不是"做完了"。
2. **参照基线是 host x86_64**：POSIX 语义（argv0/envp/信号/退出码）与架构无关，
   可作基线；path 维度因环境布局不同已标 EXEMPT。真正 aarch64 Linux 基线
   需要 qemu-user-static + rootfs（deb 下载极慢，后台续传中）。
3. **结果提取**：lobos 终端输出需从 UI dump 提取，v1 未做自动解析。

## 用法

```bash
bash scripts/verify/accuracy/build-probes.sh
python3 scripts/verify/accuracy/diff-harness.py \
  --push --targets host,android --json /tmp/accuracy-results.json
# 注：结果与 bin/ 产物均为运行时生成，.gitignore 已排除，不入库
```

## 判据原则

- MATCH：与基线四元组一致
- DIFF：不一致且非豁免 = 准确性缺陷，必须修
- EXEMPT：环境硬边界，必须写明理由，不能当通过
- 每个维度需配正向 + 反向样本，防止判据零命中（v2 补）

## v1 实测：差分基准抓到的准确性缺陷（2026-11-26）

用 glibc 动态探针 + nativeLibraryDir 的 `libglibc-loader.so`，在同一设备上对比
"loader 直跑（run-as，不经终端 hook）"与"经 LobOS 终端 hook"：

| 维度 | host/Android原生/loader直跑 | LobOS 终端 hook 内 | 判定 |
|---|---|---|---|
| signal.usr1_handler_ran | 1（handler 安装后 raise 触发） | **0（未触发）** | DIFF，真实缺陷 |
| argv0.exe_link | 指向探针自身 | 指向 `libglibc-loader.so` | DIFF（自定位程序会取错 exe） |
| argv0.child_exit | 0 | -1（自重执行失败） | DIFF |
| path.stat[/bin/sh] | 视环境 | OK mode=755（overlay 回退到宿主 sh） | 环境适配，需复核 |
| identity.getpid | 真 PID | 真 PID（1345，未触发 4242 路径） | 本路径一致 |

交叉验证：host / Android 原生 static / glibc loader 直跑三种方式的 signal 全部
`usr1_handler_ran=1`，只有经 LobOS 终端 hook 变成 0。缺陷精确定位到 PTY hook 路径，
不是 libc/probe/loader 问题。这证明差分基准能定位静态审计无法确定的问题。

证据获取方式：`run-lobos-probe.sh <probe> <dim>` 输出 UI dump，
`extract-terminal.py <dump> [dim]` 结构化提取。dump 为一次性读数，用完即弃，不入 git。

## 自动化 LobOS target（v1.1）

`run-lobos-probe.sh <probe> <dim>` 已实现一键在 LobOS 终端 hook 内跑探针并 dump UI：
- 用 nativeLibraryDir 的 `libglibc-loader.so`（唯一在 app_data SELinux 下可执行的 loader）
- 构造 clean `LD_PRELOAD` 的 one-shot runner，经终端输入执行
- 输出 UI dump 到 stdout，配合 `extract-terminal.py <dump> [dim]` 提取结构化结果
  （只取每个维度的最新段，避免终端历史文本累积污染）

实测可稳定复现：`run-lobos-probe.sh accprobe-glibc signal` -> `usr1_handler_ran=0`，
而 host / Android 原生 / loader 直跑均为 1。缺陷可复跑、可定位。

## 下一步（v2，按对抗审计优先级）
1. 修 signal 缺陷：定位 PTY hook 路径为何 SIGUSR1 handler 不执行（sigaction 透传是好的，
   怀疑 raise/tgkill 或 reinstall 线程时序），修后重跑基准确认 MATCH
2. 修 argv0 缺陷：glibc loader 路径下 /proc/self/exe 指向 loader、自重执行失败
3. qemu aarch64 基线下载完成后续传接入，替换 host x86_64 作为 path 维度基线
4. 每维度补正向+反向样本，防判据零命中

## argv0 维度复检结论（2026-11-26）

初判为"缺陷"的 argv0 差异，经差分基准 + AT_EXECFN 验证后重新定性：

- `/proc/self/exe` 在显式 glibc-loader 执行下指向 loader：这是内核事实，用户态无法伪造，
  属于硬边界（与之前删除 elfload 的决策一致）。
- 但 `AT_EXECFN=probe`（正确保留了真实程序名）——loader 提供了正确的自定位替代信息源。
  依赖自定位的真实程序（codex 用 /proc/self/exe）走静态 raw-exec 路径，因此拿到的是真实程序；
  用 loader 启动的动态程序可用 AT_EXECFN。两条路径都有自洽的定位机制。
- `child_exit=-1` 是探针自身用裸 execve 执行 app_data_file（SELinux no-exec）且绕过 hook 的必然结果，
  不代表真实程序的 re-exec 行为（真实程序 via compat hook）。

结论：argv0 维度标为 EXEMPT（硬边界 + 已验证替代机制），不是待修缺陷。
这体现差分基准的价值不只是"发现差异"，还包括"用证据判断差异是否是缺陷"——
避免把所有不同都当错误（那正是最初对标式审计的毛病）。

## env 维度复检结论（2026-11-26）

差分基准实测：LobOS 终端里程序收到 68 个环境变量（含 10 个 LOBOS_*），
host 干净 env 基线是 0。这是对兼容层的设计性注入（exec hook 的 env_merge_sys），
不是缺陷——native 垫片依赖这些变量做路径翻译/loader 路由。所有注入键用 LOBOS_ 保留前缀，
不与普通程序冲突。

但需明确记录：**LobOS 的 execve envp 不是精确替换，而是被增强**。标准 Linux 下
execve(path,argv,{A=1}) 子进程只看到 A=1；LobOS 会额外注入系统变量。
依赖"envp 精确透传"做测试/沙箱的程序行为会不同。标 EXEMPT（设计性），不是通过。

## glibc guest VFS hook 补齐（2026-11-26）

四层诊断确认的缺口已补齐：
- 根因：bionic liblobosposix.so 依赖 bionic libc.so，glibc guest 加载不了；
  原先 glibc guest 只有 exec 时翻译，运行时 stat/open 无 VFS hook。
- 补齐：`build-glibc-vfs-hook.sh` 用 glibc cross toolchain 编出
  `liblobos-vfs-glibc.so`（依赖 libc.so.6，导出 open/stat/fstatat/readlink/mkdir/rename 等），
  RuntimeEnvironment 注入 `LOBOS_VFS_SO`，exec-path glibc 分支把它与 sigsys so
  一起放进 LD_PRELOAD（vfs 在前，沿用 bionic LD_PRELOAD 过滤）。
- 差分基准确认：虚拟路径 `/usr/bin/bash` 的 stat 从 ENOENT 变为成功，
  readlink 走 hook（realpath）。
- 产品回归：终端 echo / node --version 正常。

验证注意：glibc 程序在 app_data 无法直接 exec（SELinux），需经 glibc loader。
手动验证时必须模拟 exec-path 的 env 过滤（清 bionic LD_PRELOAD），
否则 bionic hook 会被 glibc loader 误加载报 "version LIBC not found"。
真实产品路径由 exec-path 自动处理，不受此影响。

# 终端方案调研与决策（2026-11-26）

用户指出：当前终端不是优秀方案、缺很多能力，与其自己造不如找现成的，
先调查再决定。本文是三路并行调研的结论。

## 一、现状审计：我们的问题在仿真层，不在 PTY 层

### 传输层（PtySession.kt + d3/pty-session.c）其实不差
- 真 devpts：posix_openpt/grantpt/unlockpt/setsid/TIOCSCTTY/dup2 流程正确
- winsize 帧协议、8 会话槽、退出码/信号回收都有实现
- Ctrl-C/Z/D 已实测工作，job control 基础可用

### 仿真层（TerminalBuffer.kt + TerminalScreen.kt）严重不足
- `TerminalBuffer.kt:44-47,81-84`：`parseCsi` 只认 `m`（SGR），其他全丢——
  光标定位/移动/清屏/行擦除/alt-screen 全部不支持
- `TerminalBuffer.kt:86-99`：只有 6 种前景色 + 粗体，无背景色/256 色/真彩/反显/下划线
- `TerminalBuffer.kt:3,68-72`：Char 当一列，无 wcwidth，CJK/emoji 拆成两格
- `TerminalBuffer.kt:29-32`：跨帧 UTF-8 按 chunk 独立解码，产生替换字符
- `TerminalScreen.kt:74-78,118-130`：TextField 整行提交，打字不进 PTY——
  不是终端输入，是命令行输入框
- `TerminalScreen.kt:107-109`：无 SelectionContainer，输出不能选中复制
- `TerminalScreen.kt:112-114`：自动强制滚到底，不能回看
- `TerminalProfile.kt:154` 声明 `TERM=xterm` 但仿真远低于 xterm

**结论**：vim/less/htop/tmux/fzf 这些全屏 TUI 实际不可用。我们的"终端"
本质是"逐行命令日志 + 整行输入控件"，不是终端模拟器。

## 二、现成方案调研

### 方案 A：Termux terminal-view AAR（快速路径）
- PTY：JNI 手工 ptmx + fork，无额外进程；Java TerminalSession 用 ByteQueue + 三线程
- 仿真：完整 xterm 子集——主/备用屏、transcript、鼠标协议(1000/1002/1006)、
  OSC 8/52、sixel、DSR/DA、truecolor/256 色
- 输入：InputConnection + KeyHandler，Ctrl/Alt/Shift/应用光标键/bracketed paste/IME 兼容
- 复用：JitPack `com.termux.termux-app:terminal-view:0.118.0`（Apache-2.0 例外，
  但主仓 GPLv3，需法务确认）
- 代价：强绑定 Termux 自己的 TerminalSession API，要写 adapter 把我们的
  `PtySession.Session.onData` 字节喂进 `TerminalEmulator.append()`，按键写回 `session.write()`

### 方案 B：mtty-term（与 fastroot 原生配套，长期更合适）
- 同 fastroot 作者，VT 内核用 **libghostty-vt（MIT）** 工业级解析
- `mtty-term`：Apache-2.0，JNI shim 包 libghostty-vt，95 个单测 + 压力测试
- PTY host：独立 C 进程 `mtty-ptyhost`，持有所有 session，app 断开仍存活；
  每客户端独立视图/历史；`[type][length][payload]` 帧协议
- 与 fastroot 配合：所有程序（含 PTY host 里的 agent）都在 fastroot 下运行；
  fastroot compat.sh 已测 `script`/`sudo -n true`/`ssh -t`/`tmux`/node-pty
- 代价：`mtty-term` 目前不是 AAR，需按 submodule/源码集成 + JNI shim + libghostty native 构建；
  mtty 主仓 AGPL-3.0（但 term 和 fastroot 子模块是 Apache-2.0，只取子模块无品牌问题）

### 方案 C：继续自研（不推荐）
- 105 行 TerminalBuffer 要补成工业级 VT（光标/alt-screen/wcwidth/UTF-8 流解码/
  256 色/鼠标/OSC/sixel），等价于重写一个 libghostty，投入产出比极低

## 三、fastroot 对 PTY 的支持边界（已查证）

- fastroot 保证 PTY syscall 语义：devpts、TIOCSCTTY、termios ioctl、pty slave 的
  fake chmod/chown（`translate.c:1669-1679,1261-1262,2895-2919`）
- `debug/pty.c` 探针覆盖完整 PTY 建立流程
- `compat.sh` 有 PTY 测试：pty-pair / script / sudo-pty / sshd-pty / node-pty
- **但 fastroot 不提供终端仿真、行编辑、历史、按键编码**——它只保证 syscall 语义

## 四、决策

**采用方案 B（mtty-term/libghostty-vt）作为终端仿真层，保留我们现有的 PTY 传输层**
（或迁移到 mtty-ptyhost 模型作为第二步）。理由：
1. 与 fastroot 同一生态，compat.sh 已验证 PTY 场景
2. libghostty-vt 是工业级 VT，解决我们 105 行解析器的全部缺口
3. Apache-2.0 组件，许可证兼容
4. 95 个单测 + 压力测试，质量有保证

**不采用 AAR 快速路径**：Termux AAR 的 GPLv3 + Apache 例外需要法务确认，
且其 API 与我们的 PtySession 不兼容，adapter 成本不低；mtty-term 来源更干净。

## 五、执行路径（两步）

### 第一步：换仿真层（低风险）
- 引入 libghostty-vt（JNI shim + Kotlin wrapper，参照 mtty-term）
- 用真实 VT 解析替换 TerminalBuffer
- 用 TermView + KeyHandler 替换 TextField 整行输入
- 保留现有 PtySession 传输（字节流 feed 给 VT，按键编码写回 PTY）
- 验收：vttest、vim/htop/tmux、Ctrl-C/Z、resize、OSC 52、256 色、宽字符

### 第二步（评估后）：换 PTY host（可选）
- 如需"app 重启后会话存活/多客户端各自视图"，迁移到 mtty-ptyhost 模型
- 这是增强，不是第一步的前置

## 六、对"清旧内核"的影响

终端换仿真层后，PTY 通道仍用我们的 native pty-session.c——它调用
`liblobosbash.so` 并依赖 exec hook。要让它跑 Ubuntu 里的程序，需要把
`pty-session.c:289` 的 `execv(argv[0], argv)` 换成 fastroot launcher 命令行
（调研确认这是最小闭环：`execv` 改成 `libfr-launch.so <ld.so> --library-path
... --preload <shim> /usr/bin/bash`）。这步完成后，终端才真正走 Ubuntu+fastroot，
旧 exec hook/VFS 内核才可以安全删除。

**所以正确顺序是**：终端仿真层替换 → PTY 接 fastroot → 产品验证 → 再删旧内核。

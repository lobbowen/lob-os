# 终端 shebang/npm CLI 执行问题的根因调查（2026-11-27）

用户要求：claude/codex/dsh 等 npm 全局 CLI 必须像标准 Linux 一样直接敲名字启动，
不能用原生 binary 路径绕过。本文件记录只读调查确认的事实与根因。

## 已确认正常（排除的假设）
在终端 UI 实测：
- `#!/bin/sh` 脚本 → 正常执行（SH_SHEBANG_OK）
- `#!/usr/bin/env sh` 脚本 → 正常执行（ENV_SHEBANG_OK）
- symlink → shebang 脚本 → 正常执行
- `chmod +x` 让 644→755 立即生效，脚本马上能跑
- `ls -l` 显示 mode 正确（fake root:root，如 -rwxr-xr-x）
- `env` 命令能执行（显示 FR_* 环境）
结论：fastroot 的 shebang 解析、chmod、stat、execve 机制本身都正常。

## 根因（已确认）
`Permission denied` 不是 shebang 问题，是 npm install 在 fastroot 下**安装中断**：
1. npm install claude 的 postinstall（`node install.cjs`）失败，报错：
   `Cannot find module '/data/data/lobos.os/files/ubuntu/usr/local/lib/.../install.cjs'`
   注意路径是 `/data/data/`（真实路径），不是虚拟 `/usr/local/...`。
2. node 通过 `/proc/self/cwd` 或 `import.meta.url` 拿到真实 app_data 路径，
   fastroot 只 hook 了 getcwd syscall（translate.c:2977 strip_root），
   `/proc/self/cwd` 的 readlink 在部分场景未翻译。
3. postinstall 失败 → npm 以 rc=1 中断 → 未完成 bin 的 symlink/chmod 收尾
   → bin 残留为占位脚本（"Error: claude native binary not installed"）且无执行位
   → 终端敲 `claude` 报 Permission denied。

## 需要系统层修复的点（非单产品补丁）
1. **cwd 语义**：让 fastroot 下子进程（node/python）的 cwd 与标准 Linux 一致——
   即 node 读 `/proc/self/cwd` 或 `process.cwd()` 得到虚拟路径（/root），
   而不是真实 app_data 路径（/data/data/lobos.os/...）。
   这是 postinstall 正确执行的前提。
2. **安装完整性**：postinstall 不应因路径翻译失败而中断 npm 安装；
   或让 npm 的 bin chmod/symlink 收尾不受 postinstall 失败影响。
3. 修好后验证：终端直接 `claude --version` / `codex --version` / `dsh --version`
   都能显示版本，与标准 Linux 一致。

## 排除的错误方向
- 不要单独给 claude/codex/dsh 打补丁或用原生 binary 路径绕过（用户明确反对）
- 不要改 fastroot 上游 C 代码作为常规手段（vendored，风险高）；优先在
  env/cwd/启动参数层解决

## 最终修复（2026-11-27，已解决）

根因锁定为 **cwd 语义不一致**，两处系统层修复：

1. `PtySession.host()`：PTY host 启动时 `pb1.directory(FR_ROOT/root)`，
   让 bash 及子进程的 cwd 从 app filesDir 改到 rootfs 内。
2. `UbuntuSystem.environment()`：FR_ROOT 用 `/data/data/<pkg>/...` 规范形式
   （内核 `/proc/self/cwd` 返回的形式），使 fastroot 的 strip_root 前缀匹配。

修复效果（终端 UI 实测）：
- node `process.cwd()`：修复前 `/data/user/0/lobos.os`，修复后 `/root`
- 提示符：`root@localhost:~#`（与 Linux 一致）
- npm install 完整成功（postinstall 通过，不再需要 --ignore-scripts）
- 终端直接敲名字启动（系统层生效，非产品补丁）：
  - `claude --version` → `2.1.332 (Claude Code)`
  - `codex --version` → `codex-cli 0.172.0`
  - `dsh --version` → `0.2.2-rc.5` (RC=0)

这说明问题从来不是 shebang 本身，而是 cwd 未虚拟导致 node 模块解析拿到
真实路径。修复后所有 npm 全局 shebang CLI 与标准 Linux 一致，可直接执行。

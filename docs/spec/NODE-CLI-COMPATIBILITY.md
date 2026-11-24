# Node CLI 通用兼容规范（LobOS 标准系统能力）

## 1. 这个规范解决什么

让**上游按标准 Linux 分发的 Node CLI**（dsh 及所有同类）在 LobOS 终端里
无需重编、无需人工补丁、重装环境后一次性可用。

dsh web 是这条规范的第一个端到端验证实例：
```
$ dsh web
dsh web: http://127.0.0.1:3080/?token=...
curl http://127.0.0.1:3080/ -> 401（服务活着、鉴权正常）
```

## 2. 成功的原因（技术原理，讲清楚为什么）

一个 Linux Node CLI 在 Android/LobOS 上跑不起来，根因不是"软件要重编"，
而是两个**平台环境差异**，都是一次性的系统层适配：

### 差异一：私有 native addon 没有 android 平台件

以 `node-addon-require-builtin` 为例（dsh 用它 require Node 内部模块）：
- 上游只发 darwin/linux/win32 预编 `.node`，**没有 android-arm64**
- 它依赖 glibc（NEEDED libc.so.6/libstdc++.so.6），而 LobOS 的 node 是
  bionic 自编译——两套 libc 不能在同一进程共存（实测强塞会 segfault）

**关键洞察**：这个 addon 的功能本质是 `requireBuiltin(id) = require(id)`
（取 Node 内部模块）。在 `--expose-internals` 下，这就是几行 JS。
所以**不需要 native 件，用纯 JS 替身**（华为云对这类 addon 的官方推荐做法）。

### 差异二：Node 默认不暴露内部模块

`require("internal/modules/esm/loader")` 这类内部模块，默认 `require` 不到，
必须给 node 传 `--expose-internals` **命令行参数**。
- 放在 `NODE_OPTIONS` 里会被 node 拒绝（实测 RC=9）
- 必须在命令行 argv 层注入

## 3. 固化的机制（三处，全部在 APK 代码里，不靠手工）

### 机制 A：JS 替身自动应用（TerminalProfile.ensureNodeAddonShims）

`container/app/src/main/java/lobos/services/app/TerminalProfile.kt`
的 `ensureBashrc()` 每次终端启动时调用 `ensureNodeAddonShims(ctx)`：
- 遍历已安装 node_modules（有限深度，覆盖嵌套），找到所有
  `node-addon-require-builtin/lib/index.js`
- 若没有 `// lobos-js-shim-v1` 标记，备份原件为 `index.js.lobos-orig`，
  写入纯 JS 替身
- 幂等：已替身则跳过

这保证：**重装 dsh 后（npm 还原原件），终端一启动就自动重新替身**，无需手工。

### 机制 B：--expose-internals 命令行注入（exec-path.c）

`container/native/d1/exec-path.c`：
- `exec_with()`（shebang `#!/usr/bin/env node` 路径）：disp0=="node" 时在
  argv 中插入 `--expose-internals`（幂等）
- `execve()` 入口：path basename=="node" 时同样插入

这保证：无论 CLI 用 shebang 启动、直接调用、还是 npm 内部调用 node，
都自动带 expose，覆盖所有启动路径。

### 机制 C：node shell wrapper（TerminalProfile）

`TerminalProfile` 生成的 `.bashrc` 里，node 函数也带 `--expose-internals`
（与机制 B 互补，双重保障，幂等无害）。

## 4. 哪些东西不是必需的（走偏的记录，避免误解）

- **不需要重新编译 node**：bionic node 本来就能跑脚本；glibc node 在 app 域
  会因 clone3 被 seccomp TRAP 而 signal 31（已实测排除）。
- **不需要改 dsh 的源码**：JS 替身在系统层做，dsh 装什么版都通用。
- **不需要手工脚本**：早期 `scripts/compat/apply-node-addon-js-shim.sh` 是
  实验用的手工工具；标准化后由机制 A 自动完成，该脚本仅作参考/应急。

## 5. 全新安装流程（用户操作，一次性成功）

1. 安装 LobOS APK（内含机制 A/B/C）
2. 在 LobOS 终端里装 Node CLI，例如：
   ```
   npm install -g @deepseek-ai/dsh
   ```
   （`npm_config_prefix` 已由 .bashrc 指向 `$HOME/usr/local`）
3. 打开/重启终端（触发 `ensureBashrc` → 机制 A 自动替身）
4. 直接运行 CLI：`dsh web`

预期：dsh web 打印 URL、监听 3080、curl 返回 401。全程无手工补丁。

## 6. 扩展到其他 Node CLI

任何依赖"无 android 平台件的 native addon + Node 内部模块"的 CLI，
按同一模式扩展：
- 该类 addon 若功能等价于几行 JS（如 requireBuiltin），在机制 A 里加替身
- 需要内部模块的，机制 B/C 的 expose 已覆盖
- 不需要为每个软件单独适配，按 addon 类型统一处理

## 7. 验证清单（标准化是否生效）

- [ ] 终端启动后 NARB `index.js` 首行是 `// lobos-js-shim-v1`
- [ ] `node -e "require('internal/modules/esm/loader')"` 可用（expose 生效）
- [ ] `dsh web` 打印 URL，`curl localhost:3080` 返回 401
- [ ] 重装 dsh 后重启终端，上述仍成立（幂等自动应用）

## 8. 完整卸载重装验证记录（2026-11-25）

按"卸载→重装→一次性成功"做了端到端验证：
1. 把 dsh 的 NARB index.js 恢复为 npm 原件（872 字节，`"use strict"` 开头），
   删除所有 lobos 手工痕迹（备份/临时脚本），模拟全新 dsh 安装。
2. `pm uninstall -k lobos.os`（保留 data 里的 dsh/npm 环境——那是用户应用数据，
   不是 APK 的一部分）。
3. 重装 APK（含机制 A/B/C），启动 app、进终端。
4. 验证 NARB index.js 首行自动变成 `// lobos-js-shim-v1`（ensure 自动替身生效）。
5. 终端敲 `dsh web` → 打印 URL、监听 3080、curl 返回 401。

结论：标准化成立。重装后无需任何手工操作，一次成功。

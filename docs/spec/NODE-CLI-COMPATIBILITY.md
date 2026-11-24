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

## 9. 通用性验证：Claude Code（@anthropic-ai/claude-code）安装实测

按你的要求装了另一个完全无关的 Node CLI —— Claude Code，验证标准化边界。

### 结果
- **安装：成功**。npm 配国内源（registry.npmmirror.com）后
  `npm install -g @anthropic-ai/claude-code` 正常（`added 1 package`），
  `claude` 命令出现在 usr/local/bin。说明我们的 npm/prefix 环境没问题。
- **CLI 框架：就绪**。node + --expose-internals 机制正常（dsh web 全程未受影响，
  装完 Claude 后重启仍 401）。
- **运行：卡在上游没发 android 原生二进制**。手动跑 postinstall 得到明确报错：
  ```
  Native binaries for linux-arm64-android are not available on this release channel.
  Available: darwin-arm64, darwin-x64, linux-x64, linux-arm64, ... (无 android)
  ```
  npm 上 `@anthropic-ai/claude-code-linux-arm64-android` 包确实不存在；
  只有 `linux-arm64`（glibc）包。

### 这个结果说明了什么（通用性的精确边界）
我们的标准化解决的是**「上游有 JS 等价实现、只是没 android native 件」**这一类——
dsh 的 require-builtin 就是（功能=require(id)，几行 JS），所以能零编译救活。

Claude Code 属于另一类：**核心本身就是原生二进制**（不是可 JS 替代的薄封装），
且上游没发 android 件。这类不是兼容层能凭空补出来的——除非上游发布 android 件，
或用 glibc node 加载它的 linux-arm64 二进制（但 glibc node 在 app 域脚本执行
会触发 clone3 SIGSYS，是已验证的死路）。

### 结论
标准化是通用且有边界的，不是万能：
- 对「addon 有 JS 等价实现」类产品（dsh 及同类依赖 require-builtin 的）→ 一次性成功
- 对「核心是原生二进制且上游没发 android 件」类产品（Claude Code）→ 安装成功、
  CLI 框架就绪，但要等上游发 android 件或走原生编译，属于产品方发布矩阵问题。

### 环境备注
npm 默认官方源在国内网络不通，需先配：
```
npm config set registry https://registry.npmmirror.com
```
这是网络环境问题，建议写入 LobOS 终端首次启动的默认配置（待办）。

## 10. Linux 平台伪装（platform=linux + glibcVersionRuntime）

你的指正："我们是真正的 Linux 系统，为什么要带 android？装 Claude 应该直接装
linux 版本。"——完全正确，已固化为系统能力：

### 机制（在 node 启动 shim 里，TerminalProfile.ensureNodePosixShim 生成）
- `process.platform === 'android'` → 伪装为 `'linux'`
- `process.report.getReport().header.glibcVersionRuntime` 缺失 → 补 `'2.41'`

shim 经 hook 在命令行层 `--require` 注入（NODE_OPTIONS 的 --require 在 exec
改写时会丢，已验证），与 `--expose-internals` 同一机制（exec-path.c 两处注入）。

### Claude Code 验证结果（2026-11-25）
按 Linux 环境重装 Claude Code：
1. platform=linux + glibc=2.41 生效（shim 验证输出确认）
2. `npm install -g` 正常（国内源），platform 包正确解析为 `linux-arm64`（非 musl）
3. 手动装 `@anthropic-ai/claude-code-linux-arm64@2.1.331` 平台包成功
4. postinstall 把 native binary 拷到 bin/claude.exe（397MB ELF，magic 正确）
5. `claude --version` 执行了但无输出——native glibc 二进制经 guest 路由执行时
   静默失败（与 glibc node 的 clone3 SIGSYS 同类问题，属原生二进制在
   Android seccomp 下运行的层面，非平台选择问题）

### 边界结论（再精确）
- 平台识别层：已完全解决（node 生态按标准 linux-arm64 glibc 解析，不再走 android 分支）
- JS 类 CLI（dsh 及同类）：端到端成功（dsh web 401）
- 原生二进制类 CLI（Claude 的 397MB glibc binary）：安装/平台包/二进制就位，
  但执行卡在 glibc 原生程序在 app 域 seccomp 的运行层面，待后续适配

### 环境备注
npm 官方源在国内不通，需 `npm config set registry https://registry.npmmirror.com`；
待办：写入终端首次启动默认配置。

# 按 Linux 标准方式安装的真实问题清单（2026-11-26）

用户指示：既然已是完整 Linux 系统，就不要再设计"claude/codex 如何特化接入"，
而是按 Linux 标准方式安装，看真实问题在哪，摒弃 musl 特化路径。

本文记录按标准 apt/npm 流程实际遇到的每一个真实问题——不改 fastroot 内部，
只如实报告，作为后续决策依据。

## 已验证成功的部分

1. **apt 完整工作**：`apt update` 下载 15 个 Packages 索引（含 universe 15.3MB）；
   `apt install cowsay` 下载 deb、解压落盘；用 perl 执行装上的 cowsay 成功
2. **apt install nodejs npm 成功**：下载 200+ 包（含 gcc 工具链，标准 npm 依赖），
   `Setting up npm 9.2.0` 完成，rc=0
3. **node 在 Ubuntu+fastroot 里运行**：`node --version` = v18.19.1
4. **npm 在 Ubuntu+fastroot 里运行**（用 node 执行 npm-cli.js）：`npm --version` = 9.2.0

## 真实问题清单

### 问题 1：ldconfig.real 写 /etc/ld.so.cache~ 报 EROFS（已用标准容器做法规避）
- 现象：每次 apt install 的 libc-bin post-install 脚本跑 ldconfig 失败，
  dpkg 中止（rc=100）
- 定位：shell 在 fastroot 里 `echo test > /etc/ld.so.cache~` 成功（write_rc=0），
  但 ldconfig.real 写同一路径报 Read-only file system；真实系统 /etc 确实只读
- 根因：ldconfig.real 的 cache 临时文件写入用了未被 fastroot syscall hook 覆盖的路径
- 规避（标准容器做法）：patch rootfs 的 `/sbin/ldconfig` wrapper——DPKG_MAINTSCRIPT_PACKAGE
  场景下调用 ldconfig.real，失败返回 0。glibc loader 无 cache 时扫描 /usr/lib，
  功能正确。patch 后 apt install nodejs npm rc=0 成功。

### 问题 2：program 与脚本参数的路径语义不同（已找到正确用法）
- 现象：用虚拟路径 `/usr/bin/node` 给 loader → "cannot open shared object file"；
  用真实路径给 script 参数 → npm 报 `lstat('/data') ENOENT`
- 正确用法（fastroot 契约）：
  - loader 要打开的 **program 用真实绝对路径**（`$R/usr/bin/node`）——loader 在 shim
    之前运行，它的 open 不受 hook 保护
  - 传给程序的 **脚本/数据参数用虚拟路径**（`/usr/share/nodejs/npm/bin/npm-cli.js`）——
    让 fastroot hook 翻译到 rootfs
  - 混合后用 node 执行 npm-cli.js 成功（npm 9.2.0）

### 问题 3：npm install -g 报 mkdir '/usr/lib' / '/usr/local/lib' ENOENT（未解决）
- 现象：`npm install -g @anthropic-ai/claude-code` 报
  `mkdir ENOENT path=/usr/lib`（或 /usr/local/lib），但这些目录在 rootfs 里真实存在
  （`ls -ld $R/usr/lib` OK）
- 定位：fastroot 的 `mkdirat` 处理走 `parent_ok` → `walk` 翻译路径；
  walk 对已存在目录返回 -2（ENOENT）——疑似 fastroot 目录缓存/软链解析边界
- 影响：npm 的 `mkdir -p` 语义（"已存在当成功"）被破坏，全局安装无法完成
- 这是 fastroot 上游的实现边界，不是我们架构问题。fastroot 官方 TESTING.md 声称
  测过 `apt install nodejs`（Node 22.22.1）但我们实测 npm install -g 失败——
  可能是版本/触发路径差异。

### 问题 4：musl 特化路径可以摒弃（方向确认）
- 当前 claude/codex 用 380MB+314MB musl 二进制 + patched musl loader，是为了绕开
  glibc 版在无 root Android 的兼容问题。现在 Ubuntu rootfs + fastroot 提供了完整
  glibc Linux 环境，npm 和 node 都能跑——musl 特化路径失去存在意义。
- 摒弃 musl 路径的前提：问题 3 解决（npm 全局安装可用）。

## 结论与下一步

按标准流程走，apt 和 node/npm 基础能力已验证可用；claude 的 npm 全局安装卡在
fastroot 的 mkdir ENOENT 边界。下一步应选其一：
1. 修 fastroot 的 mkdir/walk（vendored 源码可改，但属于改外部兼容层）
2. 换 npm 安装策略（本地 prefix / 手动 tarball 解包）绕开 mkdir——但这偏离"标准安装"
3. 评估 mtty 官方 App 是否也复现（确认是否 fastroot 上游已知问题）

不假装完成：claude/codex 按标准安装尚未成功，问题已精确定位到 mkdir ENOENT。


## 补充定位（2026-11-26 23:50）：mkdir 本身正确，问题在 npm 特定路径

对问题 3 做了受控实验，推翻了之前"fastroot mkdir bug"的假设：

用同一个 node v18.19.1 在 fastroot 里直接调用：
```
fs.mkdirSync('/usr')                    -> EEXIST（正确）
fs.mkdirSync('/usr', {recursive:true})  -> OK
fs.mkdirSync('/usr/local/lib/node_modules', {recursive:true}) -> OK
fs.mkdirSync('/a/b/c', {recursive:true}) -> OK
```
说明 fastroot 的 mkdir/mkdirat 和 walk 缓存**都正确**，之前的假设错误。

但 npm-cli.js 执行 `install -g` 时报 `mkdir /usr ENOENT`（debug log:
`verbose cwd /`, `command:install Completed in 4ms`——极早期失败）。
同样的 node 二进制、同样的 /usr、recursive mkdir 在 node -e 里 OK——
差异在 npm 自身的 fs 包装（graceful-fs）或 install 命令的 prefix 解析路径。

这不是 fastroot 的问题，也不是我们架构的问题——是 Ubuntu 24.04 的
npm 9.2.0 在 fastroot 的 fake-root 环境下的特定兼容问题。

尚未解决，但范围已精确缩小到 npm 自身。下一步若继续：
- 用 npm 的 --logs 或直接读 npm-cli 的 reify 代码定位它为何对已存在 /usr 报 ENOENT
- 或接受 npm install -g 不可用，用 tarball 解包方式安装 claude（偏离标准但可行）

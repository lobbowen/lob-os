# LobOS 统一路径名字空间规范（用户态虚拟根）

## 1. 问题：现在不是"加载了硬盘"，是"逐函数补路径"

当前状态：沙箱目录已经是标准 Linux 布局（`files/usr/bin`、`files/usr/lib`、
`files/etc`、`files/.tmp`），但程序看到的不是标准路径——它们拿到的是
`/data/user/0/lobos.os/files/usr/...` 这种真实沙箱绝对路径，靠 `PATH` /
`LD_LIBRARY_PATH` / `PREFIX` 环境变量指路。

缺的是一步"把这块沙箱当硬盘加载进来"：程序看到统一的标准 Linux 路径树
（`/usr`、`/lib`、`/etc`、`/tmp`），系统一次性映射到沙箱真实位置。没有这一步，
就只能给 open/exec/shebang/loader 每个函数单独打补丁——这正是现状
（`open-fallback.c`、`tmp-paths.c`、`link-interpose.c`、`exec-path.c` 各补各的）。

## 2. 边界：内核 mount 不可用，用户态虚拟根是唯一路

实测：app 域 `unshare -m` = Operation not permitted（untrusted_app 无
CAP_SYS_ADMIN），mount/chroot/pivot_root 全部不可用。memfd+execveat 被 seccomp
拒(ENOSYS)，execve(/proc/self/fd) 被 SELinux 拒(EACCES)。

所以虚拟根只能用 LD_PRELOAD 的 libc 路径 hook 实现（proot 语义，不用 ptrace），
这是 non-root 下唯一能一次性内化路径树的方式。

## 3. 虚拟根映射规则（单一真值）

虚拟根 = `ctx.filesDir`（`/data/user/0/lobos.os/files`），记作 `$VROOT`。
由系统级环境变量 `LOBOS_VROOT` 注入（非产品清单）。翻译规则：

| 程序看到的路径 | 映射到 |
|---|---|
| `/usr/...`   | `$VROOT/usr/...` |
| `/etc/...`   | 不映射，直通真实 Android /etc（见下） |
| `/lib/...`   | `$VROOT/usr/lib/...`（usr-merge） |
| `/bin/...`   | `$VROOT/usr/bin/...`（usr-merge） |
| `/sbin/...`  | `$VROOT/usr/bin/...` |
| `/tmp/...`   | `$VROOT/.tmp/...` |
| `/var/...`   | `$VROOT/var/...` |
| `/opt/...`   | `$VROOT/opt/...` |
| 其他 `/` 开头 | 保持原样（Android 系统路径 /system /apex /data 等不动） |
| 相对路径     | 保持原样 |

`/etc` 不映射：`/etc/passwd`/`resolv.conf`/`zoneinfo` 等必须可见于真实系统；
把 `/etc` 翻进沙箱会导致 `cat /etc/passwd` 失败（实测回归）。LobOS 自己的
`/etc` 文件（lobos-sysenv 等）由程序用真实绝对路径显式读取，无需虚拟映射。

白名单（不翻译，直通真实系统）：`/system`、`/apex`、`/dev`、`/proc`、`/sys`、
`/data`、`/storage`、`/vendor`、`/odm`、`/product`、`/$VROOT` 自身。
避免递归和把 Android 自身路径翻进沙箱。

## 4. 单一翻译入口

`container/native/d1/vfs-root.h` 提供 `lobos_vfs_translate(path, buf, cap)`：
所有路径类 hook 必须调用它，不再各自硬编码 `/tmp`、`$HOME`、shebang path 等。
翻译是纯字符串前缀替换 + 白名单短路，无状态，可重入。

## 5. 收敛现有 hook

- `tmp-redirect.h` 的 `/tmp` 特例 → 并入 `lobos_vfs_translate`（`/tmp` 规则）
- `open-fallback.c` 的 open/openat → 用统一翻译
- `tmp-paths.c` 的 mkdir/rmdir/unlink/rename/... → 用统一翻译
- `link-interpose.c` 的 link/linkat → 用统一翻译
- `exec-path.c` 的 execve 目标路径 → 用统一翻译；PT_INTERP/loader 路由保留
  （loader 是 ELF 加载语义，不属于路径名字空间，但要认翻译后的路径）

shebang 解析：`#!/usr/bin/env node` 里的 `/usr/bin/env` 经翻译命中沙箱 env。

## 6. 环境注入

`RuntimeEnvironment.treeRootEnv` 注入 `LOBOS_VROOT = ctx.filesDir.absolutePath`。
`PATH`/`LD_LIBRARY_PATH` 逐步可切到标准视图（`/usr/bin`、`/usr/lib`），
由翻译层落地到真实位置。为兼容期，先保留真实路径，翻译层双向可用。

## 7. 与 ELF loader 的分工

路径名字空间负责"文件在哪"；PT_INTERP 自动路由负责"用哪个 loader 加载"。
两者正交：
- 程序 exec `/usr/bin/claude` → 翻译为 `$VROOT/usr/bin/claude`
  → exec hook 读其 PT_INTERP=ld-musl → 用系统 musl loader 加载（目标只需可读）
- 动态库 `/usr/lib/libfoo.so` → DT_NEEDED 解析时 loader 用 LD_LIBRARY_PATH，
  翻译层保证标准 `/usr/lib` 路径可命中

## 8. 验收标准
- 一个只认标准路径（硬编码 `/usr/lib`、`/etc`、`/tmp`）的程序，不改一行、
  不加环境变量，在 LobOS 里能正确找到资源
- 现有 dsh / claude / codex 不回归
- 零产品登记表：新增程序装进标准路径即可

## 9. 明确不做
- 不做内核 mount / chroot / pivot_root（能力不具备）
- 不做 ptrace 式 proot（性能与复杂度；LD_PRELOAD 足够覆盖 libc 路径调用）
- 不翻译 Android 系统路径（白名单直通）

## 10. 配套：npm 平台的 musl ABI 声明（系统级，通用）

Node 安装器（如 Claude Code 的 install.cjs）用 `detectMusl()` 判断 libc：
`process.report.header.glibcVersionRuntime === undefined` 即为 musl。LobOS
用户态底座是 musl 语义，node shim 统一删除该字段并包装 getReport，使所有
npm CLI 在 install 时选官方 `*-musl` 平台包（glibc 包在 app 域跑不起来）。
这是一次性系统级 ABI 声明，等价于 Alpine 等真 musl 发行版，不含产品名。

## 11. 验收实测
- `/usr/bin/python3 --version` -> Python 3.11.13（真实 /usr/bin 无此文件，虚拟根翻译命中沙箱）
- `cat /etc/passwd` -> 读到真实系统（/etc 不映射，无回归）
- musl 二进制经 exec hook 按 PT_INTERP 自动路由 -> 正常运行
- node 下 `glibcVersionRuntime=undefined`（detectMusl 判定 musl）

## 12. 决定性边界：虚拟磁盘的加载语义（2026-11-26 实测）

用户指出"静态 ELF 的 noexec 是最大问题，根因是没有完整磁盘空间概念"。
系统排查确认这是对的，并定位到精确的加载语义边界：

### 事实链
1. app 进程 context=untrusted_app，files/ 标签=app_data_file，被 SELinux noexec；
   nativeLibraryDir 标签=apk_data_file，可 exec。app 无 CAP_MAC_ADMIN，无法
   改自己文件的标签。所有可写位置（files/、/data/local/tmp、/sdcard）在 app 域
   exec 静态 ELF 全部 Permission denied（实测）。
2. 唯一出路 = 不靠内核 exec，由 loader 加载 ELF（目标只需可读）。Termux
   termux-exec 就是这么做的：LD_PRELOAD 把 execve 改写成
   `/system/bin/linker64 <program>`（见 GoT linkerexec 文档与 termux-exec 源码）。
3. **但 linker64 只接受 ET_DYN/PIE**：实测 linker64 加载 ET_EXEC 报
   "unexpected e_type: 2"；把 e_type 强行改成 3 后报 ".dynamic section not found"。
   即：能被 loader 加载的充要条件是 ELF 有 PT_DYNAMIC（动态链接），与 e_type 无关。
4. 真实二进制分两类：
   - Claude musl 版：INTERP=ld-musl + 有 PT_DYNAMIC -> musl loader 加载成功（已验证）
   - Codex：纯静态，只有 LOAD 段，无 INTERP 无 PT_DYNAMIC -> 无法被任何 loader 加载

### 结论
"虚拟磁盘 + 我们的 loader 加载一切 ELF"对**动态链接 ELF**完全成立（这就是
Linux 加载语义，目标只需可读，noexec 无关）；对**纯静态 ELF**在 non-root
Android 上不成立——它没有动态段，必须由内核 exec，而内核拒绝 app 磁盘上的
文件。这是 Android W^X/SELinux 的硬约束（Termux 在 API 29+ 同样受限，见
termux-app issue #5185），不是虚拟根或任何用户态 hook 能消除的。

### 自研 ELF 加载器实测（下一步方向，未闭环）
写了一个最小 ELF64 加载器（读程序头 -> mmap PT_LOAD -> 跳入口），验证
"自己的加载器加载纯静态 ELF、目标只需可读"这条路的机制：
- 能正确读出 entry/phnum、mmap LOAD 段（机制本身成立）
- 但直接跳入口时，glibc 静态启动代码需要完整 auxv/栈布局，裸入口 _start
  也 segfault（mmap 地址/权限或入口约定未对齐）——加载器实现未闭环。
这是"虚拟磁盘"最能走向通用的方向：一个我们自己的加载器，按 ELF 程序头
加载一切二进制（动态靠 PT_DYNAMIC、静态靠 mmap LOAD + 正确入口约定），
目标文件只需可读，彻底绕开 noexec。需要继续攻的是静态 ELF 的入口/auxv
约定（等价于实现一个小型 userland exec）。

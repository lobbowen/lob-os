# runtime —— L1 运行时件

被别的程序**依赖才能运行**的构建物。当前只有 node。

## node

| 项 | 值 |
|---|---|
| 版本 | `24.21.0` |
| ABI | `arm64-v8a` |
| 商店包名 | `userland-node-<version>-<sha12>-android-arm64.zip` |
| 落盘 | `files/usr/lib/toolchain/node/`（与 6 件工具链同构；一次只有一个版本生效） |
| 依赖 | `libc++_shared.so`（由 `LD_LIBRARY_PATH` 指向 APK 的 `nativeLibraryDir` 供给） |
| 验收 | `node -v` 打印三段版本，且真跑一段 JS + 起一个 http 服务 |
| 判据入口 | `NodeRuntime.path(ctx)` —— **唯一**解析口，三通道依次试：程序 → 商店件 → APK 兜底 |

## 落盘位置的实测依据（2026-10-03，Android 17 / API 37 真机）

三条实测决定落盘位置，都不是推断：

1. **`files/` 下可以 exec。** `files/` 根下与 `files/work/...` 下的
   `liblobosptyprobe.so`（arm64 静态）都能正常跑通。去掉执行位则报
   `Permission denied` —— 那是**普通权限位**问题。

2. **外部存储不能 exec。** `/mnt/installer/0/emulated` 是
   `fuse ... noexec,nosuid,nodev` 挂载。所以运行时不能放 sdcard。

3. **真机上 node 24.21.0 正在工作。** `files/usr/bin/node` 是指向
   `/data/app/.../lib/arm64/libnode.so` 的符号链接，`node -v` 打出 `v24.21.0`。

结论：可执行运行时落在应用私有目录可行 —— 与 6 件工具链
（`files/usr/lib/toolchain/<name>/bin/`）同构，那 6 件已实测在真机上跑通。

## 曾经判为「不可行」的结论已作废

`dsh-mobile` 的 `build-node-android.sh` 注释里写过「Android 10+ SELinux W^X 禁止执行
`files/` 中的文件」，并附真机错误
`Cannot run program ".../files/node/24.21.0/node": error=13`，据此把 node 绑死在
jniLibs —— 于是 116 MB 每次换版本都要重发 APK。

本机 Android 17 实测推翻该结论：同一 label（`app_data_file`）下能 exec；
而**无执行位时**报的正是 `Permission denied`（errno 13），两种现象相同，
当时很可能归因错了。判据改为：**以本机实测为准，不以注释为准**。

## 编译

- 脚本：`scripts/build-node-android.sh`
- 产物落 `dist/libnode.so`（**编译工作区**，不进 jniLibs）；`libc++_shared.so` 落 jniLibs
  （它是 APK 原生件，在 `native-assets.txt` 里）
- 编译一次，制品发到 Release `node-runtime-<version>-<abi>`，`build-userland.yml` 取它落件

### 为什么脚本里有这些硬失败判据

脚本本身按仓内约定不写注释（注释门禁零容忍），判据的由来记在这里。

**并行度下限锁 2。** CI runner 是 4 vCPU / 16 GB。按 3.5GB/编译进程 + 2GB 余量算，
14.7GB 只够 `-j3`。但实测矩阵更严：`make -j4` 在 **144 分钟被硬终止** ——
配置的 `timeout-minutes` 是 330，所以**不是超时**，是 runner 被抢占或内存击穿
（表现：日志被硬截断，连收尾步骤都没记录）。`-j2` 在 8GB cgroup 下全程零 OOM。
下限锁 2 而不是退到 `-j1`（那会让本就几小时的构建再拖长很多，得不偿失）。
`NODE_BUILD_JOBS` 可显式覆盖。

**V8 `trap-handler.h` 补丁必须强失败。** 跨编译 arm64 目标时，x64 主机会命中
「arm64 simulator on x64」分支，置 `V8_TRAP_HANDLER_VIA_SIMULATOR`——而 simulator 的
`ProbeMemory` 只存在于 arm64 编译单元，x64 主机的 mksnapshot 会链接失败。修法是给条件
阶梯的每个分支 AND 一个 false 项（**不能**套 `#if 0`，那会打乱 `#if`/`#endif` 配平，
编译器报 `unterminated conditional directive` 并把整个头文件后半段吞掉）。
补丁带三重验证：① 条件指令配平（depth 必须为 0）；② 阶梯结构符合预期（1 个 `#if` +
至少 1 个 `#elif`）；③ 真实预处理求值 `V8_TRAP_HANDLER_SUPPORTED` 必须是 0。
任一不过就 `FATAL` 退出——**因为放行一个没打成功的补丁，比编译失败更难查**。

**`stack_trace_posix.cc` 禁用 execinfo。** bionic 没有 `<execinfo.h>` / `backtrace()`，
而某些 NDK 下 clang 会把 bionic 误判为 glibc 从而 include 它。补丁强制
`HAVE_EXECINFO_H 0`；锚点找不到就用文件头强制定义，并在事后 grep 确认。

**`aligned_alloc` → `memalign`。** `aligned_alloc()` 在 bionic 要 API 28，本构建目标
API 24。`memalign()` 自 API 1 可用，此处等价（对齐是页大小=2 的幂，size 是对齐的整数倍，
结果可 `free()`）。补丁后会 grep 确认调用点归零（排除注释里的说明文字），并用
`-Wl,--no-undefined` 实测该 API 下可链接。

**产物五项硬红。** ELF 类型、解释器是 Android linker（`/system/bin/linker64`）、
16KB 页对齐、DT_NEEDED 闭环（`libc++_shared.so` 必须在）、以及不在
`scripts/native-deps.txt` 白名单里的动态库。进编译前还有一道配对的 `make -n` 断言：
它保证「node 本体那次链接的命令行里确实有该标志」，五项门禁保证「产物里真的有」——
中间任何一环（ld 版本、链接顺序、段裁剪）都可能丢。

## 版本切换

一次只有一个版本生效。切换 = 改 `files/usr/bin/node` 指向哪个版本目录。
允许同时存在多个版本的**文件**（升级期间新旧共存，避免中途无可用），但只有索引选中的那一个被 `usr/bin/node` 指向。

## `usr/bin/node` 的建链：两个建链者，一个判据

`usr/bin/node` 有两处会写：

| 建链者 | 何时跑 | 目标从哪来 |
|---|---|---|
| `SupplyProvisioner.linkEntry` | 商店件落位时 | 该件的件内入口（`toolchain/node/bin/node`） |
| `PrefixProvisioner.linkNode` | 每次启动（`RuntimeEnvironment.ensure`） | `NodeRuntime.path(ctx)` |

**判据只有一处**：`NodeRuntime.path`。此前 `PrefixProvisioner` 与
`RuntimeEnvironment.treeRootFor` 各用 `ProgramManager.nodeBin`（只看程序通道），
而 `SupplyProvisioner` 管的是商店件 —— 两个建链者互不知情，会互相覆盖：
商店件装好后，启动路径把链改指回程序通道那份，`expected()` 还会报「缺 node」。

收敛后 `ProgramManager.nodeBin` 只剩 `NodeRuntime.path` 一个调用方。
`treeRootFor` 的兜底是 `usr/bin/node` 本身（`PrefixProvisioner` 刚建好的链），
不改 `Snapshot.nodeBin` / `TreeRoot.nodeBin` 的非空类型 —— 消费方
（`GuestAdapter` spawn、`NODE_BIN`、`PATH`）直接 `.absolutePath`，
改可空会波及三处，不值得。

## 上架

node 与 6 件工具链走**同一条商店通道**（见 `components/userland/COMPONENT.md` 的发布段）：
`build-userland.yml` 的 `node` job 取已编译件（从本仓或 dsh-mobile 的
`node-runtime-<version>-<abi>` Release，两种发布形态都认）→ 落成
`dist/bin/node` → `package-userland.sh node` → 投递 → 签进清单。

清单里 node 的条目已实测投影正确（CI run 37140173937 的 manifest job 输出
`node@24.21.0 入口 bin/node`），7 件齐全。

代价要说清：node 件 72MB（压缩后），首次安装要下这一份 —— 换来的是
**换 node 版本不用重发 APK**（116MB 的 APK 增量 vs 72MB 的单件增量）。

# runtime —— L1 运行时件

被别的程序**依赖才能运行**的构建物。当前只有 node。

## node

| 项 | 值 |
|---|---|
| 版本 | `24.21.0` |
| ABI | `arm64-v8a` |
| 商店包名 | `userland-node-<version>-<sha12>-android-arm64.zip` |
| 落盘 | `files/usr/lib/runtime/node/<version>/bin/node`（**一次只有一个版本生效**） |
| 依赖 | `libc++_shared.so`（由 `LD_LIBRARY_PATH` 指向 APK 的 `nativeLibraryDir` 供给） |
| 验收 | `node -v` 打印三段版本 |

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
- 并行度：`NODE_BUILD_JOBS` 覆盖，默认按内存算且**下限锁 2**。
  记录：CI 上 4 vCPU / 16 GB，`make -j4` 在 144 分钟被硬终止（不是超时，
  `timeout-minutes` 是 330）—— 标准 runner 被抢占或内存击穿。`-j2` 实测全程零 OOM。
- 产物形态门禁：`scripts/verify-runtime-elf.sh`
- 编译一次，制品发到 Release `node-runtime-<version>-<abi>`，后续构建复用不再重编。

## 版本切换

一次只有一个版本生效。切换 = 改 `files/usr/bin/node` 指向哪个版本目录。
允许同时存在多个版本的**文件**（升级期间新旧共存，避免中途无可用），
但只有索引选中的那一个被 `usr/bin/node` 指向。

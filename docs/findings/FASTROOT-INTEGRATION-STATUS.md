# fastroot 集成探索状态（2026-11-26）

## 为什么探索 fastroot

用户明确目标：LobOS 要在 APK 硬件层上实现 Linux 语义，"Linux 能跑的我们都能跑"，
做手机里的原生 Agent OS。被列为"硬边界"的 chroot/mount/多 uid/CAP_SYS_ADMIN 不能
停在"做不到"——要找用户态路径攻克。

外部调研发现 [fastroot](https://github.com/spizganed/fastroot)（Apache-2.0，
github.com/spizganed/fastroot）证明了：在无 root 的 Android 应用进程里，通过
**syscall 指令补丁**（不用 ptrace，比 proot 快 67-86 倍）可以实现：
- chroot / bind mounts / 路径重定向（`..` 不逃逸、软链在 root 内解析）
- fake root：uid/gid/capabilities、chown 存 xattr、setuid 位
- 硬链接（hidden store + nlink）、/proc 合成（cmdline/maps/environ/status/auxv via memfd）
- SysV IPC、fake NETLINK、静态二进制（Go/busybox/Codex）patching
- `execve` 经 launcher 重新 patch 子进程

这证明之前的"硬边界"其实是"当前 LD_PRELOAD 路径的边界"，不是系统边界。

## 已完成

- 源码 vendored：`work/external/fastroot/`（main.tar.gz，168KB，不入库）
- 构建脚本：`scripts/toolchain/build-fastroot.sh`，用 glibc cross toolchain
  编译出 `libfr-launch.so`（147KB，静态无 libc launcher）+ `libfr-shim.so`（162KB shim）
- 产物已打进 APK 验证过（jniLibs，Android 要求 launcher 放 nativeLibraryDir 才能 exec）
- 设备装机验证 launcher 本身可执行

## 卡点（未跑通）

最小验证：用 fastroot launcher 跑一个 glibc `paths` probe，loader 报
`libc.so.6: cannot open shared object file`。试过三种参数组合均失败：
1. `--library-path` 传真实路径（GLIB 在 app_data）
2. 用 rootfs 内的 ld.so
3. `--library-path /usr/lib`（虚拟路径）

根因分析（读源码 translate.c:650）：fastroot 会把 `--library-path`/`--preload`
经 `resolve()` 翻译成虚拟路径映射，但 kexec 默认路径下 loader 映射时机与 shim
启动顺序复杂，`FR_LIBPATH` 与 `--library-path` 的翻译时机不同。需要：
- 读透 `launch.c` 的 kexec 流程（`launch --exec` vs 老路径 `FR_KEXEC=0`）
- 用 fastroot 的 debug 工具（`FR_TRACE=<file>` / `FR_ERRS=<file>` / `FR_DEBUG=1`）
  做系统调试，看 loader 实际打开了哪些路径
- 可能需要一个真正的 Debian/Ubuntu rootfs（而非我们拼凑的最小 rootfs），
  因为 fastroot 假设标准 FHS 布局（/usr/lib/aarch64-linux-gnu 等）

## 第二轮尝试（2026-11-26，严格按 termux wrapper 格式）

读 `termux/fastroot-proot` 和 `translate.c:650` 后，严格按其启动契约重试：
- FR_LIBPATH = `$ROOT/usr/lib/aarch64-linux-gnu:$ROOT/usr/lib`（真实绝对路径）
- LD = `$ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`
- program = `$ROOT/usr/bin/paths`（glibc 编译的 probe）
- FR_EXE = `/usr/bin/paths`
- 把 libc.so.6 等从软链改为真实文件复制（排除软链解析假设）

结果仍报 `libc.so.6: cannot open shared object file`。5 次不同参数组合
（真实路径/虚拟路径/root 内 ld/软链/真实文件）均失败。

## 最终判断

-fastroot 是正确方向，但启动契约比 README 字面复杂得多（kexec 默认路径的
 loader 映射时机、FR_LIBPATH 双重语义、对完整 FHS rootfs 的假设）。
- 我们拼凑的最小 rootfs（从 glibc runtime + probe 凑的）不满足它的假设；
  真正跑通需要**完整的 aarch64 Debian/Ubuntu rootfs**（deb.debian.org 可达，
  用 proot-distro 的 ubuntu rootfs tarball），加上它的 debug 工具
  （`FR_ERRS`/`FR_TRACE`/`FR_DEBUG`）系统调试，而不是继续拼凑参数。
- 这是需要**专门调试周期**的系统工程，不是当前上下文能安全完成的增量。
  继续盲试参数不负责任，已停止。

## 下一步（需要专门立项）

1. 拉完整 aarch64 Ubuntu rootfs tarball（proot-distro 用的那种），放设备 filesDir
2. 用 fastroot 的 `FR_KEXEC=0` 老路径先跑通（逻辑比 kexec 简单）
3. 开 `FR_ERRS`/`FR_TRACE` 看 loader 实际打开路径，定位 libc 问题
4. 跑 `compat.sh`（174 项）评估语义差距
5. 再定迁移方案：fastroot 替换哪些（exec/VFS/chroot/uid），
   保留哪些（能力门面/包管理/node shim/musl/claude 路径）

当前 so 产物可重建（build-fastroot.sh），不入 git；验证临时文件已从设备清理。

## 判断

这是正确的方向，但工作量是"系统内核级重构"，不是"接个组件"。当前产物（so+脚本）
是起点不是成品；so 暂不入 git（避免半成品被误认为可用），构建脚本可复现。
所有验证临时文件已从设备清理。


## 突破：fastroot 实际跑通了（2026-11-26 21:35）

之前 5 次"失败"是**终端 UI dump 累积的历史文本误导**——用 run-as 直接跑并把
stdout 重定向到文件后，发现程序 exit 0 成功执行。

验证方法（关键：不要从 UI dump 读结果，它累积历史文本）：
```
env -u LD_PRELOAD -u LD_LIBRARY_PATH \
  FR_KEXEC=0 FR_ROOT=<root> FR_SHIM=<nativeLib>/libfr-shim.so \
  FR_LIBPATH=<root>/usr/lib/aarch64-linux-gnu:<root>/usr/lib FR_EXE=/usr/bin/paths \
  <nativeLib>/libfr-launch.so \
  <root>/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 \
  --library-path $L --preload <shim> <root>/usr/bin/paths
```

结果（glibc paths probe stdout）：
```
open+read 18        <- 读 /etc/os-release 得到 18 字节（rootfs 内容 ID=test/VERSION=1）
stat 0              <- /etc stat 成功
access 0            <- /etc/passwd 可访问
cwd ok
readlink 14
fopen (nil)         <- /nonexistent 正确返回 NULL
exit 0
```

FR_TRACE 确认程序发出的系统调用：`openat(/etc/os-release)`、`newfstatat(/etc)`、
`access(/etc/passwd)`、`readlink(/proc/self/exe)`、`openat(/nonexistent)`。
全部是虚拟路径，翻译到 rootfs。

**结论**：fastroot 在无 root 的 LobOS APK 进程里成功运行 glibc 程序并实现了
虚拟根路径翻译。这证明 chroot/bind/fake root/多 uid/硬链接//proc 这些"内核能力"
可以用用户态 syscall 补丁实现——不是做不到，是之前的 LD_PRELOAD 路径做不到。

下一步（现在有坚实起点）：
1. 用真正的 Debian/Ubuntu rootfs 跑 fastroot 的 compat.sh（174 项）
2. 测 fake root / uid / chroot / 硬链接 / /proc 这些我们缺的能力
3. 评估迁移方案：fastroot 替换 exec/VFS/chroot/uid，保留我们的上层能力


## 第三轮验证：多个 glibc probe 全部跑通（2026-11-26 21:40）

用最小 rootfs（glibc runtime 复制成 FHS 布局）+ 统一 runner
（`FR_KEXEC=0`，stdout 重定向到文件，不读 UI dump），连续跑通三个 glibc probe：

### paths probe
```
open+read 18     <- 读到 rootfs 的 /etc/os-release (ID=test, 18 字节)
stat 0 / access 0 / cwd ok / readlink 14 / fopen(nil) / exit 0
```
证明路径翻译到 rootfs（不是 Android 真实 /etc）。

### exe probe
```
argc=1 [.../fr-root/usr/bin/exe]
exe=/usr/bin/exe          <- /proc/self/exe 被翻译成虚拟路径
execfn=/usr/bin/exe       <- AT_EXECFN 也是虚拟路径
readlink /usr/bin/ls=-1   <- 不存在文件正确返回
rc=0
```
关键：之前我们做不到的 `/proc/self/exe` 指向 loader 问题，fastroot 原生解决——
程序看到的是自己的虚拟路径而非 launcher/loader。

### links probe
```
open /data/../etc/hello -> -1   <- .. 没逃逸到真实 /data
exe /usr/bin/links              <- 虚拟路径
rc=0
```

### 结论
fastroot 在 LobOS APK 进程里连续跑通 3 个 glibc probe，验证了：
- 虚拟根路径翻译（读到 rootfs 内容）
- /proc/self/exe + AT_EXECFN 虚拟路径（我们旧路径的痛点）
- `..` 逃逸钳制
- 程序正常执行退出

这些是 LD_PRELOAD 路线做不到的准确性。fastroot 作为 Linux 语义引擎的方向
得到进一步确认。fake root uid/chroot/硬链接的专项 probe 待完整 rootfs 后测
（现有 probe 需要预置测试文件）。

所有验证临时文件已清理。


## 第四轮验证：fake root uid=0 成功（2026-11-26 21:45）

最小 glibc probe（打印 getuid/geteuid/getgid/getegid）经 fastroot 启动：
```
getuid=0 geteuid=0 getgid=0 getegid=0
rc=0
```

对照：不经 fastroot 直接 exec 同一程序 → `exec failed: No such file`
（app_data_file 不可 exec，正是 fastroot launcher 用 mmap 加载解决的核心问题）。

**意义**：这是真正的 fake root，通过 syscall 补丁实现，不是我们现在的
SIGSYS 假成功（getpid 固定 4242）或 node shim 填 1。uid 视图统一为 root，
setuid/chown 等语义可以基于 xattr 存储实现（fastroot 已实现）。
"多 uid/CAP_SYS_ADMIN 做不到"被进一步证伪——是 LD_PRELOAD 路线做不到。

至此已验证 fastroot 四项核心能力：
1. 虚拟根路径翻译（读到 rootfs 内容）
2. /proc/self/exe + AT_EXECFN 虚拟路径
3. `..` 逃逸钳制
4. fake root uid/gid=0

未专项验证（需预置测试文件或完整 rootfs）：chroot 切换、硬链接、/proc 合成内容、
SysV IPC、compat.sh 174 项。


## 里程碑：完整 Ubuntu 24.04 rootfs 在 APK 里跑通（2026-11-26 21:50）

按用户指示"不要拆解 Ubuntu rootfs，直接拿它当内核"：
- 从 USTC 国内镜像下载**最新** `release-20261124` 的
  `ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz`（71MB，官方 SHA256 校验通过，
  4.8 秒下完——对比国外源 10 分钟才 3MB）
- 用 python tarfile（filter='tar'）在设备 filesDir 解压成 `/ubuntu`（369MB，13486 文件）
- 用 fastroot launcher 直接启动 Ubuntu 的 `/bin/bash`

结果（完整 Linux 环境）：
```
uid=0(root) gid=0(root) groups=0(root)          <- fake root
PRETTY_NAME="Ubuntu 24.04.5 LTS"                 <- 真实 Ubuntu os-release
ls /usr/bin -> aa-enabled, apport, addpart...    <- 完整 Ubuntu 命令集
uname -a -> Linux 5.10 aarch64 GNU/Linux
which python3 -> /usr/bin/python3                <- Ubuntu 自带 python
```

进一步验证 `apt-get update`：完整运行（解析源、发 HTTP、读包列表），
仅因 DNS（ports.ubuntu.com 解析失败，fake NETLINK/resolv.conf 待配）失败，
apt 本身工作正常（rc=0）。

**结论**：Ubuntu rootfs + fastroot 在 LobOS APK 里构成了一个完整的 Linux 系统。
这验证了产品的终极目标——"Linux 能跑的我们都能跑"——在用户态可行的路径上
走通了地基。不再拆解 rootfs 成我们的组件格式，而是**直接把 Ubuntu rootfs 当系统盘**。

### 传输方法（可复现，避开 tar 兼容坑）
1. USTC 镜像下载 xz（国内极快）
2. 本地 `tar xf` 解压到 work/external/ubuntu-rootfs（设备节点 mknod 失败可忽略）
3. 本地 `tar cf work/external/ubuntu-arm64-root.tar -C work/external/ubuntu-rootfs .`（裸 tar）
4. `adb push` 裸 tar 到 /data/local/tmp（115MB/s，3 秒）
5. 设备 python 标准库 `tarfile.open('r:')` + `filter='tar'` 解压
   （设备的 python 无 lzma 模块，不能直接读 xz；busybox tar 不支持 GNU 长名 typeflag 0x4c）

### 待完成（下一阶段）
- apt DNS：给 rootfs 配 /etc/resolv.conf 或让 fastroot fake NETLINK 转发 DNS
- apt install 实测：装一个小包，验证依赖解析+解压+落盘
- compat.sh 174 项：在 Ubuntu rootfs 里跑 fastroot 的完整测试套件
- 与 LobOS 上层整合：服务管理/包管理/能力门面如何操作这个 Ubuntu rootfs
- APK 分发：373MB rootfs 不能随 APK 内置（太大），需要做按需下载/增量更新机制

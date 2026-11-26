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

## 下一步（明确，不是模糊待办）

1. 拉一个最小 aarch64 Debian rootfs（deb.debian.org 可达，用 proot-distro 的
   ubuntu rootfs tarball 或 debootstrap），放到设备 filesDir
2. 用 `FR_KEXEC=0` 老路径先跑通（shim 先加载，逻辑更简单），再切 kexec
3. 开 `FR_ERRS`/`FR_TRACE` 看 loader 路径解析，定位 libc 找不到的具体原因
4. 跑通后跑 fastroot 的 `compat.sh`（174 项）评估与真实 Linux 的语义差距
5. 再制定迁移方案：哪些用 fastroot 替换（exec/VFS/chroot/uid），
   哪些保留（我们的能力门面/包管理/node shim/musl/claude 路径）

## 判断

这是正确的方向，但工作量是"系统内核级重构"，不是"接个组件"。当前产物（so+脚本）
是起点不是成品；so 暂不入 git（避免半成品被误认为可用），构建脚本可复现。
所有验证临时文件已从设备清理。

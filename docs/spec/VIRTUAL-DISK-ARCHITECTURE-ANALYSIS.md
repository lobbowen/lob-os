# 虚拟磁盘架构分析（2026-11-27）

用户判断：当前路径问题的根本原因是"没有在 Android 内建一块真正的 Linux
虚拟硬盘/独立文件系统，而是把 fastroot 直接装到 Android 硬盘的普通目录上"。
本文件记录四路并行调研结论，区分"架构事实"与"因果归因"。

## 结论先行

1. **架构事实成立**：当前 Ubuntu rootfs 确实是 `filesDir/ubuntu` 普通目录
   （`RootfsInstaller.kt:22`），不是独立文件系统镜像；无内核 mount/chroot/
   mount namespace（`CompatSemantics.kt:93-110`、fastroot `GAPS.md:32,35`）。
2. **因果需修正**：npm postinstall 的路径失败直接根因不是"没有镜像"，而是
   fastroot 的 `strip_root` 按字面前缀匹配，宿主传入了非 canonical 的
   `/data/user/0/...`，内核 `/proc/self/cwd` 返回 `/data/data/...` 导致前缀不匹配。
   fastroot 上游 `GAPS.md:51` 明确要求宿主给 canonical host paths。
   这是集成用法问题，已修（`UbuntuSystem.kt:62-67`、`PtySession.kt:631-639`）。
3. **镜像不是路径隔离的充分条件**：把 squashfs/ext4 镜像挂到 app_data 下某路径，
   进程 root 仍是 Android `/`，内核 `getcwd()`/`/proc/self/cwd` 仍返回宿主路径。
   只有 `chroot/pivot_root/mount namespace` 或用户态 syscall 翻译能改变路径语义。
4. **无 root APK 下，用户态路径翻译层（fastroot/proot）不可省**：mount 需要
   `CAP_SYS_ADMIN`，loop device/FUSE 都不可行（Termux 社区实测、fastroot GAPS）。
   fastroot 官方方案就是"普通目录 + syscall 翻译"，mtty/fastroot 官方 app 同样如此。

## 根因分层

| 层 | 问题 | 是否根因 | 证据 |
|---|---|---|---|
| 存储载体 | rootfs 是普通目录不是镜像 | 不是路径问题根因 | 镜像挂到普通路径仍暴露宿主 cwd |
| 路径语义 | 无内核 root/mount 边界 | 是"隔离缺失"的根因 | chroot/pivot_root 不可用，只能翻译 |
| 集成接口 | FR_ROOT 非 canonical | 是本次 npm 故障的直接根因 | GAPS.md:51，已修并验证 |
| 产品需求 | 系统盘纯净/可快照/可重置 | 是镜像方案的正当动机 | SYSTEM-DISK-BAKED.md:3-10 |

## 为什么镜像不能单独解决路径问题（原理）

- `getcwd(2)` 用进程的 root/pwd 生成路径（内核 `d_path.c`）；mount 点只改变
  查找目标，不改变进程 root（man7 path_resolution）。
- rootfs 镜像挂到 `/data/user/0/.../files/ubuntu`：guest 的 `getcwd()` 仍返回
  `/data/.../ubuntu/root`。
- chroot 到该挂载点并 `chdir("/")`：才返回 `/root`，但需要 `CAP_SYS_CHROOT`；
  pivot_root 需要 `CAP_SYS_ADMIN` + mount namespace——普通 APK 都没有。
- 因此只要内核 root 不变，就必须靠 fastroot 这种 syscall 层把路径"伪装"成虚拟根。

## 无 root Android 虚拟磁盘方案评估

| 方案 | 需 root | 解决路径问题 | 结论 |
|---|---:|---|---|
| 普通目录 + fastroot 翻译（当前） | 否 | 基本解决，需 FR_ROOT canonical + cwd 进 rootfs | 当前最优，已验证 |
| 普通目录 + proot | 否 | 基本解决，但 ptrace 慢 | 备选 |
| ext4/squashfs/erofs + loop mount | 是 | 配合 chroot 才解决 | 无 root 不可行 |
| FUSE 挂镜像 | 需系统条件 | 不自动解决 | 普通 APK 不可行 |
| 用户态镜像文件系统（改 fastroot 内部） | 否 | 可解决但需重做 fastroot FS 层 | 极高成本，非必要 |
| 自有内核/root/ROM + pivot_root | 是 | 真正解决 | 超出 APK 形态 |

证据：
- mount(2) 需 CAP_SYS_ADMIN：https://man7.org/linux/man-pages/man2/mount.2.html
- Termux loop mount 实测 Permission denied：https://github.com/termux/termux-app/discussions/3800
- fastroot GAPS：no user namespaces, no mounts, app has no CAP_SYS_ADMIN
- fastroot README：Linux system lives in a normal app folder
- mtty README：real ARM64 distribution in the app folder, no root/systemd/docker

## 建议路线（渐进，不盲目重写）

- **阶段 A（低风险）**：新增 `SystemRoot` 抽象，把 `RootfsInstaller.root()`/
  `UbuntuSystem.environment()`/`PtySession` cwd 逻辑收拢，加 cwd 回归测试
  （`node -p process.cwd()`、npm postinstall）。防双路径问题回归。
- **阶段 B（中）**：目录 + upper/快照语义，获得"纯净系统盘 + 可写层 + 可重置"
  的产品能力，这才是镜像方案真正该服务的目标（不是修 bug）。
- **阶段 C（按需）**：在抽象后接镜像后端；无 root 下若要真镜像 FS 需重写
  fastroot 内部，成本极高；有 root/ROM 时可用 loop/overlay，但不符合当前 APK 形态。
- **隔离语义**：只有走自有内核/ROM 才能从内核层消除；当前 fastroot 方案
  不是安全边界，不应承诺"Android /data 查无此目录"。

## 对用户判断的最终回应

- "要建立真正虚拟硬盘"作为**产品目标**（系统盘纯净、可快照、可重置、独立
  分发）是正当的，值得往阶段 B/C 走。
- 但把它作为**本次路径 bug 的根因**不准确：本次 bug 是 FR_ROOT 非 canonical
  的集成问题，已在路径层修复并验证。
- 镜像不能替代路径翻译层；当前无 root APK 下，fastroot 的用户态路径翻译
  是必需项，不是可去掉的补丁。

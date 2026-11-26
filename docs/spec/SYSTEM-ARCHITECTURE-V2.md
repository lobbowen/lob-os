# LobOS 系统架构 V2：Ubuntu rootfs 作为系统盘

## 决策依据

完整 Ubuntu 24.04 rootfs + fastroot 在 APK 里跑通了完整 Linux 闭环
（apt update/install + 执行安装的程序）。这改变了系统底盘：
- V1 底盘：自研用户态机制（LD_PRELOAD hook + VFS 翻译 + ELF 路由 + SIGSYS fake root）
- V2 底盘：Ubuntu rootfs（系统盘）+ fastroot（syscall 级执行引擎）

V1 的机制内核大部分被 V2 替代，且 V2 做得更准确（.. 钳制、真 fake root、
硬链接、/proc 合成、静态二进制 patch）。

## 双轨执行模型（不是全清，是两轨）

### 轨道 A：通用 Linux（fastroot + Ubuntu rootfs）
- 系统盘：`filesDir/ubuntu`（Ubuntu 24.04 rootfs，FHS 完整）
- 执行引擎：`libfr-launch.so` + `libfr-shim.so`（syscall 补丁）
- 适用：所有 glibc Linux 程序——bash、apt、python、perl、gcc、任意 apt 包
- 提供：chroot/bind/fake root(uid=0)/硬链接//proc/SysV IPC/静态 ELF patch

### 轨道 B：产品特化 musl（保留，不进 Ubuntu）
- 二进制：`libclaude-musl.so`（380MB）、`libcodex-musl.so`（314MB）
- 加载器：`libmusl-ld.so` + `libmusl-c.so`（patched musl loader）
- 原因：这两个产品只有 musl 版能在无 root Android 跑（glibc 版几十轮验证失败）；
  fastroot 明确不支持 musl。它们是产品核心，已验证可用，不能清。
- 位置：留在 nativeLibraryDir（APK 内），不搬进 Ubuntu rootfs

### 终端 PTY
- `liblobosbash.so` + `librivospty.so`：终端 UI 的 PTY 会话
- 保留：TerminalScreen UI 依赖，Ctrl-C/Z/jobs 已验证可用

## Fate 分类

### 删除（V1 mechanism kernel，被 fastroot 完全替代）
- `container/native/d1/` 全部：vfs-root.h、tmp-paths.c、open-fallback.c、
  exec-path.c、link-interpose.c、app-sigsys.c、tmp-redirect.h、compat-report.h
- `kernel/compat/`：Compat.kt、PeLoader.kt、PieceInspect.kt（ELF 形态识别）
- `kernel/elf/`：ElfFacts.kt、ExecBits.kt
- `kernel/layout/`：LdConfig.kt、SonameChain.kt、EntryLinks.kt（ldconfig/SONAME 软链，
  Ubuntu 自带 ldconfig）
- `kernel/proc/ExecEntry.kt`（ELF 形态判定）
- jniLibs：`libelfload.so`（已废）、`liblobosposix.so`（bionic VFS hook）、
  `liblobos-vfs-glibc.so`（glibc VFS hook，被 fastroot 替代）、
  `libguest-sigsys.so`（被 fastroot fake root 替代）

### 保留但重新定位（LobOS 自己的系统服务，不是 Linux 内核）
- `kernel/layout/SystemDirs.kt`：路径根抽象，上层 50+ 文件依赖——保留，改指向 Ubuntu rootfs
- `kernel/fs/StateFiles.kt`：原子写/journal 基础设施——保留
- `kernel/crypto/Crypto.kt` + `PackageVerifier.kt`：Ed25519 包信任链——保留
- `kernel/ipc/LocalExec.kt` + `PtySession.kt`：终端执行——保留（轨道 A 的 CLI 经它路由到 fastroot）
- `kernel/proc/ProcessLedger` + `SessionRegistry`：桥会话鉴权 + 进程账本——保留
- `kernel/spi/KernelServices.kt`：SPI 契约——保留
- `kernel/sysdev/SysDev.kt`：控制面设备元数据——保留（或简化）

### 保留（上层，不是内核）
- `services/` 全部（supply/reg/app/host/supervise/log/perm/device）
- `api/`（CapabilityBroker）
- `ui/`（Compose）

### 需要适配
- `services/reg/RuntimeEnvironment.kt`：LOBOS_* 环境变量注入改为 FASTROOT_*/Ubuntu 路径
- `services/app/TerminalProfile.kt`：终端命令路由到 fastroot+Ubuntu
- `services/supply/*`：包管理从"我们的 component 格式"改为 apt/dpkg（Ubuntu 自带）
- `host/KernelHostAdapters.kt`：PathProvider 指向 Ubuntu rootfs

## 执行顺序（依赖顺序，每步可验证）

1. 建抽象层：`UbuntuSystem.kt`（封装 fastroot launcher 启动、rootfs 路径、env）
2. 适配 RuntimeEnvironment → UbuntuSystem
3. 适配 TerminalProfile → 命令经 fastroot+Ubuntu
4. 验证产品：终端、claude（轨道 B）、codex（轨道 B）、dsh web、apt
5. 删除 V1 mechanism kernel（d1 全套 + kernel/compat/elf/layout 部分 + 废弃 so）
6. 重新定位保留的系统服务到 `system/` 包
7. 更新所有规范和文档

## 风险与边界

- **轨道 A/B 的隔离**：Ubuntu rootfs 和 nativeLibraryDir 是两套文件系统，
  claude/codex 需要访问 Ubuntu 里的文件（workspace）时如何处理？待设计。
- **rootfs 分发**：373MB 不能随 APK，需要按需下载/增量更新（ leveraging USTC 源）。
- **构建链**：Ubuntu rootfs 怎么进发布流程？首次启动下载 vs 预置。
- **产品兼容**：现有 component 体系（10 个预装 zip）与 apt 包管理并存还是迁移？

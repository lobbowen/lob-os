# LobOS 工程架构审计报告（第一阶段）

日期：2026-11-26
范围：container/app（139 个 Kotlin 文件 / 22636 行）、container/ui（16 文件）、
container/native（15 个 C/H 文件）、scripts（97 个脚本）、docs。

## 一、现状：四个模块的物理边界

| 模块 | 内容 | 行数规模 |
|---|---|---|
| `container/app` | Kotlin 业务与系统逻辑 + Android 入口 | 22636 行 / 139 文件 |
| `container/ui` | Compose UI（Screen/Contract/Components） | 16 文件 |
| `container/native` | C 垫片（d1 exec/open/vfs/sigsys、d2 flock、d3 pty、guest-sigsys） | 15 文件 |
| `scripts` | 构建/组件/验证脚本 | 97 个 |

app 模块一级包：kernel(29) / services(97) / device(8) / api(4) / control(1)。

## 二、职责边界：Android 应用层 vs 系统本体

### 属于 Android 应用层（宿主/适配，应隔离）
- `MainActivity` / `LobosNavHost` / `TerminalRoute` / `QuickAppLaunchActivity` — Activity 生命周期
- `OsApplication` / `OsHostService` / `BootReceiver` / `PackageInstallReceiver` — Android 组件
- `OsAccessibilityService` / `OsNotificationListenerService` / `PermissionCenter` — Android 系统能力适配
- `ScreenCaptureService` / `ScreenCaptureController` — Android 屏幕采集 API
- `StatusTileService` — Android Tile
- `device/` 整个包 — 8 个文件全部依赖 `android.*`

### 属于系统本体（应成为独立内核）
- `kernel/` 整个包 — exec/pty/fs/layout/elf/crypto/compat/security
- `services/reg` — 程序注册、运行时环境（系统状态）
- `services/supply` — 组件供应、安装（系统包管理）
- `services/host` — OsInit/OsState（系统生命周期，但和 Android Service 耦合）
- `services/supervise` — 进程监管
- `services/log` — Journal/RuntimeDiagnostics（系统日志）
- `services/power` — 电源策略（部分 Android 耦合）
- `services/perm` — 权限账本（部分 Android 耦合）

### 边界不清（重灾区）
- `services/app/TerminalProfile.kt` — 生成 bashrc、node shim，是系统能力但放 app 包
- `services/app/ProgramGroup.kt` — 程序组逻辑，系统级
- `services/reg/RuntimeEnvironment.kt` — 系统环境构建，但依赖 Android Context/filesDir
- `api/CapabilityBroker.kt` — 对外能力门面，直接引用 17 个包（上帝对象）

## 三、耦合问题清单（按严重度）

### P0 — 分层反转（kernel 依赖 services）
kernel 层通过 import + **全限定名**反向引用 services。`scripts/verify/verify-layer-deps.sh`
实测 **57 处**违规（含 android import）：
- `kernel/ipc/PtySession.kt` → `services.reg.RuntimeEnvironment`、`services.supply.ShellEntry`、`services.log.RuntimeDiagnostics`（8+ 处）
- `kernel/layout/SysEnv.kt` → `services.reg.RuntimeEnvironment`
- `kernel/layout/LdConfig.kt` → `services.reg.ProgramIndex`、`services.supply.SelfCheckReport/ProgramInstallPipeline`
- `kernel/proc/ProcessSupervisor.kt` → `services.reg.RuntimeEnvironment`
- `kernel/proc/ProcessLedger.kt` → `services.reg.ProgramRegistry`
- `kernel/compat/Compat.kt` → `services.log.RuntimeDiagnostics`
- `kernel/fs/NotifyRoute.kt`、`ReadRoute.kt`、`sysdev/SysDev.kt` → `services.log.RuntimeDiagnostics`

**危害**：内核不再是底座，而是依赖业务服务。任何 services 改动都可能破坏 kernel。这是"结构做了一半"的核心。

### P0 — RuntimeEnvironment 上帝对象
`services/reg/RuntimeEnvironment.kt`（226 行）注入 LOBOS_VROOT/PREFIX/PATH/LD_LIBRARY_PATH/glibc loader/musl loader/hook so 等所有环境变量，被 kernel/proc、kernel/ipc、services/* 到处调用。**环境构建是系统能力，却放在 services 且依赖 Android Context**，形成全网依赖中枢。

### P1 — services 层循环依赖
- `services.app → services.reg/supply`（27 处 import）
- `services.reg → services.app`（3 处）
- `services.supply → services.app`（2 处）
- `services.host → services.app`（5 处）
- `services.supervise ↔ services.reg/host/app` 多向

services 内部没有清晰的分层，形成网状调用。

### P1 — UI 职责跨模块分散
`container/ui` 有独立的 Screen/Contract，但 `container/app/services/app` 里还有
`LobosNavHost`/`MainActivity`/`TerminalRoute`/`DesktopIcons`/`QuickAppHost` 等 UI 装配与
Android Activity，导致 UI 逻辑一半在 ui 模块一半在 app 模块。

### P1 — native 垫片职责混杂
`d1/` 把 exec 路由、路径翻译、VFS、SIGSYS、link、env、ELF loader 全塞一个 so：
- `exec-path.c` 779 行（exec 路由 + node shim + loader 选择 + raw exec + elfload 兜底）
- `vfs-root.h` / `tmp-redirect.h` / `open-fallback.c` / `tmp-paths.c` — 路径虚拟化分散在 4 处
- `lobos-elfload.c` — userland ELF 加载器（现在降级为兜底）

这些是**内核的 syscall 垫片层**，但没有独立的内核接口定义，和 Kotlin kernel 层通过环境变量（`LOBOS_*`）隐式耦合——13 个 LOBOS_* 环境变量散落在 Kotlin 和 C 两侧，没有单一契约。

### P2 — 环境变量隐式协议
`LOBOS_VROOT` / `LOBOS_HOOK_SO` / `LOBOS_PTY_SHELL` / `LOBOS_GLIBC_LOADER` /
`LOBOS_GLIBC_LIBDIR` / `LOBOS_GUEST_SIGSYS` / `LOBOS_ELFLOADER` / `LOBOS_MUSL_LOADER` /
`LOBOS_MUSL_LIBDIR` / `MUSL_ON_TERMUX` / `LOBOS_SYSTEM_LINKER` / `LOBOS_COMPAT_LOG` /
`LOBOS_OWN_SESSION` / `LOBOS_SYSENV` — 14+ 键，Kotlin 侧写、C 侧读，零类型约束、零契约文件。

### P2 — api 层上帝对象
`CapabilityBroker.kt` 直接引用 17 个包，是"万能门面"。它作为对外 API 层没有分层，
把 device/services/kernel 全串在一起。

## 四、架构判断

这个工程现在的状态是：**功能上跑通了，但结构上是"渐进累积"的**。
- kernel 名义上是内核，实际上反向依赖 services，不是真正的底座
- services 是业务与系统逻辑的混合体，内部网状耦合
- Android 宿主适配（Activity/Service/device）混在 services/app 里
- native 垫片层是事实上的"系统调用内核"，但没有契约、职责混杂
- 环境变量作为跨层协议，是最大的隐性耦合

要达到"未来维护清晰、可迭代、低耦合"，必须做一次结构性重构：
1. 确立清晰的分层与依赖方向（单向）
2. 把"系统本体"从 Android 应用层里剥离
3. 给 native 垫片层定义正式的契约（一个 syscall 垫片模块）
4. 把环境变量协议收敛为一个类型化的 Config 对象

具体方案见 `docs/spec/ARCHITECTURE-REFACTOR-PLAN.md`。

# LobOS 分层边界规范（宿适配 vs 系统本体）

配套：ENGINEERING-STANDARD.md（总入口）、DEEP-ARCHITECTURE-AUDIT.md（问题依据）

## 一、两条边界的定义

APK 是生存环境（像硬件）。在这个 APK 内，代码必须分成两大类，不许混：

### A 类：宿适配层（adapter）—— 适配 Android 产生基础生存环境
**职责**：把 Android 提供的能力翻译成 LobOS 系统能用的形式；处理 Android 生命周期。
**允许使用**：任何 `android.*` / `androidx.*` API（这是它的本职工作）。
**典型内容**：
- Android 四大组件：Activity / Service / BroadcastReceiver / ContentProvider
- Android 系统能力适配器：Accessibility（手势/UI树）、Notification、MediaProjection 截屏、
  WifiManager、PowerManager、AlarmManager、DevicePolicyManager、ShortcutManager、
  PackageInstaller、Settings.Secure、QS Tile
- Android 资源访问：assets、nativeLibraryDir、filesDir/cacheDir 的取得
- Android 生命周期：Application.onCreate、Service 启停、广播接收
- SPI 的宿主实现：把 Android 能力接到系统 SPI 上

### B 类：系统本体（core）—— 真正的 LobOS 系统
**职责**：实现 LobOS 自己的系统语义（进程/文件/ELF/包管理/终端/状态/能力门面）。
**禁止**：直接使用 `android.*` / `androidx.*`；用 SPI 向宿适配层要能力。
**典型内容**：
- 系统机制：进程/PTY/文件/ELF/layout/crypto/状态机
- 系统服务：supply/registry/supervise/log/power/perm（策略层）
- 应用层：程序管理、终端、快应用逻辑
- API 门面：transport + 方法分发（不实现 Android 业务）
- native 垫片：exec 路由/VFS/SIGSYS（事实 syscall 层）

## 二、判据（怎么判断一个文件属于哪类）

问三个问题，任一为"是"即 A 类：
1. 它是否**定义或实现 Android 组件**（继承 Activity/Service/Receiver/Provider/TileService）？
2. 它是否**直接调用 Android 专有能力 API**（Accessibility/MediaProjection/Notification/
   Settings/Wifi/PowerManager/AlarmManager/ShortcutManager/PackageInstaller/Assets）？
3. 它是否是**Android 生命周期入口**（Application.onCreate、组件回调）？

否则为 B 类。特别注意：
- **仅用 `Context.filesDir` 当路径根** ≠ A 类 —— 这是 B 类误用 Android，
  应改为 SPI 注入路径（当前 kernel 25 处 android import 多属此类）
- **仅用 `android.util.Log` 打日志** ≠ A 类 —— B 类应用 KernelLogger
- **仅用 `android.os.SystemClock/Os`** ≠ A 类 —— B 类应用 SPI 或 java 标准库

## 三、目标目录归属

```
lobos/
  host/                  A 类：宿适配层（唯一允许 android.* 的业务目录）
    Application / Service / Receiver / Tile
    device/              Android 系统能力适配
    adapters/            SPI 宿主实现
  system/                B 类：系统机制层（现 kernel/ 正名候选）
    spi/                 能力契约（系统层定义，宿主实现）
    ipc/ fs/ proc/ elf/ layout/ compat/ crypto/
  services/              B 类：系统服务层
  app/                   B 类：应用层
  api/                   B 类：能力门面（transport + handler）
```

物理上仍在 `container/app/src/main/java/lobos/` 一个 module 内；这是逻辑分层。

## 四、当前混杂状况（审计实测，2026-11-26）

- 141 个 Kotlin 文件中：**109 个有 android 依赖，32 个纯系统本体**
- 109 个中，94 个用 Context；其中大量只是拿 filesDir 当路径根（B 类误用）
- `services/app` 21/22 有 android 依赖；`kernel` 各子包大量有 android 依赖；
  `device/` 8/8 有 android 依赖（这是正确的 A 类，但位置在 device/ 而非 host/device/）

## 五、重构顺序（按判据执行）

1. **先归位 A 类**：所有定义 Android 组件/调用 Android 专有能力的文件，物理移到 host/ 下
   （device/ → host/device/、Activity/Service/Receiver/Tile → host/）
2. **再清洗 B 类的 Android 依赖**：Context 改 PathProvider SPI、Log 改 KernelLogger、
   SystemClock/Os 改 SPI；让 system/services/app/api 真正零 android import
3. **建立校验**：verify-layer-deps.sh 恢复 android 检查——
   只有 host/ 目录允许 android.*，其余目录出现即 FAIL
4. **每步验证**：编译 + 三校验 + claude/codex/dsh/终端

## 六、一句话判据

**"通信/生命周期/硬件能力靠 Android → host；系统自己的语义 → system/services/app/api"。
宿主适配是外壳，系统本体是内核，两者在 APK 内共存但绝不混源文件。**

# 二层架构定义与复检结论（2026-11-27）

用户要求：把整个项目按二层结构理清——安卓层（APK/界面/适配/系统盘安装/
虚拟磁盘/桥接）与内核层（Ubuntu rootfs 系统本体 + fastroot + libghostty-vt，
一套 Linux，不含 Android 概念），两层之间有明确连接点。所有结构按模块化组织，
废弃的清干净。本文是四路全项目复检的结论与目标定义。

## 一、当前结构（复检事实）

### Gradle 模块
- `:app` — 宿主 APK + 几乎所有 Kotlin 源码
- `:ui` — Compose UI（终端/首页/空间/应用/设置）
- `:term` — vendored mtty-term/libghostty-vt（VT 内核）

### app 内 lobos/* 包按二层分类（81 个 .kt）

**A 安卓层**（依赖 Android API，服务 APK/UI/宿主）：
- `host/` — Application/Service/Activity、Foreground、Doze、通知、设备适配（device/）、KernelHostAdapters
- `api/` — CapabilityBroker/ApiSpec/权限Ledger/SystemFilesProvider（进程内能力分发，UDS 已移除）
- `services/app/` — TerminalRoute/Session、UI 业务、HomeViewModel、SpaceViewModel
- `services/log/`, `services/perm/`, `services/power/` — 日志/权限/电源（Android 生命周期相关）
- `ui 模块` 全部

**B 内核层**（应纯 Linux 语义）：
- `system/SystemRoot.kt` — 系统盘契约（hostRoot/canonicalRoot/cwdHost/FR_*/launchArgv）。活跃连接点，承载 Linux/fastroot 语义，但接口参数是 Android Context，严格说属 C
- `system/UbuntuSystem.kt` — 门面，委托 SystemRoot，调用点遍布 host/api/services
- `system/SystemExec.kt` — 一次性命令执行（ProcessBuilder + FR_*）；活跃
- `native/d3/pty-session.c` — PTY host（唯一 native C）
- `term/` — libghostty-vt VT 内核 + JNI shim
- `jniLibs` — libfr-launch/libfr-shim（fastroot）、librivospty（PTY）、libmtty-vt/libmtty-xbuf（VT）；无 V1 残留

**C 连接点**（安卓层 → 内核层）：
- `system/RootfsInstaller.kt` — 从 APK asset 解压系统盘到 filesDir（安卓生命周期调用）
- `system/SystemRoot.kt` / `UbuntuSystem` — 系统盘路径/cwd/env/argv 的单一真相源
- `system/SystemExec.kt` — 命令执行桥
- `kernel/ipc/PtySession.kt` — 终端 PTY 会话（安卓侧 ProcessBuilder + fastroot argv）
- `host/KernelHostAdapters.kt` — kernel SPI 注册（paths/logger/diagnostics/env）
- `api/CapabilityBroker.kt` 的 `sys.ubuntu.exec` / `sys.tty.exec` — 进程内能力到执行的入口

**分层判据校准**（避免机械按 import android.*）：
- 禁止出现在内核层的：`android.content.Context/Activity/Service/Bundle/Intent`
  等 APK/UI/生命周期概念——这些才是"安卓业务依赖"。
- 可接受的：`android.system.Os/OsConstants`（等价 Linux syscall 封装，
  与 LocalExec 用 raw syscall 同性质）、`android.util.Base64`（纯工具）。
- 实测 kernel/ 21 个文件中 3 个有 Android 引用，但均为上述可接受的 syscall/工具类
  （Crypto/StateFiles/ProcessLedger），无 APK/UI/生命周期依赖。
  曾尝试机械移到 services/sys，导致 kernel 反向依赖 services 且校验失败，已回退。
- `system/` 名字容易与"内核层"混淆：它实际是连接点（系统盘语义 + Android Context）。

### 废弃残留（可清）
- 顶层 `work/` 7.5G（sources/artifacts/tarballs/external 等构建工作区）
- `verified-components/` 351M（旧组件 zip）
- `dist-glibc-runtime/`（旧 glibc 组件）
- `docs/spec/ONE-STANDARD.md`（V1 历史存档，不在 git，保留本地即可）
- `scripts/` 已清理到活跃链（verify-layer-deps/verify-directory-layout/verify-engineering-standard/
  verify-apk-native/verify-apk-signing/verify-runtime-elf + build-fastroot/build-pty-host/
  build-node-android/cache-ndk/locate-ndk + publish/inject-apk-keystore）

## 二、目标二层架构

```
container/
  app/                      # 安卓层 APK
    src/main/java/lobos/
      host/                 # 安卓宿主：Application/Service/Activity/适配/保活
      api/                  # 进程内能力分发（安卓侧 API 契约）
      ui-bridge/            # services/app 中 UI 业务路由（TerminalRoute/HomeViewModel…）
      connect/              # 连接点（原 system/）
        SystemRoot.kt       # 系统盘契约（无 Android 字段的语义中心）
        SystemExec.kt       # 执行桥
        RootfsInstaller.kt  # 系统盘安装（依赖 Android assets/filesDir）
      platform/             # 原 kernel/ 中纯 Linux 语义部分（去掉 Android import）
    src/main/assets/system/ # 内置系统盘镜像
  ui/                       # 安卓层 UI（Compose）
  term/                     # 内核层：VT 仿真（libghostty-vt）
  native/                   # 内核层原生：pty-session.c（PTY host）
```

**分层规则**：
- `connect/` 是唯一同时知道 Android 与 Linux 的地方，窄接口
- `platform/`（内核层）不 import android.*，纯 Linux/文件/进程语义
- 安卓层不直接操作 rootfs 内部路径，只通过 connect 契约
- Gradle 模块先保持 :app/:ui/:term（不过度拆分），先做包边界与契约

## 三、迁移步骤（每步可编译）
1. 文档固化（本文）
2. 清理顶层废弃目录（work/ / verified-components/ / dist-glibc-runtime/）
3. 将 `kernel/` 中 3 个 Android 依赖文件迁到合适位置或抽 SPI，使 kernel 纯度达标
4. 将 `system/` 重命名为 `connect/`（或保留 system 但在文档中明确它是连接点），统一 SystemRoot 契约
5. 加结构校验脚本：kernel 无 android.* import、connect 是唯一访问 FR_*/rootfs 的地方
6. 每步编译+总校验+设备终端回归

## 四、原则
- 不为了"分层"过度拆 Gradle module；先契约与目录清楚
- 系统盘是 Linux 本体，Android 层只负责"安装它 + 把执行请求交给它"
- 快应用定位见 QUICKAPP-STRATEGIC-POSITION.md（安卓层前端，未来业务）
- 虚拟磁盘路径语义见 VIRTUAL-DISK-ARCHITECTURE-ANALYSIS.md（当前无 root 用 fastroot 路径翻译）

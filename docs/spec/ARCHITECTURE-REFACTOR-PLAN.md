# LobOS 架构重构方案（第一阶段）

配套：`docs/spec/ARCHITECTURE-AUDIT.md`（现状审计）

## 一、目标：四层单向依赖架构

```
┌─────────────────────────────────────────────────────────┐
│  L4 宿主适配层 (host)   ← 唯一知道 Android 的一层        │
│  Activity / Service / BroadcastReceiver / device/*      │
├─────────────────────────────────────────────────────────┤
│  L3 应用与API层 (app + api)                              │
│  UI装配 / CapabilityBroker / 程序注册表门面               │
├─────────────────────────────────────────────────────────┤
│  L2 系统服务层 (services)                                │
│  supply / reg / supervise / log / power / perm           │
├─────────────────────────────────────────────────────────┤
│  L1 系统内核层 (kernel)   ← 不依赖任何上层               │
│  exec / pty / fs / elf / layout / crypto / compat        │
├─────────────────────────────────────────────────────────┤
│  L0 native垫片 (liblobosposix.so) + 契约                 │
│  syscall interpose / ELF loader / SIGSYS / vfs           │
└─────────────────────────────────────────────────────────┘
```

依赖方向严格自上而下：L4→L3→L2→L1→L0。任何反向依赖都是错误。
L1 kernel **不得** import `lobos.services.*` / `lobos.device.*` / `android.*`（除接口定义）。

## 二、核心抽象：把隐式耦合变成显式接口

### 抽象1：KernelContext（取代散落的 Context 传递）
kernel 层需要一个能力集合，而不是传 Android `Context`：
```kotlin
// lobos.kernel.KernelContext
data class KernelContext(
    val filesDir: File,          // 系统盘根
    val nativeLibDir: File,      // 可执行 so 位置
    val config: LobosConfig,     // 见抽象2
    val logger: KernelLogger,    // 取代 services.log 反向依赖
)
```
kernel 的每个机制（PtySession/ExecEntry/ProcessSupervisor）只依赖 KernelContext，
由 L4 宿主层构造并注入。这样 kernel 不再 import services/device。

### 抽象2：LobosConfig（取代 14+ 环境变量）
把 Kotlin 侧写、C 侧读的 LOBOS_* 环境变量收敛为一份**单一契约**：
```
docs/contracts/NATIVE-ABI-CONFIG.md  （键、类型、生产者、消费者、默认值）
```
Kotlin 侧一个 `NativeConfig.toEnv(): Map<String,String>` 统一生成。
C 侧一个 `lobos-config.h` 定义所有键名常量（现在是散落的 getenv 字面量）。
新增键必须同时更新契约文档 + Kotlin + C 三处——用脚本校验一致性。

### 抽象3：KernelLogger（切断 kernel→services.log）
```kotlin
interface KernelLogger { fun log(stage: String, ok: Boolean?, msg: String, detail: String?) }
```
kernel 层依赖这个接口；宿主层把它接到 services.log.RuntimeDiagnostics。
消除 kernel 里所有 `services.log` 全限定引用（8+ 处）。

### 抽象4：RuntimeEnvironment 拆分
把 226 行的 RuntimeEnvironment 拆成三个单一职责：
- `SystemPaths`（paths: filesDir/usr/opt/nativeLib — 纯数据，无 Context 逻辑）
- `NativeEnv`（构造 LOBOS_* env map — 依赖 LobosConfig）
- `ProcessEnv`（PATH/LD_LIBRARY_PATH 组装 — 依赖 SystemPaths + NativeEnv）

### 抽象5：CapabilityBroker 门面分层
`CapabilityBroker`（17 包引用）按能力域拆为多个 Provider：
- `ProgramCapability` / `ProcessCapability` / `DeviceCapability` / `SystemCapability`
- CapabilityBroker 只做路由分发，不含业务逻辑

## 三、目标目录结构

```
container/
  kernel/          ← 新 module（或 app 内独立 source set）
    src/main/java/lobos/kernel/
      context/KernelContext.kt
      exec/ExecEntry.kt ProcessSupervisor.kt
      pty/PtySession.kt
      fs/  elf/  layout/  crypto/  compat/
      spi/KernelLogger.kt SystemPaths.kt   ← 内核对外SPI
  system/          ← 现 services 的系统本体（L2）
    src/main/java/lobos/system/
      supply/ registry/ supervise/ log/ power/ perm/
  app/             ← Android 宿主 + UI装配（L4）
    src/main/java/lobos/app/
      MainActivity.kt LobosNavHost.kt host/ device/
  api/             ← 能力门面（L3）
  ui/              ← 纯Compose（保持，但不依赖app）
  native/          ← L0, 按职责拆
    d1/exec/  d1/vfs/  d1/sigsys/  d2/flock/  d3/pty/
    contract/       ← lobos-config.h / syscall-api.md
```

**结构策略（按用户理念修正）**：APK 是安装载体，LobOS 是这一个 APK 下的独立系统。
因此**不做物理 Gradle module 拆分**（与 APK 载体冲突），全部逻辑分层都在 app module 内
用包结构 + SPI + 编译期依赖检查实现。系统独立性靠"自成体系的机制层 + Android 依赖
只出现在宿适配面"保证，不靠把代码搬出 app module。上面的目录树是**逻辑分层示意**，
物理落点仍是 `container/app/src/main/java/lobos/`。

## 四、迁移路径（按风险递增，每步可验证）

### 阶段0：建立纪律（低风险，立即做）
1. 写 `docs/contracts/NATIVE-ABI-CONFIG.md`（14 个环境变量的契约）
2. 写 `scripts/verify/verify-layer-deps.sh`：检查 kernel 不 import services/device/android
   - 目前会失败（4 import + 8 FQN），作为 TODO 清单
3. 写 `scripts/verify/verify-native-config.sh`：比对 Kotlin 字符串与 C getenv 字面量

### 阶段1：切断 kernel→services（中风险，核心）
1. 引入 `KernelLogger` SPI，替换 kernel 里 8 处 RuntimeDiagnostics 调用
2. 引入 `SystemPaths`/`ProgramLookup` SPI，替换 kernel 里 ProgramIndex/ProgramRegistry 调用
3. 引入 `ShellProvider` SPI，替换 PtySession→ShellEntry
4. 宿主层实现这些 SPI 并注入
5. 验证：verify-layer-deps.sh 通过

### 阶段2：收敛环境变量（中风险）
1. 建 `NativeConfig` 数据类 + `toEnv()`
2. C 侧建 `lobos-config.h` 常量
3. RuntimeEnvironment 改用 NativeConfig
4. verify-native-config.sh 通过

### 阶段3：RuntimeEnvironment 拆分（中风险）
1. 拆 SystemPaths / NativeEnv / ProcessEnv
2. 更新所有调用方（kernel/proc、kernel/ipc、services/*）
3. 行为不变验证（claude/codex/dsh 回归）

### 阶段4：UI 职责归位（低风险）
1. app/services/app 里的 NavHost/MainActivity/TerminalRoute 移到 app/app 或 app/host
2. ui 模块保持纯 Compose，只通过 Contract 与 app 通信
3. 消除 app/services/app 与 ui 的双向依赖

### 阶段5：services 内部去环（高风险，最后做）
1. 明确 services 内部方向：supply → reg → supervise → app（单向）
2. 用事件/接口打断 app→reg/supply 的反向调用（27 处重灾区）
3. 这步改动最大，放在最后，且有前几步的 SPI 基础后做

### 阶段6：native 层按职责拆文件（低风险，可并行）
1. exec-path.c 按职责拆：route.c / node-shim.c / loader-dispatch.c
2. vfs-root.h + tmp-redirect.h + open-fallback.c + tmp-paths.c 合并为一个 vfs.c
3. 保持导出符号不变，行为不变

## 五、重构验收标准

1. `verify-layer-deps.sh` 通过：kernel 不依赖上层
2. `verify-native-config.sh` 通过：env 契约三处一致
3. 端到端回归：claude TUI / codex TUI / dsh web 401 / 终端 PTY 稳定
4. 新增一个 CLI 只需：安装包 + 标准 PATH，不改任何 Kotlin/C 代码
5. 每个 kernel 机制都有明确的 SPI 边界，可在不启动 Android UI 的单元测试中验证

## 六、不做的事

- 不立即物理拆 Gradle module（先用包结构+校验脚本建立纪律）
- 不重写 native 垫片的功能（只拆文件/收敛契约）
- 不改用户可见行为（本次是纯结构重构）
- 不引入新的第三方依赖（保持零依赖原则）

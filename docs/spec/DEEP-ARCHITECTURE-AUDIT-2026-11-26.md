# LobOS 深度架构审计报告（摊开式 · 2026-11-26）

方法：5 个独立审计并行逐文件读代码（kernel/services/边界/native/跨层），
交叉核对文档与实现。本次只读审计，不改代码。
配套：ARCHITECTURE-AUDIT.md（第一轮静态审计）、ENGINEERING-STANDARD.md（规范）。

## 零、总结论（先说 hardest truth）

**阶段 1 重构 + 目录整理解决了"看得见的乱"，但没解决"结构性的乱"。**

三句最重要的话：
1. **分层脚本 PASS 不等于架构成立**。`verify-layer-deps.sh` 通过，是因为检查口径被改弱
   （删掉了 android import 检查）；kernel 仍有 25 处 `android.*`，四层单向架构在代码里不成立。
2. **SPI 把耦合从 import 搬到了运行时**。kernel→services 的编译依赖没了，但
   `KernelServices` 全局服务定位器 + 运行时注册让依赖在运行时原样存在，而且更隐蔽
   （no-op 默认实现静默降级）。
3. **"kernel"这个命名本身是误导**。这一层的真实身份是"Android 宿主上的用户态机制
   翻译层 + 运行时装配件"（翻译 dpkg/ldconfig、/proc、PTY 守护、journaling），
   不是有特权边界/地址空间/资源调度的内核。继续叫内核会让 README 的边界规则失去可执行性。

## 一、按层的问题分布（摊开）

### 1.1 kernel 层（30 文件 + 5 SPI）

**P0（结构性，正在制造失控）：**
- `ipc/PtySession.kt`（867 行）— 10 类职责的上帝对象：协议帧/宿主生命周期/双会话表/
  PID 记账/sysdev 渲染/环境注入/LD_PRELOAD/日志。全局 `host` + `ProductSessions` 双表。
  证据 `PtySession.kt:242,614-617,715-741,331,635,787`
- `sysdev/SysDev.kt`（512 行）— 渲染器+校验器+diff+索引+全局可变状态，与 PtySession 双向依赖
  （`PtySession:331` ↔ `SysDev:62,117,370`）

**P1（明确违反分层/正确性隐患）：**
- 25 处 `android.*` 分布在 21 文件：`Context` 被当"根路径+资源句柄+日志开关"三合一；
  `Os`/`Log` 直接依赖。Context 在 `ProcessSupervisor:35`/`PackageVerifier:22`/`LocalExec:33,67,143`
  传入但不用。
- `KernelHooks` 是绕开 SPI 的第二条全局回调通道；`PtySession` 同时用两条
  （`:265` 走 SPI、`:459,478,487,576` 走 Hook）。"单 seam"注释（`KernelHostAdapters:19-23`）失实。
- 决策进入内核：`LocalExec:42-57` 选通道、`LdConfig:99-118` 决定建链、`ProcessLedger:105-126`
  决定杀进程树、`ExecEntry:115-121` 决定 guest 路由。
- 展示文案进入内核：`Crypto:77,93,114`、`Compat:125-136`、`SysDev` 多处中文叙述/`**加粗**`。
- `kernel/spi/` 抽象泄漏：`ProgramQuery.Unit` 的 `role/stateDir/assetEntry` 内核不用；
  `SystemDiagnostics.selfCheck` 返回 UI 中文串；no-op 默认返回空串会让
  `SonameChain:46-49` 把"无摘要"误判成"字节相同"触发错误建链。
- `Crypto.kt` 的 `verifyEd25519WithFacts` 是调试产物；`Ed25519Pure` 自研密码学无测试向量。
- `ProcessSupervisor:71-75` 靠正则匹配 `Process.toString()` 取 PID，极脆弱。
- `SessionRegistry` 令牌明文 JSON + 20 秒宽限，无签名绑定。

**P2（局部坏味道）：**
- 死代码：`LobosEnv.kt` 全文件无调用者；`LdConfig:204` 在 return 后；
  `EntryLinks.pieceId` 未用；`PrefixProvisioner:6` 未用 import。
- 包级环：`elf/ExecBits` ↔ `proc/ExecEntry`（PE 魔数判定重复）。
- `mm/LibPath.kt` 仅一行别名，`mm` 子包名不副实。
- 无测试：自研密码学、PE 解析、协议帧均无单测。

### 1.2 services 层（97 文件）

**关键发现：services 仍是全连通网状，阶段 5（去环）完全没做。**
- 双向依赖计数：supply↔reg（22/14）、reg↔app（3/反向 27）、host↔app（5/多）、
  supervise↔reg（12/多）。规范要求的"supply→reg→supervise→app 单向"不存在。
- **host 子层 4 个 high 问题**（二次核验）：
  - `HostControlService.update/rollback` 调 `broker.beginSession` 后从不释放，
    `SessionRegistry.clear()` 无调用者 → 每次管理操作残留 token + socket。
  - `OsState:38-43` 禁止 `BOOTING→RUNNING`，但 `OsHostService:104` 强制该跃变。
  - `hostAlive` 置 true 后不复位，`onDestroy` 不清理 → 宿主死后 `ensureRunning` 提前返回，永不重启。
  - RUNNING 在组件就绪前就持久化（`:104` vs `:166-186`）。
- 版本目录用字典序 `maxByOrNull{it.name}`（`KernelHostAdapters:40-46`），`"9"` 排在 `"10"` 上。
- `BootReconciler:57-74` 三段清理静默吞失败。
- `api ↔ host` 成环：`CapabilityBroker` 被 host 引用，同时 broker import services.host。

### 1.3 app / host / api / ui / device 边界

**AndroidManifest 11 个组件散落 5 个包**（host 4 / device 3 / app 2 / api 1 / power 1）：
- `api/StatusTileService` 站错位：QS Tile 是 system-ui 适配，不是 API 层，且直接依赖 host。
- `services/app/MainActivity`、`LobosNavHost` 等 7 个 UI 外壳文件应归 ui/shell。
- `QuickAppLaunchActivity:11-52` 在 Activity 里藏 `sys.tty.exec` shell 执行探针 — 高。
- `SystemFilesProvider` 导出整个 `filesDir` 而无限定白名单 — 中高。
- `AccessibilityServiceState:27-30` 组件名硬编码 `.lifecycle.OsAccessibilityService`，
  manifest 注册的是 `.device.OsAccessibilityService` — 高（组件名错配）。

**`services/app` 22 文件是 4 类东西混在一起**：UI 外壳 7 / 快应用 8 / 系统预装兼容 5 / 终端 2。
其中 `CompatSemantics`/`DriverRegistry`/`PieceProvisioner`/`PreinstalledPieces`/`TerminalProfile`
这 5 个是系统本体，站在 app 包里 — 高。

**`api/CapabilityBroker` 是 1833 行 / 82 方法的单体**，不是薄门面：
- 实含 UDS 传输 + 会话鉴权 + OTA 幂等状态机 + 程序/端口/目录 + 设备能力 + 本地 invoke 第二入口。
- `api/README:23-31` 声称"无状态只转发"与实现冲突。
- 可拆：现有 `MethodDef` 表驱动已留缝，按域拆 `bridge/os/program/package/device` handlers。

**UI 编译期单向（app→ui），概念双向**：ui 契约承载系统模型，app ViewModel 产出 ui 状态并反向
import ui 类型（29 处）。`TerminalBuffer` 有状态缓冲被 app 运行时直接持有。

**device 层 3 种授权持久化策略不一致**：a11y 写 `Settings.Secure`、通知只读、截图序列化
Parcel 落盘；且 device 通过 `KernelHooks.ensureHost` 反向触 host，host 又直接 new device 控制器。

### 1.4 native 垫片层（15 文件 / 2652 行）

**结构**：6 个 C 编成 `liblobosposix.so`，导出 3 个 JNI 符号（对应 3 个 external fun）。

**P0/P1：**
- `exec-path.c` 784 行做 7 件事（raw syscall/环境注入/ELF 解析/node shim/exec 路由/
  exec 族/JNI），Node 策略与业务硬编码在 C（`:17` 硬编码 shim 路径）。
- **虚拟根 `/tmp` 目标分裂**：`vfs-root:81` 把 `/tmp` 映射到 `$VROOT/.tmp`，
  `RuntimeEnvironment:52,172` 把 `TMPDIR` 设为 `cacheDir` — 两个不同目录。
- **路径 `..` 穿越**：只做字符串前缀拼接，不规范化，`/tmp/../../x` 可逃出 vroot。
- **open EACCES 兜底语义错误**：`open-fallback:19-35` path 是 HOME 祖先时返回 HOME 目录 fd。
- 13 个 `LOBOS_*` env 契约无文档，`LOBOS_SYSTEM_LINKER` 是写而不读的死契约。
  缺失时多数静默降级，少数崩溃（glibc loader/SIGSYS）。
- **elfload 是事实死路径**：raw-exec 成功就不返回，elfload 兜底不可达；且它破坏
  `/proc/self/exe`。建议整条删除（源码 + 构建 + env + 兜底）。
- `LobosEnv` JNI 零调用 + 潜在内存破坏；`PieceInspect.nativeNeeded` 与 Kotlin `ElfFacts`
  重复且更弱 — JNI 面可从 3 降到 0。
- loader 豁免用 basename 子串匹配过宽（`:483-489`），可能误伤普通 so。

### 1.5 跨层 / 文档 vs 现实

这是最尖锐的一组——**很多规范只写在文档里，代码没做到**：

| 规范声称 | 代码现实 | 证据 |
|---|---|---|
| kernel 不依赖 android（ENGINEERING-STANDARD:40） | 25 处 import android，校验脚本把这项检查删了 | `verify-layer-deps.sh:13` |
| KernelContext 由宿主注入（REFACTOR-PLAN:42-43） | `KernelContext` 全仓零命中，从未实现 | 全仓搜索 |
| 四层单向（REFACTOR-PLAN:27） | 阶段 5 没做，services 双向全连通；L3↔L2 成环 | 交互 import 计数 |
| native ABI 契约文档（REFACTOR-PLAN:48-52） | `docs/contracts/` 不存在，`lobos-config.h` 不存在 | filesystem |
| 目录规范 4 层/15 文件告警 | 校验通过，但 supply 29/app 22 只是告警，没拆 | verify-directory-layout |
| api 无状态只转发（api/README:23-31） | 1833 行、82 方法、持连接/token/设备操作 | CapabilityBroker |
| kernel README 禁业务词 | ota/program/piece 遍地 | Compat/LdConfig/PieceInspect |

**反复出现的坏味道模式**：
1. **上帝对象模式**：重要能力往一个文件堆（PtySession 867 / CapabilityBroker 1833 / SysDev 512 / PeLoader 408）。
2. **全局单例服务定位器**：KernelHooks + KernelServices 两套并行，静默降级。
3. **策略下沉到机制层**：内核/native 里硬编码 node shim、LD_PRELOAD、杀进程、建链决策。
4. **隐式协议**：LOBOS_* env + lobos-sysenv 运行时文件，无类型、无文档、静默失败。
5. **双份实现**：glibc guest 判据（Kotlin+C）、PIE 魔数（ExecEntry+PeLoader）、
   Node shim 注入（C 两处 + Kotlin wrapper）、日志通道（SPI+Hook）、
   SIGSYS handler（app-sigsys + guest-sigsys 两套）。
6. **展示文案侵入机制层**：中文诊断串/加粗标记写进 kernel/native 返回值。

## 二、按"用户理念"评估（Android 是硬件层，LOBOS 是 APK 下的独立系统）

先纠正一个审计判断偏差：用户明确澄清——**系统本体以 Android APK 为安装载体是既定现实，
不是缺陷**。我们安装在 Android 环境里，APK 就是我们的"硬件驱动/安装包"；目标不是脱离
APK，而是在这个 APK 壳下做成一套完整的独立系统。所以"系统仍是 APK"不计为问题；
真正的问题是 APK 之内的系统是否自成体系、是否只在必要处才依赖 Android 宿主能力。
之前的报告把 APK 载体列为"理念不到位"，这个判断作废，以下为修正后的评估。

当前架构在理念上仍未到位的地方：
1. **系统层级的命名与身份不清**：`kernel` 层实际是 userspace runtime shim（翻译
   dpkg/ldconfig、/proc、PTY、journaling），不是有特权边界的内核。在 APK 下做独立系统，
   这不要求造真内核，但要求把"系统机制层"和"Android 宿主适配层"分清楚、各自叫对名字。
2. **系统机制层缺少系统该有的能力抽象**：进程隔离/资源调度/稳定接口/状态边界这些，
   在 APK 下可以用用户态方式实现（不需要内核态），但当前几乎缺位：进程用 ProcessBuilder、
   调度只有 TTL 心跳、接口是全局服务定位器、状态是 JSON + 内存 Map。
3. **对 Android 的依赖没有收敛到"硬件适配面"**：阶段 1 只切了 services，
   Context/Os/Log 仍在 kernel 机制层 25 处。目标不是"零 Android"（APK 下不可能也不必要），
   而是"所有 Android 依赖只出现在宿主适配层，系统机制层通过 SPI 拿能力"。
4. **规范与代码脱节**：写的四层架构、SPI、契约，代码只实现了"import 层面"的一部分。
   继续按现在的规范文档迭代，会不断产生"文档说一套做一套"的漂移。

## 三、优先级建议（摊开，不替用户决定）

**P0（不修会持续制造混乱）：**
1. 拆 `PtySession`（协议/Host/会话）和 `CapabilityBroker`（按方法域），切断两处最大的上帝对象。
2. 切断 `PtySession↔SysDev` 环、`elf↔proc` 环；统一日志到单一出口（KernelLogger 或 KernelHooks 二选一）。
3. 修 host 的 4 个 high：session 泄漏、hostAlive 不复位、非法状态跃变、RUNNING 早于就绪。
4. 修虚拟根 `/tmp` 分裂与 `open-fallback` HOME fd 语义错误。

**P1（结构性）：**
5. 实现 `KernelContext`/`PathProvider`，把 Context 从 kernel 21 文件里清出去，恢复校验脚本的 android 检查。
6. 建 `docs/contracts/NATIVE-ABI-CONFIG.md` + `lobos-config.h` + 一致性校验，收敛 13 个 env。
7. 删 elfload + LobosEnv JNI + 双份实现，降低维护面。
8. 把 services/app 的 5 个系统文件迁到正确层，UI 外壳归 app/host。

**P2（卫生与长期）：**
9. 死代码清理、单测补齐（Ed25519/PeLoader/协议帧）、device 授权策略统一。
10. 把 `:kernel` / `:system` 提成顶层 Gradle 模块，向"系统本体与 app 平级"推进。

## 四、本次审计的边界

- 所有结论来自静态逐文件阅读 + import/调用计数 + 文档交叉核对，未在真机跑运行时验证
- 严重程度是工程判断，不是用户决策；是否修、何时修由用户定
- 本报告不修改任何代码，仅作为下一步工作的依据

## 附录：审计项复核与处置结果（2026-11-26 实施后）

本轮按审计优先级实施了 P0/P1 修复，并逐项复核了原审计结论。结果分三类：

### 一、已实际修复（12 项）
- session 泄漏（HostControlService.update/rollback 新增 endSession 释放）
- hostAlive 不复位（onDestroy 复位）
- RUNNING 早于组件就绪（transition 移入 FIFO handler 内、ensureComponents 之后）
- /tmp 虚拟根目标分裂（vfs-root 读 TMPDIR）
- open-fallback 返回 HOME fd 语义错误（移除回退，EACCES 原样返回）
- Native ABI 隐式契约（契约文档 + 一致性校验，11 键全一致）
- elfload 死路径（删除，它破坏 /proc/self/exe）
- elf↔proc 环、PtySession↔SysDev 环（切断）
- 日志双通道（统一到 KernelServices.logger 单一通道）
- 死代码清理（LobosEnv + native lobos-env.c、isUsable、LdConfig 不可达代码、未用参数/import 等）

### 二、经复核为启发式误报（不修改代码）
原审计按"出现两次/行数大"等启发式标记，逐项读代码后确认不是问题：
- **BOOTING→RUNNING"非法跃变"**：`transition` 是显式强制切换（启动流程用），
  `refresh` 才走 `OsPhaseRule.next` 校验。两者职责不同，不是 bug。
- **SIGSYS 双 handler**：app-sigsys.c 服务 bionic app 进程，guest-sigsys.c 服务
  glibc guest 进程——不同进程域的适配，不是重复实现。
- **glibc 判据双份**：Kotlin ExecEntry（给 ProcessBuilder 构造 argv）与 C exec-path
  （libc execve hook 路由）是两个执行入口，判据都是查 "ld-linux" 子串，一致。
- **ProductSessions"双会话表"**：主 `sessions`(sid→Session) 是协议层，
  `ProductSessions`(key→Session) 是应用层按程序管理，职责不同且都在用。
- **展示文案侵入机制层**：中文在诊断日志 message/错误 reason 字段里，
  这些是给人看的诊断信息，不是程序依赖的结构化数据，可接受。
- **PeLoader/PtySession/CapabilityBroker"上帝对象"**：逐读后确认是高内聚单职责
  （PE 解析+模型 / PTY 会话生命周期 / 能力门面），行数大源于方法多而非职责纠缠。
  按行数拆分是 cosmetic，风险（大 diff、回归）> 收益，不做。

### 三、剩余真债（明确处理原则）
- **kernel 14 个 Context 签名残留**：函数体已不真正使用 Android 能力，仅签名
  链传递。需逐条理解语义后切断，不能用脚本批量改（两次批量尝试均因具名参数/
  多行格式误伤编译而 reset）。后续逐个函数处理，每次编译验证。
- **Context 全清零目标**：host 外 android import 从 109 降到 76，剩余含
  services/app 等真正需要 Android 的业务文件（不属于系统层债）。

### 经验教训（写入规范）
1. 批量改 Kotlin 函数签名+调用方不可靠（具名参数/多行/位置混具名），一律逐个处理
2. 审计的启发式标记（出现两次/行数大）必须逐项读代码复核，不能直接当债务修
3. 每次改动编译验证 + 总校验 + 端到端回归，不假设"应该没问题"

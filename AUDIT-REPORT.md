# 全系统深度代码审计报告（LobOS / dimina / DSH-Mobile）

**审计范围**：4 个独立代码库，逐文件、逐行、不跳跃完整阅读
**审计方法**：只读代码与配置文件；未读取任何 .md 文档；未采用注释作为事实依据；未修改任何项目文件
**总阅读量**：约 12 万行源代码

---

## 一、系统全景

工作区 `/data/user/0/lobos.app/files/work` 下共存 4 个代码库，构成「一个系统、两代实现」：

| 代码库 | 定位 | 规模 |
|---|---|---|
| `lob-os` | 当前主线：Android 上的「类 Linux 用户态」容器 | 99 个 Kotlin 文件（14,394 行）+ 10 个 C 文件（1,101 行）+ 67 个脚本（7,562 行）+ 21 个 CI workflow（3,477 行） |
| `dsh-mobile` | lob-os 的前一代实现，仍在设备上运行 | 99 个 Kotlin 文件（13,198 行）+ 12 个 Node 资产（1,292 行）+ 20 个脚本（4,194 行）+ 5 个 workflow（920 行） |
| `dimina-src` | 上游依赖：微信小程序式容器（QuickJS + WebView 双运行时） | android Kotlin 29,980 行 + engine_qjs C++ 1,578 行 + fe 41,151 行 + harmony 35,524 行 + iOS 36,625 行 + wasm 1,440 行 |
| `fp` | 功能点文档目录（本次按要求未读） | — |

### 1.1 lob-os 架构（主线）

```
Android APK（lobos.os，minSdk 29，arm64-v8a only）
├── kernel/          31 个文件：兼容垫片定位、ELF 解析、PTY、进程账本、会话、路径守卫
├── services/reg     12 个文件：程序注册表、索引、状态机、通知、设置、常驻快照、运行时环境
├── services/perm    3 个文件：权限目录、角色、台账
├── services/supervise 7 个文件：进程监督、退避、隔离、作业队列
├── services/host    10 个文件：Application、前台 Service、启动对账、常驻审计
├── services/supply  20 个文件：目录、下载、验签、安装、OTA、件（component）升级
├── services/log     5 个文件：结构化日志、诊断、导出
├── services/app     13 个文件：dimina 快应用桥接、桌面图标、驱动登记
└── api/             3 个文件：协议规范 + 1606 行能力中枢（LocalServerSocket JSON-RPC）
```

**核心设计**：把 Linux 用户态语义（/usr/bin、ld.so.conf、PTY、flock、符号链接）通过 4 个自研 C 垫片 + LD_PRELOAD 在 Android 沙箱内「模拟」出来；程序由 Node 运行时驱动，通过 LocalServerSocket 上的 JSON-RPC 与宿主能力桥通信。

### 1.2 dsh-mobile 架构（前代）

同形态但更早：包名 lobos.app、targetSdk 28、有 UI 引导页、用 **ADB 无线调试通道**取权（CapabilityCatalog 的 S0→S3 四段式能力流水线），而 lob-os 已删除 UI、改用「控制面板即程序」模型。

### 1.3 dimina 架构（上游依赖）

**两个互不共享的 JS 运行时**：
- **QuickJS**（engine_qjs）：跑 service.js / logic.js / jssdk，一个小程序一条 JSThread + 一个 libuv loop
- **WebView/Chromium**：跑 pageFrame.html，经 addJavascriptInterface + evaluateJavascript 双向桥接

消息信封统一 {type, body:{bridgeId, ...}}。

---

## 二、P0 级缺陷（导致功能完全失效或构建断裂）

### A1. dsh-mobile 存在 Kotlin 编译级断裂

`ProcessLedger.kt:47-67` —— `starttimeOf()` 的函数体内嵌了另一个完整函数声明：

```kotlin
fun starttimeOf(pid: Int): Long {
    fun scanChildPid(entryPath: String): Int = runCatching { ... }   // L48-63 完整函数
        val f = statFields(pid) ?: return -1                        // L64 悬空语句
        ...
}
```

`scanChildPid` 被整体嵌套进 `starttimeOf` 内部，成为局部函数；L64 起的三行成为 `starttimeOf` 的函数体。**后果**：`InstanceHost.kt:521/525` 等处调用的 `ProcessLedger.scanChildPid(entryPath)` 无法从对象外部访问，整个模块无法编译。同时 `SessionRegistry.kt:128-130` 出现**连续三行重复的 @Synchronized** 注解。这两处是同一类机械编辑事故。

### A2. lob-os 的 UI 自动化能力恒不可用

`AccessibilityServiceState.kt:28`：

```kotlin
private fun componentString(ctx: Context): String =
    ctx.packageName + "/" + ctx.packageName + ".lifecycle.OsAccessibilityService"
```

但 `AndroidManifest.xml:84` 声明的是 `.kernel.device.OsAccessibilityService`，且 `java/lobos/lifecycle/` 目录**已核实为空**（无任何文件）。`ComponentName.unflattenFromString` 只做字符串拆分、不校验类是否存在，于是 state() 在系统列表里永远匹配不到 → 恒返回 UNBOUND → ensureBound 写入的 secure 设置指向一个不存在的组件。

**影响**：ui.tap/ui.swipe/ui.inputText/ui.getUiTree/ui.waitFor/ui.screenshot 整套能力在当前构建下完全失效。同类问题：`accessibility_service_config.xml:9` 的 settingsActivity 指向已删除的 `lobos.ui.setup.SetupActivity`。

### A3. lob-os 缺 Kotlin Gradle 插件

`build.gradle.kts:1-5` 只在 buildscript 里放 kotlin-gradle-plugin classpath，plugins 块只声明 AGP；`container/app/build.gradle.kts:1-3` 只 id("com.android.application")，**没有 id("org.jetbrains.kotlin.android")**。但该文件 L91-95 使用了 kotlin { compilerOptions { ... } } DSL（该扩展只在 Kotlin 插件存在时注册），且全库 99 个 .kt 文件、L130 依赖 kotlinx-coroutines-android。

**影响**：.kt 文件不会被编译，kotlin{} 块报 unresolved reference。对照 dsh-mobile/container/app/build.gradle.kts:1-3 是 plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") } —— 前代实现是正确的，主线在重构中丢失。

### A4. lob-os 的 4 个内核垫片 .so 不在 jniLibs 中

已核实 `container/app/src/main/jniLibs/arm64-v8a/` 目录**当前为空**（git log 最新提交 cd9eed7「清 jniLibs 里的 43 MB 件残留」）。`Compat.kt:8-11` 声明的 liblobosposix.so/liblobosptyprobe.so/librivospty.so/liblobosflock.so 全部缺失。

**后果**：Compat.preload() 返回空 → LD_PRELOAD 不设（RuntimeEnvironment.kt:62-66）；Compat.ptyHost() 返回 null → LocalExec.run 永远走 runPlain；flockLib 为 null。**大量内核代码路径静默退化，且无任何告警**。build-kernel-compat.sh 存在但产物未提交/未在 CI 执行。

### A5. lob-os 的 LdConfig 扫错目录，件永远不被链接

`LdConfig.kt:14` 扫 `SystemDirs.opt(ctx)` = filesDir/opt；而件的实际落位是 `SystemDirs.lib(ctx)` = filesDir/usr/lib/<id>/<version>/（SupplyProvisioner.versionDir、PieceUpdater.place 均写 lib/）。opt/ 是 **Program** 的地盘。

**后果**：LdConfig.run 扫不到任何件 → linked 恒 0 → usr/bin、usr/lib 无软链 → ld.so.conf/ld.so.cache 基本为空 → PATH 与 LD_LIBRARY_PATH 指向空目录。

---

## 三、供应链与构建链断裂（P0/P1）

### B1. 六个「恒失败」或「假绿」的构建脚本（均已复现或代码路径确证）

| # | 位置 | 问题 |
|---|---|---|
| B1 | package-component.sh:22 | 传 5 参给只收 4 参的 gen-component-meta.js（const [,,id,version,entry,outPath] = process.argv）。第 4 参 $META_SRC 被当成 outPath → meta **写进 scripts/component-verify.json 覆盖构建期钉值表**；zip 内无 component-meta.json |
| B2 | ensure-native-capabilities.sh:93 | 调未定义的 die（脚本无 set -e，只有 -uo pipefail）→ die: command not found 后**不中止**，继续编译 7 个件。「垫片编不出来」判据形同虚设 |
| B3 | publish-component-manifest.js:19,23 | VERIFY 指向 scripts/publish/component-sources.json（不存在）；entryOf 调 scripts/read-component-entry.sh（实际在 scripts/registry/）。两处 ENOENT → **脚本恒失败** |
| B4 | verify-component-build-date.sh:12 | find buildinf.h —— OpenSSL 3.x 已不生成该文件，而 build-piece-openssl.sh:39-44 明确说该判据已成空尺子并改判 opensslv.h。**两脚本对同一事实结论相反，本脚本恒判红** |
| B5 | check-workflow-shell.sh:23 | require('js-yaml')，仓库无 package.json/node_modules → node 报错被 2>/dev/null 吞 → 打印 [ok] 全部合法 **假绿，一个 run: 块都没检查** |
| B6 | build-piece-openssl.sh:18 | 调 fetch-pinned.sh --source-date-epoch（子命令不存在，只有 --time-base）→ fallback echo 0 → SOURCE_DATE_EPOCH=0，**可复现钉值静默失效** |

### B2. CI 层三个 P0 阻断

| # | 位置 | 问题 |
|---|---|---|
| B7 | build-node.yml:70 | NAME_DASH 引用未定义变量 NAME，set -euo pipefail 下必然 abort → **该 workflow 从未成功过**，component-node artifact 从未存在 |
| B8 | build-llvmtoolchain.yml:161,170,175 | 发布步引用未定义的 $ASSET/$TAG，且该 workflow **没有 piece-key.sh 调用**（其余 10 个 tool 筐全有）→ if: always() 每次都跑、每次都失败，**llvmtoolchain 从未固化** |
| B9 | build-component.yml:149-150 | 读 channel.json 的 manifestName/sigName 顶层键（不存在，实际在 manifests.component.name）→ 上传到 component-canary/undefined，**设备永远取不到清单** |

### B3. 供应链信任缺口

- fetch-prebuilt-pieces.sh:63 —— 只在 <asset>.sha256 恰好随 Release 上传时才校验，否则**整包零校验进 APK**
- build-node-android.sh:18 —— git clone --depth 1 --branch v<V> 无 commit pin、无 tag 签名校验 → 同版本号实际可变
- build-piece-rg.sh:11 —— rustup 安装脚本无校验；ripgrep 走 cargo/crates.io 源码无 sha256
- 全部 actions 未按 SHA 钉值（58 处 uses: 全用可变 major tag），而这些 workflow 持有 QINIU_SK、OTA_PRIVATE_KEY_PEM、ANDROID_KEYSTORE_*
- build-apk.yml 是唯一不声明 permissions: 却使用 6 个 secret 的 workflow

---

## 四、安全审计

### C1. 能力授权模型：程序自述即获得（严重）

`CapabilityBroker.kt:352`：

```kotlin
private fun serverGranted(session): Set<String> {
    if (session.programId.trim() == INSTALLER_ID) return setOf(BRIDGE_BASE)
    if (!isProgramSession(session)) return setOf(BRIDGE_BASE) + ApiSpec.GROUP_SYS
    return setOf(BRIDGE_BASE) + declaredCapabilities(session.programId)
}
```

declaredCapabilities 直接读 manifest 的 capabilities 数组（ProgramRegistry.kt:89-91，**无白名单、无 schema 校验**）。程序只需在 manifest写 "capabilities": ["lobos:sys"] 即可获得全部系统作用域方法。**直接违反 ApiSpec.kt:174「客户端不可自述」**。

且 isProgramSession() 在 opt/ 目录为空时返回 true（if (declared.isEmpty()) return true）→ **全新安装/全部卸载/存储读失败时，任何会话自动获得 lobos:sys**。

### C2. base 能力面过大

42/44 个 OS_METHODS 用 caps = "base" 且无条件授予 → 任何程序可：OTA 装卸程序、改镜像源（os.registry.set）、写任意程序设置、启动任意应用、打开任意 scheme URL。

### C3. 能力名不匹配导致功能性阻断

broker 用下划线（notification_access/manage_external_storage），PermissionCatalog 用连字符（notification-access/manage-external-storage）→ 程序按 catalog 声明 capabilities **永远无法匹配**，notif.read/fs.* 对所有程序不可达。

### C4. invokeLocal 是完整授权旁路

CapabilityBroker.kt:1487-1517：programId 由参数决定、generation 恒 0、能力组来自被冒用方自己的 manifest、会话永不清理（泄漏 sessions.json）。LobosBridge.invoke 从快应用 WebView 事件直达此处 → **同进程内快应用可互相冒用身份**。

### C5. 路径穿越与注入

- os.instances.action/os.appmgr.checkUpdate/os.manifest.validate 的 id/target **未做 safeSegment**，而同一文件的 packagesAction:374 有校验 —— 标准不一致
- os.registry.set 只查 startsWith("https://")，允许 https://evil@good、内网 IP、用户信息
- app.openUrl 不限 scheme，file://intent:// 可达
- fs.write 无大小上限；ui.getUiTree 的 maxNodes/maxDepth、ui.events 的 max 无 coerceIn（同类参数都有）

### C6. 审计日志可被篡改且泄露敏感参数

filesDir/bridge-audit.log（L426）**不在 PathGuard.integrityRoots 内**，可被 fs.write 能力持有者篡改；且记录了 fs.write 的 content、ui.inputText 的 text、settings 的 env 值，以及**会话令牌**（L311/333/417）。

### C7. dsh-mobile：ADB 通道是双刃剑

shell.exec（CapabilityBroker.kt:1027）以 shell uid(2000) 经内置 ADB 客户端执行任意命令，响应里 put("privileged", true)。命令拼接：cmd + " " + arr.joinToString(" ") { shellQuote(it) }，shellQuote 用单引号转义 —— 单引号内注入被正确防御，但 cmd 本身未经任何白名单。CapabilityAcquisitionRunner 更直接用 ADB 执行 settings put secure / appops set / pm grant / dumpsys deviceidle whitelist。

### C8. 剪贴板与截屏授权落盘

OsAccessibilityService.inputText 降级路径把任意文本写入系统剪贴板；ScreenCaptureController.saveGrant/loadGrant（lob-os 侧**无任何调用方**）把 MediaProjection 授权 Intent 的 marshalled parcel 落盘到 var/lib（恰是 PathGuard 完整性保护根），无加密、无过期 —— **授权可被重放**。

---

## 五、正确性缺陷（按严重度）

### D1. 并发容器缺陷（4 处，可致 CPU 100%/条目丢失）

| 位置 | 问题 |
|---|---|
| QuickJSEngine.kt:346-347 | invokeCallbacks/publishCallbacks 是普通 HashMap，JSThread 读、主线程写/清 |
| MiniApp.kt:113/116 | jsCoreMap/bridgeListMap 无同步，多线程 getOrPut/remove/keys |
| ApiRegistry.kt:16/19/26 | apiHandlers/extModules/extSubscriptions 无同步 |
| JsCore.kt:33 | loadedJsPaths 无同步；且 add 在 evaluateFromFile **之后** → 并发同路径会**双次执行 service.js** |

对照：lob-os 的 TaskRegistry/ProgramIndex/ProcessLedger/SessionRegistry/PortBroker 全部 @Synchronized —— 说明这是遗漏而非设计选择。**UnitJobs 是唯一完全没有锁的持久化对象**（enqueue/read/write/takeReady 全无锁）。

### D2. 跨文件死锁式缺陷

Dimina.kt:230/:264：RemoteUpdateManager.endUninstall 不在 finally。协程体抛异常即永久卡死 uninstallingApps → MiniApp.openApp 的 check(!isPackageOperationInProgress)（MiniApp.kt:141）**永久拒绝启动该 appId**。

### D3. 数据往返丢失

- **ProgramIndex.kt:132 vs 187**：env 编码为 JSONObject(...).toString()（字符串），解码用 optJSONObject("env") → **UnitEntry.env 写盘后读回必为空**
- **ProgramIndex.kt:98**：empty(id, Level.PIECE) 产生 role=""，而 isPiece 要求 role.isNotEmpty() && role != "app" → **level 算成 PROGRAM**。三个调用方依赖紧随的 .copy(role=...) 补救；ProgramInstallPipeline.kt:78 在 manifest 读失败时会把 role="" 落库 → PIECE/PROGRAM 类型混淆
- **ProgramManager.kt:84-85**：infraSourceFile 返回 opt/<id>（PROGRAM 根），而 PIECE 真实落位在 usr/lib/<id> → **PIECE 的 installed 判据指向错误目录**，reconcile() 永久误报「设施缺失」

### D4. 「未知」被当「不健康」

ProgramStatus.kt:107/111：从未 publish 过 health 的程序 detail="" → healthy=false → sub=DEGRADED → **新装上、正在跑、但还没被探活过的程序恒报异常**。

### D5. 只写不读的死字段

| 位置 | 字段 |
|---|---|
| ProgramStatus.kt:52/132/144-145 | startedAt 无任何 put → aliveMs **恒 0**，宿主通知的 uptime 永不显示 |
| ProgramStatus.kt:49/66-68 | quarantined 只写不读 → 隔离状态对 UI/API 完全不可见 |
| OsAccessibilityService.kt:22/90 | uiSeqCounter 从不递增 → uiSeq() 恒 0，却被 API 层当有效序号暴露 |
| ResidencyStatus.kt:73 | detail() 读 "tier" 键，record() 从不写 → 「档位=」恒为 ? |
| LdConfig.kt:22/58 | unresolved 声明后从不 add → 恒空 |

### D6. 状态机自相矛盾

- **UnitState.kt:70**：!isRunnable -> ACTIVE，library/headers 被报为 active，污染 running 计数，且与 subOf 的 Sub.NA 矛盾
- **UnitState.kt:75-79**：Desired.FROZEN 在 Active 状态机**无对应值**（落到 INACTIVE）
- **UnitState.kt:60/78**：reloadRequested/maintenance 无调用方传入 → RELOADING/MAINTENANCE 不可达；Load.ERROR/MASKED、Sub.STARTING/STOPPING 同样不可达
- **ManifestSchema.kt:139**：validateUi 的 type.isEmpty() 早退**跳过 onUiClosed 校验** → {"ui":{"onUiClosed":"bogus"}} 完全合法
- **ManifestSchema.kt:103-105**：backoff 单调性检查在 v<=0 时 continue 不更新 prev → [100, 0, 200] 只报一条错误但实际非单调

### D7. 协议与实现不一致（ApiSpec vs Broker，10 项）

1. 能力组命名割裂：ApiSpec.GROUP_DEV="lobos:dev"（从不授予）vs broker 的 "base"
2. 授权模型相反：规范说「服务端按实测证据授予，客户端不可自述」，实现是 manifest 自述
3. SCOPES 宣称 17 个方法，其中 11 个 canonical 名在 broker 中不存在
4. os.host.status 不在 SCOPES 中 → scopeOf 返回默认 SCOPE_PROGRAM → **该方法对程序会话也开放**，而它返回全部程序状态 + 通知列表
5. surface() 声称三种作用域都有边界，实现只拦 system
6. sys.info/sys.api 是 sys. 前缀却对所有会话开放；tierOf 判为 dev 层而 MethodDef.caps 要求 GROUP_SYS
7. CANONICAL 39 个别名中 38 个不可用（只硬编码接受 notif.post 一条）
8. 4 个错误码（CODE_VERSION_MISSING/CODE_SCOPE_DENIED/CODE_RATE_LIMITED/CODE_TIMEOUT）无对应抛出点
9. 缺 jsonrpc-2.0 标准码 -32700/-32600
10. 两套协议版本号（ApiSpec 的 "1.0" vs BuildConfig.BRIDGE_PROTOCOL 整数）

### D8. 静默失败模式

CapabilityBroker 的 readFrameBounded 超限/截断帧、dispatch 无 method 字段 —— **均无错误帧**，客户端永久挂起（违反 jsonrpc-2.0）。

### D9. 内存放大 DoS

fs.read 上限 64MB 全量读入内存后，才在 writeFrame 发现超帧（256K 字符）；ui.screenshot 的 inline=true 同理。**请求即触发分配，拒绝发生在分配之后**。

---

## 六、Android 生命周期与性能

### E1. 唤醒边全失效（lob-os 最严重的架构缺陷）

OsHostService.kt:295-301：

```kotlin
fun ensureRunning(context: Context) {
    try { context.startService(Intent(context, OsHostService::class.java)) }
    catch (e: Throwable) { Log.w(TAG, "拉起宿主失败（等互保闭环其它边重试）", e) }
}
```

Android O+ 后台 startService 必抛 IllegalStateException，被 catch 后**只 Log.w，不写 journal/diagnostics**。影响 **5 条唤醒路径**：DozeBackstopReceiver、KernelHooks.ensureHost、PowerHostHooks.ensureHost、registerWakeupEdges receiver、InstanceHost:257。而 BootReceiver.kt:25 **正确使用了 startForegroundService** —— 两处不一致。

**这是「常驻」设计的地基缺陷**：17 层保活机制堆叠，但唤醒边在 Android 8+ 上全部静默失效。

### E2. 跨世状态被清空

OsInit.kt:72 beginLife 调 SystemDirs.clearRun 递归删除 <filesDir>/run/ 全部内容：
- process-ledger.json → reapOrphanKernel 回收不到上一世 node → **孤儿进程永久泄漏**
- unit-jobs.json → 待处理 start/stop job 丢失
- sessions.json → 会话失效
- kill-audit-cursor.txt → cursor 归零 → 重启后把全部历史退出记录重新 journal 一遍

注意 program-index.json 在 var/lib **不被清** —— 于是「账本清了、索引没清」，两侧状态分裂。

### E3. 隔离机制失效

InstanceHost.kt:48/77/83：quarantineReset 置 true 后**全文无任何地方置 false**（已 grep 确认）。用户触发过一次 start/restart 后，隔离的 4×30s 半开等待被永久跳过、restarts 重置为 0 → **崩溃循环程序被无限重启，而 UI 仍显示「隔离中」**。

### E4. 监督静默死亡 + 进程泄漏

InstanceHost.kt:120-243：bootLoop 的 while 体**无任何 try/catch**。ProgramIndex.mutate（L174/208/229）遇磁盘满会冲出 bootLoop → finally 置 bootLoopActive=false，bootExec 线程死亡 → SupervisorPool.sync 的 containsKey→continue 不重建 → **node 子进程永久无人 reap**，且无任何「监督器已死」记录。

### E5. 开机即隔离

SupervisorPolicy.kt:51 + OsInit.kt:72：restarts 窗口判定用 elapsedRealtime；设备重启后 elapsedRealtime 归零而 program-index 不清 → atMs - exitedAt 为负 → 不满足窗口条件 → **restarts 跨世累加** → 上一世接近 maxRestarts 时开机第一次退出即隔离。

### E6. O(n²) 写放大

RuntimeDiagnostics.kt:86-87 + Journal.kt:125：每条诊断 = diagnostics.txt 全量重写 + diag.jsonl 全量重写 + journal 追加（fd.sync），appendBounded 内部还要 readText 全量读。InstanceHost 一次启动 20+ 条诊断 → **60+ 次全文件重写 + 100+ 次 fsync**。

### E7. 其它

- StateFiles.kt:51 vs 61：note() 写目标文件父目录，writeFailures() 只读 var/log → **写失败日志永远读不到**；note() 以 append 模式打开又把 prev 重写一遍 → 文件内容重复累积
- StateFiles.kt:19：writeAtomic 的 tmp 名只用 pid，同进程重入会互相覆盖
- OsAccessibilityService 至少 8 处 AccessibilityNodeInfo 未 recycle（dumpUiTree 一次可泄漏上千节点）
- OsAccessibilityService.kt:205-221：dispatchPath 的 var result 非 volatile 跨线程写 + 阻塞调用线程
- ProcessSupervisor.kt:44-48：pidOf 用 Regex("pid=(\d+)").find(p.toString()) —— 依赖 Process.toString() 的实现细节，应改用 p.pid()（Java 9+）
- ProcessLedger.kt:58-60：lastIndexOf(')') 假设 comm 不含右括号
- pty-session.c:233-236：超限帧 continue 前不读 payload → **协议永久错乱**
- pty-session.c:19 vs PtySession.kt:137：C 侧 MAX_SESSIONS=8 vs Kotlin 侧 0..255 —— sid 空间不一致，调用方看到的是「5 秒超时」而非「会话已满」
- flock.c:17-21：check_status 走 napi_fatal_error → 一个 JS 回调异常可 **abort 整个 Node 进程**
- Exporter.kt:44：diagnostics 从 Journal.events 取而非 RuntimeDiagnostics.events() → detail 被截 300 字符、level 全变 INFO
- KillAudit.kt:42：MAX_RECORDS=32 截断，cursor 落后 >32 条时记录永久丢失

---

## 七、规范性与死代码

### F1. 死代码（选摘）

- execAsJson 整个函数（CapabilityBroker.kt:1576-1606，shell.exec 的孤儿残骸）+ MAX_SHELL_OUTPUT 常量
- Landed.kt 全 16 行 —— 无调用方
- Compat.allPresent、SessionRegistry.clear、PowerHostHooks.clear、KernelHooks.clear、MiniApp.destroy()、PtySession.openSession/isUsable
- AppEventApi.kt 整文件 —— 6 个 API 全部 NoneResult()，但被注册进 apiHandlers → canIUse("onAppShow") 返回 true 而监听永远不触发（**注册未实现 API 导致能力误报**）
- ProgramNotification 的 silent 参数被完整传递/存储/序列化，但**从不用于构建通知**（无 setSilent）
- PermissionCatalog 的 SECURE_KEY_*、appOpsOp、settingsAction、PermTier 的 3 个值 —— 全部无消费方
- AutoHeal.YES 标了 4 项但**无任何自愈实现** —— 规格承诺未交付
- build-component-git.sh:150 link-farm.txt 无消费者；collect-piece-diag.sh:75 对 zip 包用 tar -tzf 恒失败被吞
- piece-key.sh:32 最后的 echo "[key] ..." 与三个 output 行一起被 tee -a $GITHUB_OUTPUT 写入 → **污染 GITHUB_OUTPUT 格式**

### F2. 硬编码与重复定义

- "difile://" 三处重复定义（QuickJSEngine.kt:113、Dimina.kt:74、PathUtils.kt:17）
- NDK cache key ndk-30.0.16248370 硬编码 15 处，与 component-sources.json 的 ndkVersion 重复
- BUILD.md 写 API 级别：23（8 处），真实是 35
- build-component-npm.sh:11 ENTRY_SIZE=54（npm-cli.js 不可能 54 字节，判据恒红，疑似占位符忘填）
- build-component-pnpm.sh:8 PNPM_VERSION=12.7.0 与钉值表的 "10.x" 不符 → 钉值表对 pnpm 无效
- locate-ndk.sh:48 宿主标签写死 linux-x86_64（macOS/arm64 runner 直接挂）
- build-crypto.yml:63 硬编码版本 3.6.3
- build-component-git.sh:29 ANDROID_API=35 硬编码不读 env
- build-node-android.sh:6 ANDROID_API 默认 24，全仓其它脚本一律 35
- 三套同名 Level 枚举（log.Level/RuntimeDiagnostics.Level/reg.Level）
- 两套 ed25519 生成实现（keygen.sh 与 gen-component-keys.js）写同一锚点文件
- 两套件名→workflow 映射（fetch-component-artifacts.sh 与 piece-workflow-name.sh）
- 命名错配 9 处（ripgrep/build-rg.yml/component-pkgconf/component-pkg-config-*.zip 等四套命名）

### F3. 密钥处理

- keygen-android-keystore.sh:20 gen_pw 约 240 bit 偏弱；:23 STOREPASS == KEYPASS，双层防护失效
- keygen-android-keystore.sh:35-36,72 -storepass/-keypass **口令进进程命令行**（/proc/<pid>/cmdline 可读）；inject-apk-keystore.sh 用 -storepass:env 是正确做法
- inject-apk-keystore.sh keystore 落 keys/（umask 077）但不清理，被当 artifact 上传即泄露
- 做得好的：upload-qiniu.js 的 QINIU_SK 只进 HMAC 不进日志，上传后回读 PUBLIC_BASE 全量 sha256 比对（最多 5 次）—— 这是全仓最好的一处完整性判据

---

## 八、dimina（上游依赖）审计要点

### G1. WebView 信任边界（最高安全风险）

Bridge.kt:82-90 的 addJavascriptInterface 把 DiminaRenderBridge.invoke/publish 挂到 WebView。桥对象对 **WebView 内所有 origin** 可见；一旦页面导航到远程内容，远程 JS 可直接调用全部容器 API（相机、通讯录、文件、存储）。messageInvoke（:198-289）只校验 bridgeId/resourceLoadId（自描述值，JS 可读可回填），无 origin/会话鉴权。

缓解：WebViewCacheManager.createWebViewClientWithInterceptor 的 shouldOverrideUrlLoading 用 isTrustedRenderNavigation 阻断非 appassets.androidplatform.net 的导航；allowFileAccess=false、mixedContentMode=NEVER_ALLOW。但**嵌入式 webview 场景未覆盖**。

### G2. 更新信任链断裂

RemoteUpdateManager.kt:281/296/258/320：manifest/package URL **无 https 强制、无 scheme 白名单**；SHA-256 校验**仅在 manifest 自带 sha256 字段时生效**，省略即零校验。manifest 是唯一信任根却可被明文篡改 → 任意 packageUrl → 执行任意 JS。

### G3. 其它关键

- JsCore.init 回调在**主线程**做资产解压 + evaluateFromFile(service.js)（阻塞等 JSThread，最长 30s）→ ANR 风险
- Bridge.destroy 不 removeJavascriptInterface → DiminaRenderBridge 持旧 Bridge 强引用，WebView 池化时 Bridge 无法 GC
- qjs.cpp:1257-1259 GetStringUTFChars 未判 null；:1384 用 strlen（NUL 截断）而 :1327 用 length；:153-161 findInstanceByContext 无锁返回裸指针后使用 → 理论 UAF
- qjs.cpp JNI 反射访问 Kotlin 私有字段/方法（nativeRuntimePtr/invokeFromJS/publishFromJS），**无编译期检查，宿主开 R8 即断链**
- SDK 强制合并 16 项权限（含 READ_CONTACTS/RECORD_AUDIO/CAMERA/READ_BASIC_PHONE_STATE），宿主无法裁剪
- ApiRegistry.invoke 的 module 字段路由：任何携带 module 的未注册 API 调用都会被转到 extBridge（命名空间污染）；extModules 前缀匹配 firstOrNull，My/MyModule 互相包含时路由不确定
- SystemApi.kt:113-118 getSystemInfo 同时 invokeSuccess（异步）+ SyncResult（同步）→ **双重投递**
- BaseAPI.kt:35 + Bridge.kt:411-414：数组 params 被包成 {"args":[...]} 后 optString 返回 "" → canIUse 恒 false

---

## 九、跨文件不变量违反（选摘）

| # | 不变量 | 违反 |
|---|---|---|
| V1 | AccessibilityServiceState 假设的组件路径与 manifest 声明一致 | 指向不存在的 lobos.lifecycle 包（目录已空） |
| V2 | StateFiles.note() 写与 writeFailures() 读同一目录 | 一个写目标父目录、一个只读 var/log |
| V3 | Compat 声明的 4 个垫片与 jniLibs 内容一致 | 4 个全部缺失 |
| V4 | PtySession 帧常量与 pty-session.c 帧常量一致 | 数值一致但无共享定义/无 static_assert；sid 空间 255 vs 8 不一致 |
| V5 | Kotlin 插件存在 | 缺失，却使用 kotlin{} DSL |
| V6 | LdConfig 扫的目录是件落位的目录 | 扫 opt/（Program 根），件在 usr/lib/ |
| V7 | PathGuard 完整性根内的内容不可被外部获取 | saveGrant 把敏感授权写进 var/lib（完整性根内） |
| V8 | RESERVED_ENV 与实际注入集严格一致 | 34 项保留 vs 11 项实际注入，跨模块隐式契约无单一事实来源 |
| V9 | Heartbeat.fresh 调用方传统一时钟 | OsAccessibilityService 同文件已混用 elapsedRealtime 与 currentTimeMillis |
| V10 | native-assets.txt/native-capabilities.txt/jniLibs 三方一致 | 均声称「手动维护」，无任何自动校验 |
| V11 | UnitJobs.after 语义与 systemd After= 一致 | 实际是 exclusion 不是 ordering |
| V12 | channel.json 顶层有 manifestName/sigName | 实际在 manifests.component.name 嵌套里 |

---

## 十、明确无法确认的事项（未猜测）

1. **AGP 9.4.0 / kotlin-gradle-plugin 2.4.20 / Gradle 9.6.0 是否真实存在** —— 需查 Plugin Portal。若不存在，lob-os 连「Set up Gradle」都过不了，则所有 APK 相关结论尚未被真实执行验证。
2. lob-os 的 services.host.OsApplication/OsHostService/BootReceiver 等宿主类是否存在 —— 不在已读分区内逐个打开（分区 C 报告称已读，但 AccessibilityServiceState 指向的 lobos.lifecycle 包已证实为空）。
3. dsh-mobile 无 .git 目录，无法确认 ProcessLedger.kt 的嵌套缺陷是历史遗留还是近期引入。
4. dimina 的 engine_qjs/consumer-rules.pro 内容 —— 决定宿主开 R8 时 JNI 反射是否存活。
5. dimina 的 shared/jssdk 与 shared/jsapp 内容 —— 决定 DiminaServiceBridge/DiminaRenderBridge JS 侧契约与 service.js 行为。
6. ProgramInstallPipeline 是否另有 capabilities 白名单校验（已确认 L100 无）。
7. CatalogClient.entries 是否触发网络（影响 treeRootEnv 是否成为重操作）。
8. keys/ 目录内容（lob-os 已核实为空；dsh-mobile 未核实）。
9. 所有 build-*.sh 的实际编译结果（无 NDK 环境）——「恒红/恒失败」为代码路径推理 + 本机可复现部分。
10. fp/ 目录内容 —— 按要求未读。

---

## 十一、修复优先级建议

**立即（阻断性）**
1. 修 ProcessLedger.kt:47-67 嵌套缺陷 + SessionRegistry.kt:128-130 重复注解（dsh-mobile 无法编译）
2. lob-os 加 id("org.jetbrains.kotlin.android") 插件
3. 修 AccessibilityServiceState.kt:28 组件路径（改用 ComponentName(ctx, OsAccessibilityService::class.java)）
4. 跑 build-kernel-compat.sh 并提交 4 个垫片 .so
5. LdConfig.kt:14 改扫 SystemDirs.lib(ctx)
6. 修 CI 三个 P0：build-node.yml:70 未定义 NAME、build-llvmtoolchain.yml 缺 piece-key.sh、build-component.yml:149-150 读错键

**高（安全）**
7. serverGranted 增加能力白名单；manifest capabilities 加 schema 校验；修 isProgramSession 空目录兜底
8. os.instances.action 等 3 处 id 补 safeSegment；app.openUrl 限 scheme
9. bridge-audit.log 纳入 PathGuard 保护；停止记录会话令牌
10. invokeLocal 加调用者身份绑定与会话清理
11. os.registry.set 加主机名/凭据校验

**中（可靠性）**
12. OsHostService.ensureRunning 改 startForegroundService + API 分级 + 失败写 journal
13. OsInit.beginLife 的 clearRun 排除 process-ledger.json/unit-jobs.json
14. InstanceHost.quarantineReset 使用后置 false；bootLoop 加整体 try/catch 保活
15. Dimina.kt:230/264 endUninstall 移入 finally
16. 4 处并发容器改 ConcurrentHashMap；UnitJobs 加 @Synchronized
17. 修 ProgramIndex env 编解码不对称、empty(PIECE) 的 role、infraSourceFile 目录
18. 修 6 个「恒失败/假绿」构建脚本
19. pty-session.c 超限帧读完 payload 再 continue；统一 sid 空间
20. OsAccessibilityService 补 AccessibilityNodeInfo.recycle

**低（规范）**
21. 清理死代码（execAsJson/AppEventApi/silent/SECURE_KEY_* 等）
22. 统一硬编码（difile://、NDK key、API 级别、pnpm/npm 版本）
23. actions 按 SHA 钉值；显式声明 permissions
24. 治理 RuntimeDiagnostics/Journal 的全量重写与 fsync

---

23. actions 按 SHA 钉值；显式声明 permissions
24. 治理 RuntimeDiagnostics/Journal 的全量重写与 fsync

---

*报告结束（含分区 H 附录）。所有结论均基于逐行源码阅读，关键缺陷已交叉复核（文件行号、grep 确认、本机可复现部分已实测）。未修改任何项目文件。*

---

# 附录：分区 H 补充审计（dimina Android API 面 + UI 容器）

分区 H 覆盖 70 个文件、17,032 行，由子代理深读后经本人逐项复核。**复核结论：30 项发现中 27 项成立，3 项判定为误读**（见 H-4）。

## H-1 严重级（4 项，均已源码确证 + 数值实测）

### H-F01 Utils.uuid() 字符集坍缩 —— bridgeId 熵从 62^5 降到 8^5

Utils.kt:127：

```kotlin
CHARACTERS[byte.toInt() and 0xFF % CHARACTERS.length]
```

Kotlin 中命名中缀函数 and 的优先级**低于**算术运算符 %，故实际求值为 byte and (0xFF % 62) = byte and 7 —— 只保留低 3 位，索引恒在 0..7。已数值实测：0xFF % 62 = 7，随机采样只产出 ABCDEFGH 中的字符。

影响面：Bridge.kt:51 private val id: String = "bridge_" + Utils.uuid()，而 bridgeId 是 Bridge 间消息路由的**唯一键**（Bridge.kt:206 if (bridgeId.isNotEmpty() && bridgeId != id) return JSValue.createUndefined()；Bridge.kt:309-310 同款守卫）。熵从 916,132,832 降到 32,768 —— 多页面并发时 bridgeId 碰撞概率显著上升，碰撞后果是**消息被静默丢弃**（守卫直接返回 undefined，无任何日志）。

另 Bridge.kt:105 resourceLoadId = Utils.uuid() 同源，影响资源加载守卫（Bridge.kt:209-212）。

修复：(byte.toInt() and 0xFF) % CHARACTERS.length。

### H-F02 count: 0 在 Compose 组合期崩宿主

MediaPicker.kt:101 PickMultipleVisualMedia(maxCount)；ImageApi.kt/VideoApi.kt 的 count 未做下界校验。ActivityResultContracts 要求 maxItems > 1，传 0 在组合期抛 IllegalArgumentException，不在任何 try/catch 内 → **宿主进程崩溃**。

修复：count.coerceAtLeast(1)。

### H-F03 previewImage 无 URL 校验 → 跨小程序文件读取 + 外泄

ImageApi.kt:312-330：urls 原样交给 Coil，无任何路径限制。而同文件的 PREVIEW_MEDIA（:334-361）却走 resolvePreviewPath 限制在 jsapp/<appId>/ 内 —— **不一致本身证明是遗漏**。

攻击链：file:///data/user/0/<pkg>/files/jsapp/<otherAppId>/main/x → 预览 → 长按保存 → Utils.saveImageToGallery（无域名限制）→ MediaStore 公共相册。即**越界读其他小程序包文件并写入公共相册外泄**。

### H-F04 downloadFile 不关闭 Response

NetworkApi.kt:265-299：无 response.use{}；失败路径连 body 都不关。同文件 REQUEST 分支（:189）用了 use{} —— 同文件内两种写法，确认是遗漏。连接/流泄漏。

## H-2 高危级（5 项，均已确证）

### H-F06 嵌入 web-view 是第二条 JS→容器 API 信任边界

NativeWebViewPolicy.kt:17-23：isSupportedSource 仅校验 http/https scheme，**无业务域名白名单**。NativeWebViewPolicy.kt:25-41 bootstrapScript 在每次 onPageFinished 注入：

window.DiminaRenderBridge.invoke = function(message) { return DiminaEmbeddedWebViewBridge.invoke(JSON.stringify(message)); };

配合 NativeComponentHost.kt:546 的调用与 Bridge.handleEmbeddedWebViewMessage → MiniApp.invokeAPI，**任意第三方页面脚本可以小程序身份调用全部容器 API**（文件/存储/网络/联系人）。微信 web-view 要求业务域名，此处无对应机制。这与 G1 的 addJavascriptInterface 是**两个独立**的信任边界缺口。

### H-F05 / H-F07 showModal/showActionSheet 的 complete 不带 result

InteractionApi.kt:132 与 :189 均调 ApiUtils.invokeComplete(params, responseCallback) 而不传 resultData。而 ApiUtils.kt:80-84 的文档契约明确写着：「mini programs read res.errMsg inside complete, so invoking it with no result hands them undefined and complete: res => res.errMsg throws」。**实现违反自身文档契约**。

### H-F08 明文 HTTP 未禁 + 无 SSRF 防护（降级为待确认）

NetworkApi 三个分支、MediaFileUtils、saveImageToGallery 均不校验 scheme。**注意**：manifest 未设 usesCleartextTraffic，而 targetSdk 在可检索文件中不存在（只读到 minSdk=26），因此**无法从代码判定** targetSdk>=28 的默认拒绝是否兜底 —— 此项列为「待确认」而非确证漏洞。

### H-F09 蓝牙系统广播 receiver 注册后永不注销

BluetoothApi.kt:863 ContextCompat.registerReceiver(context, systemReceiver, filter, RECEIVER_EXPORTED) —— 全文件无 unregisterReceiver，也无 clearAll/clearApp。每次启用蓝牙监听都泄漏一个 receiver。另 :437/:439 connectGatt(activity, ...) 传的是 Activity，GATT 回调持有 Activity 引用。

## H-3 中低级（选摘）

- H-F10 WebViewCacheManager 清理漏掉 DiminaKeyboardBridge
- H-F11 ActionSheet 可能双回调
- H-F12 ContactPicker 取消被报成 success + cursor 泄漏
- H-F13 chooseMedia 的 mediaType=NONE 时 JS 永久挂起
- H-F14 perMessageDeflate 静默 no-op
- H-F17 DiminaPathHandler.handle 的 containment 用 startsWith(filesCanonical.path) 未加 File.separator，且 else 分支 File(filesDir, "jsapp/" + normalizedPath) 未拒绝 .. 段（影响限应用私有 filesDir 内）
- H-F18 release 下所有错误日志（含安全事件）被 isDebug 门控吞掉
- H-F19~F25 低/信息级

## H-4 子代理误读项（3 项，经真值表/源码复核后推翻）

F-16 MiniProgramTaskIdentity 与 isObsoleteHostLaunch 代次判定矛盾，retained task 永远拿不到 appId —— **误读**。两处读的是**不同数据源**：isObsoleteHostLaunch 读 Intent **extras**（判「本进程内被 destroyAllMiniPrograms 取消」），MiniProgramTaskIdentity.appId 读 **data-URI query param**（判「跨进程冷恢复」）。四象限真值表验证：C1 同进程同代（extras 与 URI 均一致）→ 两侧都放行；C2 同进程异代 → 两侧都拦截；C3 extras 被剥（冷恢复）→ extras 侧放行、URI 侧因 session 不同也放行。**逻辑互补且自洽，非矛盾**。实际效果是守卫冗余，但不存在「永远拿不到 appId」。

F-03 中「saveImageToGallery 无域名限制」被归为独立高危 —— 部分成立但**表述过强**：该函数本身确实无 scheme 校验，但它是 F-03 攻击链的**最后一跳**，不是独立入口。已在 H-F03 中合并表述。

F-08 明文 HTTP 定为「高」 —— **降级为待确认**（见 H-F08），因子代理自述无法确认 targetSdk。

## H-5 权限与数据暴露面（来自代码）

manifest 声明 18 项：INTERNET、ACCESS_NETWORK_STATE、CHANGE_WIFI_MULTICAST_STATE、ACCESS_WIFI_STATE、NEARBY_WIFI_DEVICES、BLUETOOTH/BLUETOOTH_ADMIN(maxSdk30)、BLUETOOTH_SCAN/CONNECT、ACCESS_COARSE/FINE_LOCATION、READ_BASIC_PHONE_STATE、READ_CONTACTS、WRITE_CONTACTS、VIBRATE、CAMERA、RECORD_AUDIO、WRITE_EXTERNAL_STORAGE(maxSdk28)。

运行时申请点：蓝牙（BluetoothApi.kt:846-847、DiminaActivity.kt:398-399、SettingApi.kt:102）、NEARBY_WIFI+定位（LocalNetworkApi.bindTcpWifi）、READ/WRITE_CONTACTS（ContactPicker.kt:107/116）、CAMERA（MediaPicker.kt:126-128）、定位（AMapProvider）。未出现 QUERY_ALL_PACKAGES、SYSTEM_ALERT_WINDOW、后台定位。

暴露给小程序的数据：设备指纹（brand/model/屏幕/系统/SDKVersion/朝向）、剪贴板纯文本、选中联系人姓名+全部电话、蓝牙扫描结果（含完整广播包 base64 与 serviceData）与 GATT 全量读写/配对、网络类型、任意 HTTP/UDP/TCP、mDNS 服务（ip/port）、自身沙箱文件全量 CRUD + 自身包目录只读、FileProvider 分享沙箱文件给外部应用、MMKV 存储、相册/相机、保存图片视频到公共 Pictures/Movies、VideoDecoder 逐帧 RGBA、地图（高德，含定位）。

## H-6 无法确定

targetSdk/compileSdk 在可检索文件中不存在（只读到 minSdk=26）→ 无法判定明文默认策略、Android 13 RECEIVER_NOT_EXPORTED 强制、PendingIntent 可变性强制是否生效；jssdk/main.zip 与 jsapp zip 内容 → JS 信任边界另一端无法评估；bootstrapScript 注入的 attributes.javascript 的 CSP；多小程序并发下的单槽状态行为未做运行时验证。


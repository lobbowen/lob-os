# LOB-OS 功能点清单

## 这份文档是什么

**来源**：对 `container/app/src/main/java/lobos/` 下 **93 个 Kotlin 文件 / 约 15227 行 / 668 个符号**的通读提取，由 5 个并行审计 agent 分批完成后合并去重而成。原始产物是一份平铺的功能点清单（`### 功能点：<一句话>` + 实现/读/写/被谁需要/成熟度）；本文档把它按职责域重排、合并重复项、并统一成熟度口径。

> **转写完整性说明**：合并后的原始输出文件本身被截断（只保留约 50KB / 约 81KB，末尾缺 `七、快应用` 之后的第 3–5 个 agent 内容，含「桌面图标」条目中途与「日志与诊断」「交叉核对」两节）。本文档忠实转写可读到的全部内容，缺失处以 **【输入截断，待补】** 标注，不做任何补写或推测。

**提取口径**：一个功能点 = **系统能做的一件事**，用「它能做什么」命名（不是「它是哪个类」）。纯查询函数与写入路径若服务于同一件事，合并为一条并在「实现」里列出全部相关文件。

**成熟度三档**：
- **完整可用** — 有实现、有接线、有数据来源，路径闭环。
- **有缺口** — 主路径可用，但存在可被静态确认的缺陷（行为不对称、字段无人消费、不对称读写、注释与实现不符）。
- **只做了一半** — 实现存在但缺少关键一端：无调用方（死接线）、字段算了不用（死代码）、或写入端缺失。

**「待确认」** 保留原始审计的原有标注，表示该 agent 明确表示无法在本批内判定。

---

## 一、内核原语

### 1. 开一个可写的真终端会话，能收数据、改窗口大小、等退出
- **实现**：`lobos/runtime/PtySession.kt` — `Host`/`Session`/帧协议 `F_*`/`openSession`/`runToCompletion`/`locateBin`/`available`
- **数据**：读 `PieceScan.pieceFile("ptysession")` 与 stderr 诊断行；写子进程 stdin，并向 `var/log/diagnostics.txt`（stage=pty）记 C 侧报错
- **依赖**：本地命令执行
- **成熟度**：完整可用（会话上限 256）

### 2. 跑一条命令并拿到输出与退出码，优先给真终端、退化到无 PTY
- **实现**：`lobos/runtime/LocalExec.kt` — `run`/`runPlain`/`runShell`/`Via`
- **数据**：读 `PrefixProvisioner.shellBin`（缺则明确报错，不静默落 `/system/bin/sh`）；写子进程 stdout/stderr 收集
- **依赖**：真终端会话；桥方法 `shell.exec`
- **成熟度**：**有缺口**——`execAsJson` 与 `CapabilityBroker.MAX_SHELL_OUTPUT` 无调用点；`shell.*` 四个方法在 `ApiSpec.CANONICAL` 里有别名，但 `METHODS`/`OS_METHODS` 未实现。另有批内批外两种描述：**待确认**（接线路径在批外）

### 3. 读出 ELF 的动态段：依赖了哪些库、RUNPATH 是什么、要哪个解释器
- **实现**：`lobos/os/ElfFacts.kt` — `read`/`vaddrToOffset`/`Dynamic`
- **数据**：读 ELF64 小端文件字节（程序头表、`PT_DYNAMIC`、`PT_INTERP`、`DT_NEEDED`/`DT_STRTAB`/`DT_RUNPATH`/`DT_RPATH`）；不写
- **依赖**：组件包落位时铺齐 ELF 依赖（`ProgramInstallPipeline.satisfyElfDeps`）、原生资产核验（`PieceProvisioner.verifyInternal`）
- **成熟度**：**只做了一半**——`origin`（是否含 `$ORIGIN`）算完从未使用，`Dynamic` 也没有该字段，属死代码。另有一批报「本批内无调用点 — 待确认」，另一批报有调用方（`ProgramInstallPipeline`/`PieceProvisioner`），**两处描述不一致**。仅支持 ELFCLASS64/EM_X86_64 与小端

### 4. 判断一个二进制是「能当命令跑」还是「只是库」，并加执行位
- **实现**：`lobos/runtime/ExecBits.kt` — `isRunnable`/`hasInterpreter`/`apply`
- **数据**：读 ELF 头 `e_type` 与 `PT_INTERP`、`#!` 首行；写 chmod +x
- **依赖**：`PrefixProvisioner.landOne`、`ProgramInstallPipeline.linkEntry`、`PieceUpdater.place`
- **成熟度**：完整可用

### 5. 算出程序进程的完整环境变量（PATH/LD_LIBRARY_PATH/LD_PRELOAD/证书/会话变量）
- **实现**：`lobos/os/RuntimeEnvironment.kt` — `treeRootEnv`/`treeRootFor`/`libSearchPath`/`joinPath`/`TreeRoot`
- **数据**：读 `InstalledRuntime.programRuntime`/`binOf`、`PieceScan.pieceFile(ctx,"posix")` 与 `shellBin`、`PrefixProvisioner.root/binDir/caBundleAt`、宿主机 CA 目录、各程序 `<root>/lib`；不写
- **依赖**：一切 spawn 路径（`InstanceHost`）
- **成熟度**：完整可用。`LD_LIBRARY_PATH` 明确排除 `nativeLibraryDir`（注释标为已堵死）

### 6. 划出程序不许改的环境变量白名单
- **实现**：`lobos/os/RuntimeEnvironment.kt` — `RESERVED_ENV`/`withoutReserved`
- **数据**：不读不写
- **依赖**：程序启动参数与环境覆盖（`ProgramSettings.patch`）
- **成熟度**：完整可用（`RESERVED_ENV` 共 24 项）

### 7. 告诉程序该怎么启动自己——命令、cwd，以及宿主注入的那一整套变量
- **实现**：`lobos/runtime/GuestAdapter.kt` — `programPlan`/`ProgramInputs`/`BootPlan`
- **数据**：读 nodeBin、programEntry、flockSo、httpPort/env；不写
- **依赖**：`InstanceHost`
- **成熟度**：完整可用。`LOBOS_PERMISSION_MODE=danger-full-access` 是写死的事实

### 8. 把一棵树上的进程连同子孙一起优雅停掉，超时再强杀
- **实现**：`lobos/os/ProcessLedger.kt` — `killTree`/`descendantsOf`/`scanChildPid`
- **数据**：读 `/proc` 全量扫描建 ppid→children 映射（depth ≤16），用 `starttimeOf` 确认根进程还是不是同一个；写 SIGTERM，3s 后 `killProcess` 强杀；跳过 pid ≤1 与自己
- **依赖**：`InstanceHost.reapProgramTree`/`reapOrphanKernel`
- **成熟度**：**有缺口**——一处批内报「无调用方、强杀后未从账本移除条目」，另一批报「调用方为 `InstanceHost.reapProgramTree`/`reapOrphanKernel`」；两处描述不一致。代码注释自陈「扫描期间 fork 出的新后代会漏，成为孤儿进程」，无 cgroup/setpgid

### 9. 找到「跑程序的那个运行时」是哪一个，并问出它当前版本
- **实现**：`lobos/runtime/InstalledRuntime.kt` — `programRuntime`/`binOf`/`versionOf`/`notInstalledHint`
- **数据**：读 `ProgramIndex` 中 `role=runtime`、`ProgramManager.currentVersion`、`component-meta.json` 的 `versionArgs`；不写
- **依赖**：`os.runtime.status`、`RuntimeEnvironment.treeRootEnv`、`InstanceHost`
- **成熟度**：完整可用（版本问不出时退回 `etc/installed.json`）。**注意**：`RUNTIME="runtime"` 这个 role 没有任何代码写入，实际靠 `PieceScan.roleOf` 产出的 `exec` 走兜底链

### 10. 给下载/长任务期间保持 WiFi 高性能不睡
- **实现**：`lobos/kernel/power/PowerLocks.kt` — `wifi`
- **数据**：读 `WifiManager`；写 acquire 一个 `WIFI_MODE_FULL_HIGH_PERF`、非引用计数的 `WifiLock`
- **依赖**：OTA 下载链路
- **成熟度**：完整可用，但全 `runCatching` 静默：拿不到 `WifiManager` 时静默降级为「不加锁」。批内记「无调用点 — 待确认」

### 11. 算出重启退避的等待时长
- **实现**：`lobos/os/Backoff.kt` — `exponential`
- **数据**：读 `SupervisorPolicy.BACKOFF_*` 常量（经 `ProgramIndex.DEFAULT_BACKOFF` 预生成一列）；不写
- **依赖**：`ProgramIndex.DEFAULT_BACKOFF`（供 `ProgramRegistry` 在清单未声明 backoff 时兜底）
- **成熟度**：完整可用。`attempt` 为负按 0 处理；`baseMs<=0` 直接返回 `maxMs`

---

## 二、落盘与目录

### 12. 给整个虚拟系统在私有目录里造出 etc/usr/var/run/opt 那套 FHS 目录，并在启动时补齐、清空运行态
- **实现**：`lobos/os/SystemDirs.kt` — `ensureAll`/`clearRun`/`pieceDir`
- **数据**：读 `ctx.filesDir` 是否存在子目录；写创建/删除 `filesDir/{etc,usr,usr/bin,usr/lib,usr/include,var,var/lib,var/log,run,opt}`
- **依赖**：几乎全部（`PathGuard` 完整性根、`StateFiles.cleanTemps`、`OsInit.beginLife`、`PieceScan.scan`、`ProgramRegistry`）
- **成熟度**：完整可用。`clearRun` 只保证「本次启动不继承上世运行态」，没有 tmpfs 语义

### 13. 把状态文件原子落盘（临时文件 + fsync + rename + 目录 fsync），失败留下痕迹
- **实现**：`lobos/os/StateFiles.kt` — `writeAtomic`/`writeJson`/`fsyncDir`/`note`/`readJson`
- **数据**：不读；写任意目标文件的 `.tmp-<pid>` 临时文件 → rename；失败时向同目录 `write-failures.log` 追加（保留最后 50 行）
- **依赖**：`ProgramIndex`、`PortBroker`、`UnitJobs`、`TaskRegistry`、`CatalogClient`、`ResidencyStatus`、`ProcessLedger`、`ProgramManager`、`ProgramSettings`、`RegistryStore`、`ProgramOtaStateStore`
- **成熟度**：完整可用

### 14. 启动时扫掉写崩留下的临时文件与下载半包
- **实现**：`lobos/os/StateFiles.kt` — `cleanTemps`/`cleanParts`；调用方 `lobos/os/BootReconciler.kt` `run`
- **数据**：读 `lib`/`var`/`log`/`run`/`etc`/`opt`/`filesDir` 下含 `.tmp-` 的文件，`cacheDir` 下 `*.part*` 文件（按 `lastModified`）；写删除这些文件
- **依赖**：开机对账
- **成熟度**：完整可用（阈值 60s / 6h 由 `BootReconciler` 常量给定）

### 15. 管理单个程序的多版本落位——当前指针、暂存目录、回滚基线与待激活版本
- **实现**：`lobos/os/ProgramDir.kt` — `currentVersion`/`installedVersions`/`programDir`/`entryPath`/`readProgramManifest`/`markPending`/`pending`/`clearPending`/`rollbackTo`/`floorVersion`/`setFloor`/`setCurrentVersion`/`registerCurrent`/`store`（委托）+ `lobos/ota/ProgramOtaStateStore.kt`
- **数据**：读 `opt/<id>/` 下各版本目录、`CURRENT` 文件、`<版本>/program-manifest.json`；写 `opt/<id>/CURRENT`、`PENDING`、`FLOOR`（由 `ProgramOtaStateStore` 落），指针切换时回调 `ProgramIndex.upsert` 自动登记
- **依赖**：开机对账、`ProgramRegistry`、`PackageInstaller`、`ProgramManager`、`InstanceHost`
- **成熟度**：完整可用

### 16. 给程序包起临时分片名，清掉中途崩溃留下的暂存目录
- **实现**：`lobos/os/ProgramDir.kt` — `stagingDirName`/`isStagingDir`/`isReplacedDir`/`sweepStaleStaging`
- **数据**：读 `opt/<id>/` 下匹配 `.tmp-<pid>-<ms>` 与含 `.replaced-` 的目录名、`currentVersion()`；写 CURRENT 版本目录缺失时把 `<cur>.replaced-*` rename 回 `<cur>`，删除其余暂存/被替换目录
- **依赖**：开机对账（`BootReconciler.run`）
- **成熟度**：完整可用

### 17. 回收装不下的旧版本目录，腾出磁盘
- **实现**：`lobos/os/ProgramDir.kt` — `pruneOldVersions`；另有 `lobos/runtime/PieceUpdater.kt` `prune`
- **数据**：读 `opt/<id>/` 目录列表与 CURRENT / FLOOR / PENDING 三处钉；写删除其余版本目录（保留额外 1 个），返回 (已删, 删不掉, 释放字节)
- **依赖**：开机对账（结果写 `RuntimeDiagnostics` "program-gc"）
- **成熟度**：完整可用。`PieceUpdater.prune` 保留但**无调用点**

### 18. 诊断当前版本落位是否自洽（指针在、目录在、入口在、清单在）
- **实现**：`lobos/os/ProgramDir.kt` — `integrityChecks`/`assertNotDirectlyExecutable`；`lobos/ota/ProgramOtaResolution.kt` `resolve`
- **数据**：读 CURRENT 指针、版本目录、`entryPath`、`program-manifest.json`；不写（只报告）
- **依赖**：`build.programStatus`、`InstanceHost`
- **成熟度**：完整可用（批内一处记「无调用方」，另一批给出 `build.programStatus`/`InstanceHost` 为调用方，**待确认**）

### 19. 给程序分配一个互不冲突的本地端口并记账
- **实现**：`lobos/os/PortBroker.kt` — `claim`/`release`/`list`；`lobos/os/ProgramManager.kt` `resolveHttpPort`
- **数据**：读/写 `run/ports.json`（范围 41000–50999）；写 `Journal.append(ctx,"ports",…)`
- **依赖**：`QuickAppBinder`、`QuickAppRegistry`、`os.ports.*`；`ProgramIndex.remove`、`PackageInstaller.uninstall` 自动释放
- **成熟度**：完整可用（`claim` 幂等；段满时返回 0，由各调用方处理）

### 20. 登记一件长期任务的受理、进度与结束，超 50 条时丢弃已完成的
- **实现**：`lobos/os/TaskRegistry.kt` — `start`/`update`/`finish`/`list`/`get`/`current`/`abandonRunning`
- **数据**：读/写 `run/tasks.json`（原子落盘，上限 50，优先淘汰 done/failed）
- **依赖**：`os.appmgr.install/upgrade/uninstall`、`os.journal.tasks/task`；开机对账 `abandonRunning("boot-reconcile")` 把残留 running 全标 failed + progress 100
- **成熟度**：完整可用（任务 id 形如 `kind-xxxxxxxx`）

### 21. 记住组件源地址和它上次更新时间
- **实现**：`lobos/os/RegistryStore.kt` — `info`/`setOrigin`/`read`
- **数据**：读/写 `var/lib/registry.json`（原子落盘，文件里只存 origin/updatedAt）
- **依赖**：批外（本批内无调用方）
- **成熟度**：**只做了一半**——`info()` 里 `put("programs", JSONObject.NULL)` 是硬编码 NULL，没有任何地方写真实的 programs 数组；import 的 `HttpURLConnection`/`URL` 未使用

---

## 三、状态账本

### 22. 记住系统里有哪些件与程序在册、各自的版本、配置与运行态
- **实现**：`lobos/os/ProgramIndex.kt` — `UnitEntry` data class、`encode`/`decode`、`all`/`get`/`upsert`/`remove`/`mutate`/`replaceAll`、`isPiece`、`byLevel`、`safeSegment`、`edited`、`Level`/`Desired`、`DEFAULT_BACKOFF`
- **数据**：读 `var/lib/program-index.json`（schema 必须为 1，否则整表视为空）；写同左（`StateFiles.writeJson`，按 id 排序落盘），`remove` 顺带 `PortBroker.release`
- **依赖**：几乎所有功能点——`ProgramManager`、`PieceScan`、`ProgramStatusHub`、`UnitJobs`、`BootReconciler`、`PackageInstaller`、`RuntimeEnvironment`、`ProgramDir`、`ProgramInstallPipeline`、`SupervisorPool`、`QuickAppRegistry`
- **成熟度**：完整可用（配置与运行态同处一个属性空间）

### 23. 记住某个 unit 该不该跑、想跑到什么态
- **实现**：`lobos/os/ProgramIndex.kt` `UnitEntry.desired` + `lobos/os/ProgramManager.kt` `setDesired`；消费端 `lobos/runtime/SupervisorPool.kt` `drainJobs`
- **数据**：读 `ProgramIndex.all` 的 `desired`/`after`；写 `run/unit-jobs.json`（schema 1）；`SupervisorPool.drainJobs` 最终写 `ProgramIndex.desired`
- **依赖**：开机对账、`os.instances.action`、`QuickApp/ProgramGroup`
- **成熟度**：**有缺口**——`setDesired` 被 `BootReconciler` 当同步改调用，实际只入队，返回 true 但状态没变；闭环要靠 `SupervisorPool.drainJobs` 落回

### 24. 按需改程序的启动参数与环境变量，只放行白名单里的键
- **实现**：`lobos/os/ProgramSettings.kt` — `read`/`patch`/`PatchResult`/`ALLOWED_KEYS`；配合 `lobos/os/RuntimeEnvironment.kt` `withoutReserved`
- **数据**：读/写 `var/lib/program-settings.json`（原子落盘）；拒绝时 `Journal.note(ctx,"settings",false,…)`
- **依赖**：`InstanceHost`、`os.programs.settings`、`QuickAppBinder`；批外 CLI
- **成熟度**：完整可用。仅允许 `args`/`env`，`env` 里命中 `RESERVED_ENV` 一律拒并列出

### 25. 知道哪些进程是系统自己起的，并防止 pid 复用误判
- **实现**：`lobos/os/ProcessLedger.kt` — `begin`/`end`/`list`/`nextGeneration`/`liveOwned`/`isOwnedAlive`/`starttimeOf`/`ppidOf`/`pidReused`/`gone`
- **数据**：读 `run/process-ledger.json`（schema 2）与 `/proc/<pid>/stat` 第 22 场 starttime、第 4 场 ppid；写同左
- **依赖**：`ProgramStatusHub`、`UnitJobs.takeReady`、`SessionRegistry.verify`、`ResidencyStatus`、`InstanceHost`
- **成熟度**：完整可用（JSON 不可解析时按「无进程」处理并留诊断）

### 26. 给程序签发一次性会话令牌，并按 pid+starttime 判定它是不是当初那个进程
- **实现**：`lobos/os/SessionRegistry.kt` — `issue`/`bindPid`/`verify`/`claim`/`clear`/`socketName`
- **数据**：读/写 `run/sessions.json`（schema 1），字段 issuedAt/claimedAt/pid/starttime；读 `ProcessLedger.starttimeOf`/`isOwnedAlive`
- **依赖**：能力桥握手（`invokeLocal`、程序侧 extBridge）；由 `RuntimeEnvironment.RESERVED_ENV` 间接体现 `LOBOS_SESSION_TOKEN`、`LOBOS_BRIDGE_SOCKET`
- **成熟度**：完整可用。令牌 24 字节 SecureRandom；未绑定 pid 的会话有 20s 宽限期；`claim` 保证一个 token 只被首次领取

### 27. 知道每个 unit 的 LOAD/ACTIVE/SUB 三列状态（算出来的，不是记下来的）
- **实现**：`lobos/os/UnitState.kt` — `Load`/`Active`/`Sub` 枚举、`loadOf`/`activeOf`/`subOf`/`isRunnable`/`isActive`
- **数据**：读 `UnitEntry`（`invalid`/`restarts`/`maxRestarts`/`role`/`exitCode`）+ 调用方传入的 `processAlive`/`healthy`/`startRequested` 等事实；不写（纯函数）
- **依赖**：`ProgramStatusHub.statusOf`、`UnitJobs.takeReady`、`OsInit.statusLine`、前台通知、`os.host.status`
- **成熟度**：完整可用。`activeOf` 的 `maintenance`/`reloadRequested` 两个参数在 `ProgramStatusHub` 里恒为默认 false

### 28. 给每个程序/件算出一份状态面板，并把状态变化记进日志
- **实现**：`lobos/os/ProgramStatus.kt` — `ProgramStatus` data class、`ProgramStatusHub.publishHealth/publishRestarts/publishQuarantined/forget/clear/snapshot/statusOf/toJson/summaryLine`、`CapabilityRuntimeState.controlPlaneUp`
- **数据**：读 `ProgramRegistry.listIds`、`ProgramIndex.get`、`ProgramManager.currentVersion`、`ProcessLedger.isOwnedAlive/liveOwned`、`OsInit.current`；写内存 Map（`healthDetail`/`restarts`/`quarantined`/`startedAt`/`lastState`），变化时 `Journal.note(ctx,"state",…)`
- **依赖**：`OsInit.statusLine`、`OsHostService.buildHostNotification`、面板与状态磁贴（UI/API 层，**待确认**）
- **成熟度**：**有缺口**——① `publishQuarantined` 维护的 `quarantined` 集合被 `statusOf` 从不读；② `startedAt` 无写入点，`aliveMs` 恒 0；③ `CapabilityRuntimeState.controlPlaneUp` 以 `ResidencyStatus.updatedAt` 60s 内为「控制面在线」，是心跳推断而非真实探测

### 29. 把「已登记」与「落位实物」对账，产出设施清单视图
- **实现**：`lobos/os/ProgramManager.kt` — `snapshot`/`realityOf`/`status`/`reconcile`/`assemble`/`stateDirOf`/`infraSourceFile`
- **数据**：读 `ProgramIndex`、`SystemDirs.opt`、`nativeLibraryDir`；写 `var/lib/program-state.json`、`usr/facilities.json`、`usr/current/<id>` 软链
- **依赖**：`os.facilities.list`、`os.packages.list`、开机对账
- **成熟度**：完整可用（判据是注册表 `role`，不读包外清单 `kind`）

---

## 四、件的供给

### 30. 从落位目录反推「装了什么件、什么版本、入口在哪」，写回注册表
- **实现**：`lobos/os/PieceScan.kt` — `scan`/`rebuild`/`entryOf`/`roleOf`/`metaOf`/`filesOf`/`sha256Of`；内部 `SupplySha.sha256`
- **数据**：读 `usr/lib/<id>/` 与 `usr/lib/<id>/<版本>/` 目录树、件自带 `component-meta.json`、逐文件 sha256；写通过 `ProgramIndex.upsert` 覆盖 `version/stateDir/assetEntry/role/sha256/required/files`
- **依赖**：`OsInit.beginLife`、`RuntimeEnvironment.assemble`、`PrefixProvisioner.registerProvisioned`、`ProgramInstallPipeline.satisfyElfDeps`
- **成熟度**：**有缺口**——注释声明「扫不到的条目移除」，但 `rebuild` 在 `found.isEmpty()` 时直接返回 0、且从不删除扫不到的旧条目（`ProgramIndex` 里也没有按扫不到清理的路径）。整件 sha256 是按路径序串起逐文件哈希再哈希，任一文件被换即变

### 31. 判断某件是否被换过或删过（逐文件比对登记的 sha256）
- **实现**：`lobos/os/PieceScan.kt` — `verify`/`verifyAll`/`Verdict`
- **数据**：读注册表 `UnitEntry.files` + `stateDir` 下实际文件的 sha256；不写
- **依赖**：`PieceProvisioner.prepare`、`sys.nativeAssets`
- **成熟度**：**有缺口**——注释标明「登记里有现在没有的」这条其实未实现，代码只判缺文件/被换，**未实现**对比「多余文件」。一处批内另记「本批内无调用点，接线在批外 — 待确认」

### 32. 回答「这个 id 落位在哪、入口文件是哪个、它的自带说明是什么」
- **实现**：`lobos/os/PieceScan.kt` — `pieceDir`/`pieceFile`/`pieceMeta`/`shellBin`
- **数据**：读 `ProgramIndex.get` 的 `stateDir`/`assetEntry`、件目录下的 `component-meta.json`；不写
- **依赖**：`RuntimeEnvironment.treeRootEnv`（posix 件 → `LD_PRELOAD`、`SHELL`）、`LocalExec`、`PtySession`、`InstalledRuntime`
- **成熟度**：**有缺口**——`shellBin` 按 `role == SHELL/MULTI_COMMAND` 找，但 `roleOf` 只会产出 `exec`/`library`/`headers`，这两个常量无人产出，实际靠 `id == "bash"` / `id == "busybox"` 兜底

### 33. 把 APK 里带来的系统件铺进 `usr/lib/<id>/<版本>/`，建全局软链，落 CA 证书与头文件链接
- **实现**：`lobos/runtime/PrefixProvisioner.kt` — `provision`/`landOne`/`scanMeta`/`linkHeadersInclude`/`registerProvisioned`
- **数据**：读 `assets/supply/meta/*.meta.json`、`nativeLibraryDir`、`assets/ca-bundle.pem`；写 `usr/lib/<id>/<版本>/bin|lib/`、`usr/bin/`、`usr/lib/`、`usr/include`、`usr/ca-bundle.pem`、`ProgramIndex`
- **依赖**：`RuntimeEnvironment.ensure`（宿主启动即跑）
- **成熟度**：完整可用（`.so.1` 这类版本化层用同 id 的 `.so` 顶上；同名不同版本靠覆盖 + 改软链）

### 34. 让装好的程序有可调用的全局入口（`$PREFIX/bin` 在 PATH 里）
- **实现**：`lobos/runtime/PrefixProvisioner.kt` `landOne`（`Files.createSymbolicLink`）；`lobos/os/PieceScan.kt` `entryOf`
- **数据**：读 `SystemDirs.bin` 与 `ExecBits` 判据；写符号链接
- **依赖**：`ProgramInstallPipeline.linkEntry`、`PieceUpdater.place`
- **成熟度**：完整可用

### 35. 把 `$PREFIX` 里的件全部铺到位，并算出还缺哪几件
- **实现**：`lobos/os/RuntimeEnvironment.kt` — `ensure`/`assemble`/`isLink`/`Snapshot`
- **数据**：读 `PrefixProvisioner.provision` 结果、`PieceScan.scan` 中带 `bin/` 的件的 bin 目录实际文件；写 `$PREFIX` 下由 `PrefixProvisioner` 建软链、`RuntimeDiagnostics.append(ctx,"prefix",…)`，每 10 分钟打一条 supply 说明
- **依赖**：`OsHostService` 启动、`InstanceHost`
- **成熟度**：完整可用（进程内缓存 `cached`，`complete` 时直接返回；注释明确「组件供给不再由 App 启动自动安装」，改由装程序时按 requires 触发）

### 36. 判断某件在不在册、在不在落位、可不可执行、依赖齐不齐
- **实现**：`lobos/pieces/PieceProvisioner.kt` — `prepare`/`verifyInternal`/`AssetStatus`/`PrepareReport`/`readApkLibEntries`
- **数据**：读 `nativeLibraryDir` 列表、APK 内 `lib/*.so` 条目、`ElfFacts.read` 的 DT_NEEDED、`ProgramIndex` 登记；写 `var/log/diagnostics.txt`（stage=native-assets，含 data=report）
- **依赖**：`InstanceHost.bootProgramOnce`（不过则中止启动）、`os.provisioning.get`、`sys.nativeAssets`
- **成熟度**：**有缺口**——`AssetStatus.Unusable` 与 `parseErrno`/`err` 定义了但无任何构造点，实际只会产生前四种状态

### 37. 把系统件与远端清单逐条比对并更新，回滚到 APK 原件
- **实现**：`lobos/runtime/PieceUpdater.kt` — `checkAndUpdate`/`installGroup`/`place`/`pointEntryAt`/`rollback`/`prune`/`states`
- **数据**：读 `native-manifest.json` + `.sig`、`component-public.pem`、本机件 sha256；写 `usr/lib/<id>/<版本>/`、`usr/bin|usr/lib` 软链、`etc/installed.json`（selectedVersion）
- **依赖**：`lobos.sys.native.status/update/rollback`
- **成熟度**：**有缺口**——`State.updated` 恒为 false（`states()` 里写死）；`isManagedByUpdate` 与 `ProgramIndex_safeSegment` 是死代码

### 38. 从远端取回组件目录并 ed25519 验签，检查有效期后缓存到本地
- **实现**：`lobos/os/CatalogClient.kt` — `refresh`/`cached`
- **数据**：读 `assets/supply/channel.json`、`assets/supply/component-public.pem`、HTTP 取 `<base>/<manifests.component.name>` 与其 `.sig`；写 `var/lib/catalog.json`（fetchedAt/baseUrl/channel/revision/manifestName/body）
- **依赖**：`PackageInstaller.install`（取 `entryFor`）、`ProgramInstallPipeline`、`os.catalog.list/refresh`、`os.runtime.nodeLts`
- **成熟度**：完整可用（TTL 6h；`expiresEpochMs` 缺失或已过期即拒用；缓存命中但已过期也返回失败）

### 39. 把远端目录的多种形状统一成一份条目列表，并标出哪些已装、哪些可升级
- **实现**：`lobos/os/CatalogClient.kt` — `entries`/`normalize`/`entryFor`/`list`
- **数据**：读 `var/lib/catalog.json`、`etc/.<name>.ok`（已装 sha 标记）、`ProgramManager.currentVersion`、`$PREFIX/bin/<entry>` 是否存在；不写
- **依赖**：`PackageInstaller`、`os.packages.list`、面板
- **成熟度**：完整可用（兼容 `packages` / `tools`+`products` 两种旧布局；`stale`/`upgradable` 字段齐）

### 40. 定义远端发布通道锚点（baseUrl / channel / 各清单名），设备侧可覆盖
- **实现**：`lobos/runtime/SupplyProvisioner.kt` — `channelAnchor`/`manifestDir`/`manifestNameOf`/`manifestSigNameOf`/`uncached`；`lobos/ota/ProgramOtaUpdater.kt` — `Config`/`loadConfig`/`parseConfig`
- **数据**：读 APK `assets/supply/channel.json`；优先 `filesDir/etc/channel.json` 覆盖；不写
- **依赖**：`CatalogClient`、`PieceUpdater`、`ProgramOtaUpdater`
- **成熟度**：完整可用（非 https 一律返回 null，OTA 被判为「关闭」）

---

## 五、装机与 OTA

### 41. 下载并安装一个程序包（校验 sha256、门禁运行时、交给安装流水线落位）
- **实现**：`lobos/os/PackageInstaller.kt` — `install`/`runtimeGate`/`fail`
- **数据**：读 `CatalogClient.entryFor`（`url/sha256/entry/kind/requires`）、`ProgramIndex.all` 找运行时件与其已装版本；写 `cacheDir/<name>.pkg.zip.part`（临时，事后删），由 `lobos/ota/ProgramInstallPipeline.kt` 落位，失败时 `RuntimeDiagnostics.append`
- **依赖**：清单获取、安装流水线、版本范围
- **成熟度**：完整可用。版本缺省时回退到目录 `version`、再回退到 sha 前 12 位

### 42. 按目录条目装包前先卡住运行时依赖（缺件 / 版本不满足即拒）
- **实现**：`lobos/os/PackageInstaller.kt` `runtimeGate`；`lobos/os/VersionRange.kt` `satisfies`
- **数据**：读 `ProgramIndex` 中 role=PIECE 的版本、目录 `requires`/`runtime`；不写
- **依赖**：程序包下载与安装
- **成熟度**：完整可用。版本范围支持 `>= <= > < =` 组合，解析失败即拒；另一批记「仅支持空格分隔的 AND，不支持 `||`」

### 43. 验包——sha256、包内 `program-manifest.json`、ed25519 双向签、外部清单签
- **实现**：`lobos/kernel/crypto/ProgramPackageVerifier.kt` — `verify`/`canonical`/`sha256HexFile`/`publicKeyPem`；`lobos/ota/ProgramInstaller.kt`（安装前的 fail-closed 检查）
- **数据**：读候选 zip、`assets/supply/component-public.pem`、外部清单文件；不写
- **依赖**：`ProgramInstaller.install`、`ProgramOtaUpdater`
- **成熟度**：**有缺口**——一处批内记「只做了一半：本批内无调用方」，另一批记「完整可用，上限 64MB，清单无 signature 直接拒」。**待确认**

### 44. 把组件包解包落到 `opt/<id>/<版本>/`，铺齐 ELF 依赖、建入口软链、切 CURRENT、登记注册表
- **实现**：`lobos/ota/ProgramInstallPipeline.kt` — `installComponent`/`satisfyElfDeps`/`linkerCanFind`/`linkEntry`/`PieceMeta`
- **数据**：读 zip、包内 `component-meta.json`、`systemLibraryDir`、jniLibs、`usr/lib`、`usr/bin`；写 `opt/<id>/<版本>/`、`usr/bin/<名字>` 软链、`ProgramIndex`
- **依赖**：`PackageInstaller.install`、`ProgramOtaUpdater`
- **成熟度**：完整可用。依赖判据是「linker 搜索顺序里找不找得到」而非写死 `.so` 名单

### 45. 把程序包解包落到 `opt/<id>/<版本>/`，平铺后端、改写入口、挂前后端、切指针
- **实现**：`lobos/ota/ProgramInstaller.kt` — `install`/`flattenBackend`/`rewriteEntry`/`FRONTEND_REQUIRED`
- **数据**：读 zip、包内清单、`var/lib/program-settings.json`；写 `opt/<id>/<tmp>/` → `<版本>/`、`quickapp/`、`CURRENT`、`PENDING`
- **依赖**：`ProgramInstallPipeline.install`（APPLICATION 分支）、OTA 升级
- **成熟度**：完整可用（每步失败都有 rollback 分支：aside/replaced 复位）

### 46. 解包 zip 并挡住目录穿越
- **实现**：`lobos/ota/ProgramArchive.kt` — `unzip`（目录穿越防护）；`lobos/runtime/SupplyProvisioner.kt` — `unzipFromFile`/`unzipInto`
- **数据**：读 zip；写 `dest/**`，逐条 canonical 路径越界即抛（空 zip 也拒）
- **依赖**：组件包与程序包的两条安装流水线
- **成熟度**：完整可用。**注意同一件事有三套解包实现**（`ProgramArchive.unzip` / `ProgramDir.unzipInto` / `SupplyProvisioner.unzipFromFile`），见「系统性问题」

### 47. 执行 OTA 更新策略裁定——过期 / 重放 / 回退 / 灰度全拒或放行
- **实现**：`lobos/ota/OtaPolicy.kt` — `evaluate`/`bucketOf`/`Verdict`；`lobos/ota/ProgramOtaVersions.kt` — `compare`/`isNewer`/`isBelowFloor`
- **数据**：读 manifest 的 `version/sequence/expiresEpochMs/rolloutPercent/sha256/signature`、`lastSequence`、`floorVersion`、`installId`；不写
- **依赖**：`ProgramOtaUpdater`、`ProgramOtaSelfCheck`
- **成熟度**：完整可用（灰度按 `hashCode(installId:version)%100` 分桶）

### 48. 检查远端程序更新并在预算内下载安装
- **实现**：`lobos/ota/ProgramOtaUpdater.kt` — `checkAndUpdate`/`checkAndUpdateNet`/`installId`/`promotePendingSequence`/`dropPendingSequence`；`lobos/ota/ResumableDownloader.kt`
- **数据**：读 manifest、`run/ota-state-<channel>-<id>.json`、`etc/install-id.json`；写 `cacheDir/*.part`、安装副作用、`run/ota-state-*.json`（pendingSequence/lastSequence）
- **依赖**：`os.appmgr.install/upgrade/checkUpdate`、`build.programInstall`、`InstanceHost` 启动自检、WiFi 高性能锁
- **成熟度**：完整可用（启动预算、Range 续传、3 次重试、416 处理、sha256 失败即弃包）

### 49. 程序健康通过才提交版本（提升 FLOOR、推进 feed 序列号），不通过则回滚
- **实现**：`lobos/ota/ProgramOtaStateStore.kt` — `markPending`/`setFloor`/`clearPending`/`rollbackTo`；`lobos/runtime/InstanceHost.kt` — `commitPendingKernel`/`rollbackIfPendingFailed`
- **数据**：读 `PENDING`、`CURRENT`、健康探活结果；写 `FLOOR`、`PENDING`、`run/ota-state-*.json`
- **依赖**：OTA 全链、指针存储
- **成熟度**：完整可用

### 50. 卸载一个程序并把它留下的痕迹一并清掉
- **实现**：`lobos/os/PackageInstaller.kt` — `uninstall`/`linkNames`
- **数据**：读 `ProgramIndex.get`（判是否件/基础设施）、`ProgramManager.stateDirOf`（必须在 `filesDir` 内）、`CatalogClient.entryFor` 取 `entry`/`aliases`；写删 `opt/<id>/` 或 `usr/lib/<id>` 整树、`usr/bin`/`usr/lib` 下入口与别名的软链、`ProgramIndex.remove`、`DesktopIcons.withdrawNow`、`PortBroker.release`
- **依赖**：`os.packages.action`(uninstall)、`os.facilities.action`
- **成熟度**：完整可用。件（随 APK 交付）一律拒绝；越界路径拒绝；删不掉时保留索引，避免「索引说没装、文件还在」

### 51. 把某个程序删回 APK 随包基线
- **实现**：`lobos/os/PackageInstaller.kt` — `rollbackToBaseline`
- **数据**：读 `ProgramIndex.get`（件则拒绝）、`stateDirOf` 越界检查；写删 `opt/<id>/` 整树
- **依赖**：`os.packages.action`
- **成熟度**：**有缺口**——删目录后**不清理索引、不删软链、不释放端口**（与 `uninstall` 行为不对称）；删不掉时也保留索引

---

## 六、程序监管

### 52. 把每个程序拉起来、管住它、探活、按策略重启或隔离
- **实现**：`lobos/runtime/InstanceHost.kt` — `bootLoop`/`bootProgramOnce`/`requestStart|Stop|Restart`/`shutdown`；`lobos/runtime/SupervisorPool.kt` — `sync`/`drainJobs`/`wantedPrograms`
- **数据**：读 `ProgramRegistry.spec`、`ProgramIndex`、`ProgramSettings`、`ProgramDir`、OTA 配置、`ProcessLedger`；写进程、`ProgramIndex`（pid/starttime/restarts/exitCode）、`run/proc/runtime.json`、`ProgramStatusHub`
- **依赖**：一切要程序在跑的东西
- **成熟度**：完整可用（持久 wakeLock、`STABLE_MS`/`maxRestarts`/隔离半开、30s 健康预算）。每 15s 一条 `supervisor:id` 诊断，是日志量的大头

### 53. 读懂一个程序的清单，得出它的启动规格（入口、参数、端口、生命周期、能力、UI 绑定、重启策略）
- **实现**：`lobos/os/ProgramRegistry.kt` — `spec`/`list`/`listIds`/`installedVersions`/`PortDecl`/`Spec`；`lobos/os/ManifestSchema.kt` — `validate`/`validId`/`restartOf`/`spec`/`toJson`，含 `validateLifecycle`/`validateHttp`/`validateRequires`/`validateUi`
- **数据**：读 `opt/` 下的目录名当 id 列表、`<dir>/<version>/program-manifest.json`；不写
- **依赖**：`InstanceHost`、`ProgramStatusHub`、`ProgramManager.snapshot`、`ProgramDir.registerCurrent`、`RuntimeEnvironment.libSearchPath`、`os.manifest.validate`、`QuickAppRegistry`
- **成熟度**：**有缺口**——`currentVersion()` 对非标准布局可能为空，此时 `spec` 返回 null；清单缺失时返回一份 `invalid` 的占位 Spec（不是 null）。`Spec.invalid` 非空即 `startable=false`（schema=4）

### 54. 把「请求让某单元到达某态」排成作业，先验事务（单元在册、排序无环）再入队，并按 after 依赖取出下一个可执行作业
- **实现**：`lobos/os/UnitJobs.kt` — `Job`/`Verdict`/`enqueue`/`verify`/`findCycle`/`takeReady`/`read`/`pendingCount`；`lobos/os/ProgramManager.kt` `requestDesired`；`lobos/runtime/SupervisorPool.kt` `drainJobs`
- **数据**：读 `ProgramIndex.all` 建 `after` 有向图（DFS 三色标记找回边）、`run/unit-jobs.json`、`/proc/<pid>/stat`；写 `run/unit-jobs.json`（schema 1），`drainJobs` 写 `ProgramIndex.desired`
- **依赖**：`os.instances.action`、`QuickApp/ProgramGroup`、`SupervisorPool.sync`、`BootReconciler.reconcileNotRunning`
- **成熟度**：**有缺口**——一处批内记 `takeReady` 在该批内无消费者、`setDesired` 被当同步写调用；另一批给出闭环端 `SupervisorPool.drainJobs`。入队与出队闭环是否成立 **待确认**

### 55. 开机时把系统从上次的状态收拾干净（半包、残留、废弃任务、陈旧 PENDING、入口缺失）
- **实现**：`lobos/os/BootReconciler.kt` — `run`/`reconcileNotRunning`/`expireStalePending`/`reconcilePending`/`repairMissingEntry`/`Report`
- **数据**：读临时文件、`.part`、`tasks.json`、`ProgramIndex`、`ProgramRegistry.listIds`、各 `ProgramDir` 的 CURRENT/PENDING、`opt/<id>/` 各版本目录、cacheDir；写删临时/半包/旧版本、`TaskRegistry` 状态、置 `Desired.STOPPED`、清 `PENDING`、改 `CURRENT`、`var/lib/program-state.json`、重启 `ProgramGroup.reconcile`、`CompatSemantics.write`、`Journal.note(ctx,"boot",…)`
- **依赖**：`InstanceHost.bootProgramOnce` 首启一次；宿主启动流程
- **成熟度**：**有缺口**——① `expireStalePending` 里 `File(pm.programRootDir(),"PENDING").lastModified()` 在 PENDING 文件不存在时会抛 `FileNotFoundException`，外层 `run` 未包它（PENDING 由 store 管理，**待确认**）；② `reconcileNotRunning` 依赖 `setDesired` 同步生效，而后者只入队

---

## 七、调用面

### 56. 让本地程序通过一条本地 socket 用 JSON-RPC 调宿主能力
- **实现**：`lobos/bridge/CapabilityBroker.kt` — `start`/`listenLoop`/`handleConnection`/`readFrameBounded`/`writeFrame`/`dispatch`；`lobos/runtime/GuestAdapter.kt` `BRIDGE_SOCKET`
- **数据**：读 `LocalServerSocket`/`LocalSocket`；写 `filesDir/bridge-audit.log`（每次调用一行）
- **依赖**：所有程序（控制面板即其一）
- **成熟度**：完整可用（帧上限 256KB、并发 16、BindException 时沿用既有 socket、异常必回帧）

### 57. 握手时校验协议版本与一次性会话令牌，按清单授予能力组
- **实现**：`lobos/bridge/CapabilityBroker.kt` — `handshake`/`serverGranted`/`declaredCapabilities`/`isProgramSession`；`lobos/os/SessionRegistry.kt`
- **数据**：读 `run/sessions.json`、`ProgramIndex.get(programId).capabilities`、`ProgramRegistry.listIds`；写 `run/sessions.json`
- **依赖**：本地 socket 桥、`invokeLocal`、程序侧 extBridge
- **成熟度**：完整可用，但**授权规则是「清单声明什么就发什么」**，不再判系统是否允许（代码注释明说旧判据体系已清空）。另见「系统性问题」第 4 条关于作用域护栏名不副实

### 58. 对外公布桥的 API 规格（分层、别名、作用域、副作用、错误码）
- **实现**：`lobos/bridge/ApiSpec.kt` — `toJson`/`surface`/`canonical`/`scopeOf`/`tierOf`，含 `METHODS`/`OS_METHODS` 键集合
- **数据**：读方法名键集合；不写
- **依赖**：`sys.api` 方法、控制面板、快应用侧 `LobosBridge`
- **成熟度**：**有缺口**——实际注册的方法名（`os.*`/`ui.*`/`fs.*`/`app.*`）大量不在 `CANONICAL` 表里，`canonical()` 靠原样返回兜底，别名映射只覆盖了一部分；旧短名以原名注册，绕过 `canonical`

### 59. 把包 / 目录 / 实例 / 任务 / 诊断等查询与动作以桥方法暴露出去
- **实现**：`lobos/bridge/CapabilityBroker.kt` 的 `OS_METHODS` + `METHODS`（约 50 个 MethodDef）；`lobos/os/TaskRegistry.kt`、`lobos/os/ProgramSettings.kt`
- **数据**：读 `ProgramIndex`、`ProgramDir`、`TaskRegistry`、`Journal`、`RuntimeDiagnostics`；写按方法不同写 `tasks.json`/`program-settings.json`/安装任务
- **依赖**：控制面板
- **成熟度**：**有缺口**——`os.instances.action` 的 `stop` 与 `os.session.stop` 被显式拒绝（注释：宿主尚无按程序独立停止/停机能力）；`os.programs.overview` 的 `upgrade.updateAvailable` 硬编码 `false`

### 60. 在宿主内直接调用桥方法（不经 socket），供快应用能力桥复用
- **实现**：`lobos/bridge/CapabilityBroker.kt` — `invokeLocal`/`prepareSession`
- **数据**：读同上；校验 programId 在册；写 `run/sessions.json`（每次签发一条）
- **依赖**：`quickapp/LobosBridge.kt` 的 `invoke`
- **成熟度**：完整可用

### 61. 做 UI 自动化——点按、滑动、输入文本、读界面树、等控件、翻页与全局动作
- **实现**：`lobos/lifecycle/OsAccessibilityService.kt` — `performTap`/`performSwipe`/`inputText`/`dumpUiTree`/`waitForNode`/`performGlobalAction`/`dispatchPath`
- **数据**：读无障碍节点树（`rootInActiveWindow`、`getWindows`）；写剪贴板（`inputText` 的 PASTE 降级路径）
- **依赖**：桥 `ui.tap`/`ui.swipe`/`ui.inputText`/`ui.getUiTree`/`ui.waitFor`/`ui.globalAction`
- **成熟度**：完整可用

### 62. 记录并按序号拉取无障碍事件流
- **实现**：`lobos/lifecycle/OsAccessibilityService.kt` — `eventLog`/`eventsSince`/`dropEventsBefore`/`takeWindowDirty`/`eventSeq`/`uiSeq`
- **数据**：读写内存环形队列（上限 256）
- **依赖**：桥 `ui.events`/`ui.dropEvents`
- **成熟度**：完整可用（仅内存，宿主进程死即丢，代码未处理持久化）

### 63. 把设备上的文件按 utf8/base64 读出、按文本/base64 写入
- **实现**：`lobos/bridge/CapabilityBroker.kt` — `fs.read`/`fs.write`/`fs.list`/`fs.mkdir` + `requireReadableFile`/`requireWritableFile`
- **数据**：读任意绝对路径文件（上限默认 8MB、硬顶 64MB）；写目标文件（append 或覆盖）
- **依赖**：程序自助排障
- **成熟度**：完整可用

### 64. 列出 / 启动 / 打开应用与 URL
- **实现**：桥 `app.listInstalled`/`app.launch`/`app.openUrl`
- **数据**：读 `PackageManager`；不写
- **依赖**：程序
- **成熟度**：完整可用，但 `app.listInstalled` 以 `caps=["base"]` 暴露全量应用列表（见「系统性问题」第 3 条）

### 65. 把程序的前端包装进快应用运行时并打开
- **实现**：`lobos/quickapp/QuickAppHost.kt` — `install`/`open`/`close`/`hide`/`zipStore`/`ready`；`lobos/quickapp/QuickAppPackage.kt` — `check`/`withEndpoint`/`withEntry`/`entryOf`
- **数据**：读 `opt/<id>/quickapp/`、`config.json`、`ProgramIndex`；写 `config.json`（backend.endpoint、path）、`opt/<id>/<id>.zip` 临时包
- **依赖**：`QuickAppLaunchActivity`、`LobosBridge.openApp`
- **成熟度**：完整可用（前端包结构三件套校验、appId 一致性、入口页必填）。`hide` 无有效产出

### 66. 把后端地址与入口页写进前端配置，让前后端成对可用
- **实现**：`lobos/quickapp/QuickAppBinder.kt` — `bindIfQuickApp`/`loadIntoDimina`
- **数据**：读 `ProgramSettings`、`ProgramManager.resolveHttpPort`、`ui.entry`；写前端 `config.json`、`ProgramIndex`（经 `QuickAppRegistry.register`）
- **依赖**：`ProgramInstaller`、`ProgramGroup.ensureBackendRunning`
- **成熟度**：完整可用

### 67. 打开前端前先确保后端在跑，前端关掉时按声明决定后端去留
- **实现**：`lobos/quickapp/ProgramGroup.kt` — `ensureBackendRunning`/`onUiClosed`/`onBackendExit`/`shouldStopBackendOnUiClose`/`reconcile`/`hasUi`；`lobos/quickapp/Foreground.kt`；`lobos/quickapp/QuickAppLaunchActivity.kt`
- **数据**：读 `ProgramIndex` 的 `pid/starttime/desired/onUiClosed`、Activity intent 的 `EXTRA_ID`；写经 `ProgramManager.requestDesired` 排作业、`QuickAppHost.close` 收前端、journal
- **依赖**：`QuickAppLaunchActivity`；`ProgramGroup.onBackendExit` 由 `InstanceHost.watchExit` 调
- **成熟度**：完整可用。这是替代 cgroup 的关键一层，注释强调此前三个接口零调用者、现已接通；但另一批摘要仍将 `ProgramGroup.onBackendExit` 列为空接线，**待确认**

### 68. 判断某个程序是不是快应用，并把它的 UI 绑定抄进注册表
- **实现**：`lobos/quickapp/QuickAppRegistry.kt` — `isQuickApp`/`register`/`listed`/`uiOf`
- **数据**：读包内 `manifest` 的 `ui.type`/`ui.*`/`http.health`；写 `ProgramIndex` 的 `uiPackage/uiName/uiIcon/onUiClosed/httpPort/httpHealth`
- **依赖**：`QuickAppBinder`、`LobosBridge`
- **成熟度**：完整可用

### 69. 给快应用的前端开通一组宿主能力（ping / 能力清单 / 开应用 / 调桥 / 桌面图标）
- **实现**：`lobos/quickapp/LobosBridge.kt` — `handle`/`invoke`/`openApp`/`backendEndpoint`/`desktop*`；`OsApplication.kt` `initQuickAppRuntime`
- **数据**：读 dimina 事件、`ProgramIndex`、`api-spec` 的 canonical/scope；写 `DesktopIcons`、转发到 `CapabilityBroker.invokeLocal`
- **依赖**：`OsApplication` 启动时 `Dimina.init` + `registerExtModule("lobos")`
- **成熟度**：完整可用（系统作用域方法在快应用侧直接拒）

### 70. 在桌面添加 / 移除 / 查询程序的图标
- **实现**：`lobos/quickapp/DesktopIcons.kt` — `request`/`withdraw`/`withdrawNow`/`state`/`isSupported`
- **数据**：读 `ShortcutManager.pinnedShortcuts`、`ProgramManager.stateDirOf` 下的图标文件；写 `ShortcutManager.requestPinShortcut`/`disable`
- **依赖**：`PackageInstaller.uninstall`、`LobosBridge.desktop*`
- **成熟度**：**【输入截断，待补】**——原始输出在此条中途被截断

---

## 八、宿主与保活

### 71. 开机 / 解锁 / 亮屏后把 OS 宿主拉起来并保证它活着
- **实现**：`lobos/lifecycle/BootReceiver.kt` — `onReceive`/`startQuietly`；`lobos/lifecycle/OsHostService.kt` `ensureRunning`；`OsApplication.kt` `registerWakeupEdges`（USER_PRESENT / SCREEN_ON）
- **数据**：读 `OsHostService.stateDirOf(...)` 等实例字段、广播 intent action；写进程状态（启动 OsHostService）
- **依赖**：能力桥、程序监管池、截屏控制器
- **成熟度**：完整可用（`startForegroundService` 被拒时降级 `startService`，失败写诊断）

### 72. 把整盘启动健康装成唯一前台服务并自己心跳续命
- **实现**：`lobos/lifecycle/OsHostService.kt` — `onStartCommand`/`promoteToForeground`/`tick`/`buildHostNotification`/`onDestroy`
- **数据**：读 `SystemDirs.run`/`var`/`log`、`ProgramStatusHub.snapshot`、`ProcessLedger`、`AccessibilityServiceState`、`ResidencyStatus`、`DriverRegistry`；写 `var/log/`、`var/lib/state.json`（OsInit）、`run/residency.json`、前台通知 id 1004
- **依赖**：一切经桥的能力；面板与状态磁贴
- **成熟度**：完整可用（`START_STICKY` + 15s 节拍 `ResidencyPolicy.FAST_TICK_MS`）

### 73. 在 Doze 抑制期之外按闹钟把自己叫醒，保证节拍不丢
- **实现**：`lobos/kernel/power/DozeBackstop.kt` — `schedule`/`armedRecently`/`ACTION`；接收方 `lobos/kernel/power/DozeBackstopReceiver.kt`
- **数据**：读写内存 `lastArmedAt`；写 `AlarmManager.setAndAllowWhileIdle` 一条 ELAPSED_REALTIME_WAKEUP 广播（15min）
- **依赖**：宿主节拍每拍校验「兜底闹钟是否按期布防」
- **成熟度**：完整可用。`armedRecently` 判据是 2×INTERVAL_MS；失败静默返回 false

### 74. 判定并公布「系统现在是不是降级了」，给出该做什么
- **实现**：`lobos/lifecycle/ResidencyPolicy.kt` — `frozen`/`degradedReasons`/`actions`；`lobos/os/ResidencyStatus.kt` — `record`/`recordWake`/`snapshot`/`persist`/`restore`/`detail`
- **数据**：读 `OsHostService` 传入的 `programsRunning`/`installedPrograms`、`ResidencyStatus.snapshot()` 的 `degradedReasons`；写内存 `last` 与 `run/residency.json`
- **依赖**：`os.runtime.status`、前台通知、`OsPhaseRule.degraded`、`CapabilityRuntimeState.controlPlaneUp`（读 `updatedAt`）
- **成熟度**：完整可用。代码明确写死「无障碍不是保活锚」，不参与降级理由；`detail()` 读的 `tier` 字段本文件从不写入，恒为 `"?"`（写入方在批外，**待确认**）

### 75. 把「上一次是怎么死的」说清楚（死因归因）
- **实现**：`lobos/lifecycle/ResidencyAudit.kt` — `auditPreviousExit`/`heartbeat`/`interruption`/`interruptionText`；`lobos/log/KillAudit.kt` — `auditOnce`/`attribution`
- **数据**：读系统 `getHistoricalProcessExitReasons`、`var/log/residency.txt`、`run/kill-audit-cursor.txt`；写 `var/log/residency.txt`（心跳两行：存活时刻 + bootBasis）、`var/log/journal/events.jsonl`（kill-audit 分类事件）
- **依赖**：`OsInit` 把 interrupted 文案带给状态行与桥
- **成熟度**：完整可用（API < R 走 `UNREADABLE`，明确记录「未取证」）

### 76. 在通知栏常驻一行「OS 状态 + 程序状态摘要」，点进去是启动入口
- **实现**：`lobos/lifecycle/OsHostService.kt` `buildHostNotification`/`entryActivity`；`lobos/os/ProgramNotification.kt`
- **数据**：读 `ProgramStatusHub.snapshot`、`ProgramNotificationHub.list()`；写 `NotificationManager` 通知
- **依赖**：用户；`ui/StatusTileService`
- **成熟度**：完整可用

### 77. 判定整个系统处于启动中 / 运行中 / 降级 / 停止中，并说出降级原因
- **实现**：`lobos/os/OsState.kt` — `OsPhase`/`OsFacts`/`OsSnapshot`、`OsPhaseRule.degraded`/`next`/`reason`
- **数据**：读 `ResidencyStatus.snapshot()` 的 `degradedReasons`（找 `REASON_NO_PROGRAM`/`REASON_NONE_RUNNING`）；不写（纯函数）
- **依赖**：`OsInit.refresh`
- **成熟度**：**有缺口**——`facts.controlPlaneUp` 需外部填；BOOTING/STOPPING 期间 `next` 恒返回 null，即启动后没有自动跃迁的入口（**待确认**在批外）

### 78. 启动时开一个「本世」，本世从 BOOTING 起算、不继承上世的任何运行态
- **实现**：`lobos/os/OsInit.kt` — `beginLife`
- **数据**：读 `var/lib/state.json`（读上世的 phase，仅作日志）；写 `SystemDirs.ensureAll`、`PieceScan.rebuild`、`ProgramGroup.reconcile`、`SystemDirs.clearRun`、`var/lib/state.json`（phase=BOOTING）、`Journal.append`
- **依赖**：宿主启动
- **成熟度**：完整可用

### 79. 持久化并切换系统相位，同时把每次变化记进日志
- **实现**：`lobos/os/OsInit.kt` — `current`/`snapshot`/`transition`/`refresh`/`write`/`statusLine`
- **数据**：读 `var/lib/state.json`，`statusLine` 另读 `ProgramStatusHub.snapshot`；写 `var/lib/state.json`（phase/label/previous/at/note/facts/interrupted）、`Journal.append(ctx,"os-phase",…)`
- **依赖**：`ProgramStatusHub.toJson`/`summaryLine`（读 current）、`OsHostService`
- **成熟度**：完整可用。`statusLine` 的 `snap.atMs` 未用于 uptime（`ResidencyStatus` 有自己的 uptimeMs）

### 80. 把 APK 安装 / 卸载的结果接住并落诊断
- **实现**：`lobos/lifecycle/PackageInstallReceiver.kt`
- **数据**：读 `PackageInstaller.EXTRA_*`；写 `var/log/diagnostics.txt` + journal
- **依赖**：诊断页
- **成熟度**：**只做了一半**——只记状态并拉起确认界面，**没有任何回调通知安装发起方**，也没有和 `ProgramIndex` 对账

---

## 九、权限

### 81. 给每一条系统权限标注用途、策略（常驻 / 按需 / ADB）与是否自动 heals
- **实现**：`lobos/permissions/PermissionRoles.kt` — `ROLES`/`declared`/`undeclared`；`lobos/permissions/PermissionCatalog.kt` — `SPECIAL`/`LIFECYCLE`/`PermTier`/`AutoHeal`
- **数据**：不读；写 `var/lib/permission-ledger.json` 的 records
- **依赖**：`os.permissions.roles`、`PermissionLedger.register`
- **成熟度**：**有缺口**——`AutoHeal.YES` 只是标注，**没有任何自动修复代码**去落实它。另见「系统性问题」第 5 条：注释仍把无障碍写作「保活锚」，与 `ResidencyPolicy` 已定死的结论冲突

### 82. 实测每条权限此刻是否真的持有
- **实现**：`lobos/permissions/PermissionCenter.kt` — `isGranted`/`batteryExempt`/`notificationListenerBound` 等
- **数据**：读 `Environment.isExternalStorageManager`、`canRequestPackageInstalls`、`Settings.canDrawOverlays`、`PowerManager.isIgnoringBatteryOptimizations`、`OsAccessibilityService.isReady`、`ScreenCaptureController.isReady`、`NotificationStore.connected`；不写
- **依赖**：权限账本
- **成熟度**：完整可用

### 83. 把权限实测结果落成一份可查的账本
- **实现**：`lobos/permissions/PermissionLedger.kt` — `register`/`write`/`read`/`toJson`/`heldIds`
- **数据**：读 `PermissionRoles.declared()`、`PermissionCatalog.ALL`、`PermissionCenter.isGranted`；写 `var/lib/permission-ledger.json`
- **依赖**：`os.permissions.ledger`；`OsHostService.registerPermissionLedger`（每 10 分钟）
- **成熟度**：完整可用，但 `ATTEMPTS` 字段被保留读取却**无人写入**

### 84. 开关无障碍服务（本机有 WRITE_SECURE_SETTINGS 就直接写，没有就明说）
- **实现**：`lobos/lifecycle/AccessibilityServiceState.kt` — `state`/`isBound`/`ensureBound`/`disable`
- **数据**：读 `Settings.Secure.enabled_accessibility_services`、`accessibility_enabled`、`AccessibilityManager.getEnabledAccessibilityServiceList`；写同左两个 secure 设置
- **依赖**：`os.accessibility.enable/disable/state`；`OsHostService.tick`
- **成熟度**：完整可用（无法静默改时返回 `issued=false` + 明文指引，不借 ADB）

### 85. 读出设备是否被授予设备所有者
- **实现**：`lobos/capability/DeviceOwnerProbe.kt` — `measure`/`adminComponentDeclared`
- **数据**：读 `DevicePolicyManager.isDeviceOwnerApp`、`PackageManager.getPackageInfo(...GET_RECEIVERS)`；不写
- **依赖**：`os.env.status`；宿主节拍（30 分钟 TTL 采样一次）
- **成熟度**：完整可用

### 86. 挡住任何试图改写系统状态目录的路径
- **实现**：`lobos/os/PathGuard.kt` — `rejection`/`integrityRoots`；桥内 `fs.*` 四方法
- **数据**：读待检路径（要求绝对路径）与各完整性根的 canonical path；不写（返回拒绝理由字符串或 null）；拒绝时抛 `CODE_POLICY_DENIED`，`fs.write` 另写目标文件
- **依赖**：`fs.read/write/list/mkdir`、`SystemDirs.ensureAll`
- **成熟度**：**有缺口**——受保护：`etc/`、`usr/`、`var/`、`run/`、`opt/`、`filesDir/adb`、`filesDir/program-verify.js`、`filesDir/runtime.json`；但 `auditPathHint` 只给警告不拦 `/dev`、`/proc`、`/system`（`PathGuard` 只挡自己那几棵树）。一处批内记「无调用点 — 待确认」，另一批给出桥 `fs.*` 为调用方

---

## 十、设备能力

### 87. 截屏并落 PNG 文件或直接回 base64
- **实现**：桥 `ui.screenshot`；`lobos/capability/ScreenCaptureController.kt`；`lobos/bridge/ScreenCaptureService.kt`
- **数据**：读 `MediaProjection`、`VirtualDisplay`、`ImageReader`；写 `filesDir/screenshots/shot-*.png`、`var/lib/screen-capture-grant.json`（授权持久化）
- **依赖**：`ui.screenshot`
- **成熟度**：完整可用（8s 取帧超时、授权可存盘复用）。`saveGrant`/`loadGrant` 在另一批摘要中被列为无有效产出，**待确认**

### 88. 读取设备上的通知列表
- **实现**：`lobos/capability/OsNotificationListenerService.kt`；`lobos/permissions/NotificationStore.kt`；桥 `notif.read`
- **数据**：读 `StatusBarNotification`；写内存 deque（上限 200）
- **依赖**：`notif.read`
- **成熟度**：完整可用（未连接时明确报 `CODE_CAPABILITY_MISSING`）。`NotificationStore` 的三个回调在另一批摘要中被列为无有效产出，**待确认**

### 89. 投递宿主自身通知（调试用）
- **实现**：桥 `notif.post`
- **数据**：写 `NotificationManager` channel `hostbridge_notif`
- **依赖**：程序调试
- **成熟度**：完整可用（通知 id 用时间戳取模，有撞车可能）

### 90. 让程序自己往系统状态栏投递通知，并按分组归并、超额自动清理
- **实现**：`lobos/os/ProgramNotification.kt` — `ProgramNotice` data class、`ProgramNotificationHub.publish`/`clear`/`list`/`groups`/`listJson`/`groupsJson`/`summaryLine`/`ensureChannel`/`prune`
- **数据**：读写内存 `ConcurrentHashMap<programId, ProgramNotice>`（不落盘，重启即丢）；写 Android 通知渠道 `lobos_prog_<group>` 与通知；内存 map 上限 32，超出按时间保留最新并 cancel
- **依赖**：程序桥 API、前台通知摘要
- **成熟度**：**有缺口**——通知不跨进程重启保留；`ensureChannel` 创建后改 `groupLabel` 不生效（渠道已存在即 return）

---

## 十一、兼容层

### 91. 盘点兼容层提供了哪些 Linux 语义、各自什么状态
- **实现**：`lobos/pieces/CompatSemantics.kt` — `ITEMS`/`write`
- **数据**：不读；写 `var/lib/compat-semantics.json`
- **依赖**：开机对账（`BootReconciler`）
- **成熟度**：完整可用。**如实标注**——`isolation` 是 `absent`（与安卓无文件系统边界）、`session`/`signal` 是 `partial`、`exec-bit`/`uid-model` 是 `decided`

### 92. 统计兼容层被降级替换了几类、几次，并报告各驱动是否装上
- **实现**：`lobos/pieces/DriverRegistry.kt` — `ingest`/`account`/`degradations`/`report`/`summary`/`DRIVERS`
- **数据**：读 `var/log/compat-degrade.log`（shim 自报 `LOBOS_DEGRADE` 标记）；写 `var/lib/compat-degradations.json`，清空 degrade.log，首次发生写 journal
- **依赖**：`os.compat.status`；宿主节拍每拍 `ingest`
- **成熟度**：完整可用。`entryOf` 只在 `assetEntry` 以 `bin/` 开头时返回名字，库类驱动返回空串——**待确认**是否有意

---

## 十二、日志与诊断

### 93. 把程序内的事件写成一条日志（状态、端口、作业、设置、启动、OTA 事件）
- **实现**：`lobos/log/Journal.kt`（实现在批外，本批为调用方）；调用点遍布 `OsInit`/`BootReconciler`/`TaskRegistry`/`ProgramStatusHub`/`PortBroker`/`ProgramDir`/`PackageInstaller`/`CatalogClient`/`UnitJobs`/`ProgramSettings`
- **数据**：不读；写日志文件 `var/log/journal/events.jsonl`（路径在批外）
- **依赖**：几乎全部写操作功能点
- **成熟度**：完整可用（`os.journal.metrics` 的 `bySource` 恒单源，见「系统性问题」）

### 94. 把运行期异常堆栈落到一个独立的诊断文件
- **实现**：`RuntimeDiagnostics`（`append`/`stage` 系列；写入点见 `RuntimeEnvironment`、`ProgramDir`、`PrefixProvisioner`、`RuntimeEnvironment.ensure`、`ProgramInstallPipeline`、`LocalExec`/`PtySession`）
- **数据**：读写 `var/log/diagnostics.txt`（按 `stage=` 分类：`prefix`/`supply`/`native-assets`/`pty`/`program-gc`）
- **依赖**：`OsHostService.tick`、安装流水线、开机对账
- **成熟度**：**有缺口**——`node-stderr.log` 被读但**无人写入**（来自 runtime 批次摘要）；一处记 `program-gc` 与 `prefix` 由不同调用方写入

### 95. 把 APK 侧事件与节拍诊断拼成一份可查的诊断轨迹
- **实现**：`lobos/lifecycle/OsHostService.kt` `tick`；`lobos/lifecycle/ResidencyAudit.kt`；`lobos/pieces/DriverRegistry.kt` `ingest`
- **数据**：读 `ProgramStatusHub`、`ProcessLedger`、`ResidencyStatus`、`var/log/compat-degrade.log`；写 `var/log/diagnostics.txt`、`var/log/residency.txt`、`var/log/journal/events.jsonl`
- **依赖**：诊断页、前台通知
- **成熟度**：完整可用。宿主每 15s 一条 `supervisor:id` 诊断，是日志量的大头

---

## 交叉核对发现的系统性问题

以下条目均出自原始审计输出的合并摘要（`════ 批次：runtime+lifecycle ════` 段与其后的交叉核对段落），编号按问题类别归并。**原始输出未携带 Kotlin 源码行号**，因此「出处」给出的是批次段位置与涉及文件；源码行号 — **待补**。

### 一、真值重复（同一事实多处定义）

1. **桥协议版本定义了 3 处** — `lobos/bridge/ApiSpec.kt` 的 `VERSION_*`、`BuildConfig.BRIDGE_PROTOCOL`、`PROTOCOL_MIN`。握手时校验的是其中一份（`ApiSpec`），另两份无交叉校验。
2. **socket 名定义了 2 处** — `lobos/runtime/GuestAdapter.kt` `BRIDGE_SOCKET` 与 `lobos/os/SessionRegistry.kt` `socketName`。
3. **错误码表定义了 2 处，且均为 private** — 无法交叉校验。
4. **CURRENT / FLOOR / PENDING 语义各有一套** — `lobos/os/ProgramDir.kt` 与 `lobos/ota/ProgramOtaStateStore.kt` 各自定义一份指针存储语义，`ProgramDir.store` 为委托关系。
5. **zip 解包有 3 套实现** — `lobos/ota/ProgramArchive.kt` `unzip`、`lobos/os/ProgramDir.kt` `unzipInto`、`lobos/runtime/SupplyProvisioner.kt` `unzipFromFile`。
6. **设备件状态有 2 份账本** — `lobos/os/PieceUpdater.kt` 写 `etc/installed.json`（selectedVersion），`lobos/os/ProgramIndex.kt` 写 `var/lib/program-index.json`（version）。

### 二、读写不对称（有读无写 / 有写无读）

7. **`RuntimeDiagnostics` 的 `node-stderr.log`** — 被读取，但没有任何写入点。
8. **`ResidencyStatus` 的 `tier` 字段** — `lobos/os/ResidencyStatus.kt` `record()` 不写，`detail()` 恒读出 `"?"`。写入方在批外 — **待确认**。
9. **`ProgramStatusHub` 的 `startedAt`** — 无写入点，`aliveMs` 恒 0。
10. **`ProgramStatusHub` 的 `quarantined` 集合** — `publishQuarantined` 维护，`statusOf` 从不读。
11. **`PermissionLedger` 的 `ATTEMPTS` 字段** — 被保留读取，无人写入。
12. **`lobos/os/RegistryStore.kt` 的 `programs` 字段** — `info()` 硬编码 `JSONObject.NULL`，无人写真实数组。
13. **`lobos/pieces/PieceProvisioner.kt` 的 `AssetStatus.Unusable` / `parseErrno` / `err`** — 定义了但无任何构造点。
14. **`lobos/ota/PieceUpdater` 的 `State.updated`** — `states()` 里写死 `false`。

### 三、语义错用（字段名与实际含义不符）

15. **`phase` 字段全是 `desired`** — `os.instances.get`、`instanceJson`、`programJson` 三处的 `phase` 实为期望态，不是真实相位；真实相位在 `OsInit.current`。
16. **`os.journal.metrics` 的 `bySource`** — 恒单源，字段名暗示多源统计。
17. **`os.ports.list` 的 `fixed`** — 恒空。
18. **`os.programs.overview` 的 `upgrade.updateAvailable`** — 硬编码 `false`，与目录 `CatalogClient.list` 的 `upgradable` 字段重复且冲突。
19. **`RUNTIME="runtime"` role** — `lobos/runtime/InstalledRuntime.kt` 按此 role 查运行时，但没有任何代码写入该值，实际靠 `PieceScan.roleOf` 的 `exec` 走兜底链。

### 四、死代码与空接线

20. **`execAsJson` 与 `CapabilityBroker.MAX_SHELL_OUTPUT`** — 无调用点；`shell.*` 四个方法在 `ApiSpec.CANONICAL` 有别名但 `METHODS`/`OS_METHODS` 未实现。
21. **`lobos/quickapp/QuickAppHost.kt` `hide`** — 无有效产出。
22. **`ScreenCaptureController.saveGrant` / `loadGrant`** — 无有效产出或调用方（与功能点 87 所记的「授权可存盘复用」冲突，**待确认**）。
23. **`ApiSpec.SCOPE_SELF`** — 无有效产出。
24. **`Level.WARN`** — 无有效产出。
25. **`ProgramGroup.onBackendExit`** — 被列为无调用方（与功能点 67 所记「由 `InstanceHost.watchExit` 调」冲突，**待确认**）。
26. **`lobos/permissions/NotificationStore.kt` 的三个回调** — 无有效产出或调用方（与功能点 88 冲突，**待确认**）。
27. **`lobos/os/ElfFacts.kt` 的 `origin` 变量** — 算完从未使用，`Dynamic` 无该字段。
28. **`lobos/os/PieceUpdater.kt` 的 `isManagedByUpdate` 与 `ProgramIndex_safeSegment`** — 死代码。
29. **`lobos/os/PieceScan.kt` 的 `SHELL` / `MULTI_COMMAND` 常量** — 无人产出。
30. **`lobos/ota/ProgramOtaVersions` / `PieceUpdater.prune`** — `prune` 保留但无调用点。
31. **`lobos/os/RegistryStore.kt` 的 `HttpURLConnection` / `URL` import** — 未使用。
32. **`AutoHeal.YES` 标注** — 无任何落实它的自动修复代码。
33. **`PieceScan.rebuild` 注释承诺的「扫不到的条目移除」** — 未实现，且 `ProgramIndex` 无按扫不到清理的路径。

### 五、行为不对称（两个相似操作做了不同的事）

34. **`PackageInstaller.uninstall` vs `rollbackToBaseline`** — 前者清索引、删软链、撤图标、放端口；后者只删目录，三样都不做。功能点 50/51。
35. **两个相似操作只做了一个方向** — `PieceScan.verify` 实现了「被换过 / 被删了」，注释里的「登记里有现在没有的」方向未实现。
36. **无障碍服务开关的两条路径** — `AccessibilityServiceState.ensureBound` 走 secure settings 直写，`ResidencyPolicy` 侧却明确「无障碍不是保活锚」，两处对同一能力的定位不一致。

### 六、断链（有生产者无消费者，或反之）

37. **`UnitJobs` 的入队与出队没有闭环** — `enqueue` 写入 `run/unit-jobs.json`，`takeReady` 在一批内无消费者；`ProgramManager.setDesired` 被 `BootReconciler` 当同步写调用（返回 true 但状态没变）。另一批给出闭环端 `SupervisorPool.drainJobs`，闭环是否成立 **待确认**。
38. **`ProgramStatusHub.publishQuarantined`** — 有生产者（`SupervisorPool` 隔离路径）无消费者（`statusOf` 不读）。
39. **`ProgramPackageVerifier.verify`** — 一批记「本批内无调用方」，另一批记为 `ProgramInstaller.install`/`ProgramOtaUpdater` 的门禁，**待确认**。
40. **`ProcessLedger.killTree`** — 一批记「本批内无调用点」，另一批记调用方为 `InstanceHost.reapProgramTree`/`reapOrphanKernel`，**待确认**。
41. **`os.instances.action` 的 `stop` 与 `os.session.stop`** — 有方法定义但被显式拒绝，宿主无按程序独立停止 / 停机的能力；上游 `ProcessLedger.killTree` 因此可能也接不上（见上一条）。
42. **`PackageInstallReceiver`** — 有生产者（系统广播）无消费者：没有回调通知安装发起方，也没和 `ProgramIndex` 对账。
43. **`OsPhaseRule.next` 在 BOOTING/STOPPING 恒返回 null** — 相位没有自动跃迁的入口（**待确认**在批外）。
44. **`PathGuard` 只挡自己那几棵树** — `/dev`、`/proc`、`/system` 由 `auditPathHint` 只警告不拦，对应「程序可见路径挡在完整性区之外」的覆盖面小于其命名暗示的范围。
45. **`lobos/log/Journal` 与 `RuntimeDiagnostics`** — 两条日志通道并存，`node-stderr.log` 一端缺失（见第 7 条）。

---


---

## 我亲自核实的部分（带源码行号）

上一节的条目来自审计 agent 的摘要，缺行号。以下是我（主 agent）用脚本在 commit `c3cbe96` 上实测的结果，可直接复核。

### 规模与耦合

| 包 | 文件 | 行 | 被几个包依赖 | 符号数 |
|---|---|---|---|---|
`os` | 32 | 4796 | **12** | 282 |
`log` | 4 | 369 | **9** | 13 |
`lifecycle` | 8 | 1218 | 6 | 45 |
`runtime` | 12 | 2684 | 5 | 85 |
`bridge` | 3 | 1942 | 4 | 47 |
`permissions` | 5 | 415 | 4 | 62 |
`pieces` | 3 | 661 | 4 | 17 |
`quickapp` | 9 | 852 | 4 | 40 |
`capability` | 3 | 283 | 3 | 9 |
`ota` | 11 | 1741 | 3 | 53 |
`ui` | 1 | 32 | 0 | 1 |
（根目录） | 2 | 234 | 0 | 14 |

**循环依赖 14 对**（互为 import）：

```
os      ↔ runtime      10 / 39     ← 最重
os      ↔ ota           5 / 20
os      ↔ quickapp      2 / 19
lifecycle ↔ os         17 /  3
os      ↔ log           4 /  9
os      ↔ pieces        3 /  8
bridge  ↔ runtime       5 /  1
bridge  ↔ lifecycle     3 /  2
ota     ↔ runtime       3 /  2
bridge  ↔ capability    3 /  1
pieces  ↔ runtime       1 /  3
lifecycle ↔ permissions 2 /  1
capability ↔ permissions 1 / 1
lifecycle ↔ runtime     1 /  1
```

### 真值重复 · 精确行号

**1. 桥协议版本 —— 两套独立值，无交叉校验**
```
bridge/ApiSpec.kt:17          const val VERSION_MAJOR = 1
bridge/ApiSpec.kt:18          const val VERSION_MINOR = 0
bridge/CapabilityBroker.kt:301  if (clientProtocol < PROTOCOL_MIN)
bridge/CapabilityBroker.kt:305  "…宿主支持 [" + PROTOCOL_MIN + "," + BuildConfig.BRIDGE_PROT…
```

**2. 错误码 —— 8 个常量全在 `CapabilityBroker` 私有区**
```
bridge/CapabilityBroker.kt:1579  CODE_CAPABILITY_MISSING   = -32001
bridge/CapabilityBroker.kt:1580  CODE_INVALID_PARAM        = -32602
bridge/CapabilityBroker.kt:1581  CODE_METHOD_NOT_FOUND     = -32601
bridge/CapabilityBroker.kt:1582  CODE_NOT_IMPLEMENTED      = -32002
bridge/CapabilityBroker.kt:1583  CODE_SESSION_MISSING      = -32004
bridge/CapabilityBroker.kt:1584  CODE_PROTOCOL_UNSUPPORTED = -32006
```
`ApiSpec` 里另有一份同名码表，两份都是 `private` —— **无法交叉校验**。

**3. 解包 zip —— 4 个实现**
```
ota/ProgramArchive.kt:7            fun unzip(zip: File, dest: File)              ← 正宗
os/ProgramDir.kt:155               fun unzipInto(zip: File, dest: File)          ← 委托
os/ProgramDir.kt:219               private fun unzip(…) = ProgramArchive.unzip     ← 转发
runtime/SupplyProvisioner.kt:169   internal fun unzipFromFile(zip, dest)          ← 独立实现
runtime/SupplyProvisioner.kt:191   internal fun unzipInto(zipBytes: ByteArray)    ← 又一个
```

**4. socket 名 —— 单一源，但有两处转发**
```
runtime/GuestAdapter.kt:39        const val BRIDGE_SOCKET = "lobos_hostbridge"   ← 唯一源
bridge/CapabilityBroker.kt:77     private var socketName = GuestAdapter.BRIDGE_SOCKET
bridge/CapabilityBroker.kt:1548   const val SOCKET_NAME = GuestAdapter.BRIDGE_SOCKET  ← 别名
```

### 读写不对称 · 精确验证

| 字段 | 写 | 读 | 后果 |
|---|---|---|---|
`ResidencyStatus.tier` | **无** | 有（`detail()`） | 恒读出 `"?"` |
`PermissionLedger.ATTEMPTS` | **无** | 有（`readAttemptsRaw` 原样写回） | 永远是空数组 |

`ATTEMPTS` 的成因可追溯：记录取权尝试的 `readAll()` 在清判据体系时被删（它依赖已删的 `SilentAttempt` 类型），写入方随之消失、读取方留下。

`ProgramStatusHub.startedAt` **不是**不对称 —— 子代理报错了，实际有写有读。

### 落盘数据：62 个文件，按 Linux 语义分布

```
/usr      对外呈现的程序环境     9 类
/opt      程序本体              9 类
/var/lib  长期状态             11 类
/var/log  日志                 10 类
/run      运行态                2 类
/etc      配置                  3 类
```

目录结构与 Linux 的 FHS 语义完全一致 —— **这一层设计是对的**，问题在上层的 Kotlin 组织。

### 当前组件面（AndroidManifest，10 个）

```
service   .lifecycle.OsHostService                 foregroundServiceType=specialUse
service   .ui.StatusTileService                    permission=BIND_QUICK_SETTINGS_TILE
service   .capability.OsNotificationListenerService permission=BIND_NOTIFICATION_LISTENER_SERVICE
service   .lifecycle.OsAccessibilityService         permission=BIND_ACCESSIBILITY_SERVICE
service   .bridge.ScreenCaptureService             foregroundServiceType=mediaProjection
receiver  .lifecycle.BootReceiver
receiver  .lifecycle.PackageInstallReceiver
receiver  .kernel.power.DozeBackstopReceiver
activity  .quickapp.QuickAppLaunchActivity         ★ LAUNCHER
application .OsApplication
```

**没有任何界面** —— UI 清空后的状态。

## 转写完整性

- 原始输入为 5 个并行审计 agent 的合并输出，其中 **os 批次完整**（48 个功能点 + 依赖层次清单）、**runtime+lifecycle 批次以摘要形式给出**（其自报 236 个功能点 / 七域）、**bridge+permissions+capability 批次完整至「七、快应用」中的第 6 条**。
- 输入文件本身在 `DesktopIcons` 条目中途被截断（保留约 50KB / 约 81KB，缺 31094 字符）。因此 **「七、快应用」之后的第 3–5 个 agent 内容、原始输出的「日志与诊断」与「交叉核对」两节，均未进入本文档**；上表的交叉核对条目全部来自 runtime 批次摘要中已内嵌的那一节。
- 未经输入佐证的内容未做补写；输入标「待确认」处一律照实保留。

---

## 补读批次（带源码行号）

原始审计输出被截断约 38%，以下三批直接从源码补读，**每条带行号**。

### 装机与 OTA（ota 包 11 文件）

| 功能点 | 实现 | 关键行 | 成熟度 |
|---|---|---|---|
从设备侧 /assets 读 OTA 通道配置，允许不改 APK 就换源 | `ota/ProgramOtaUpdater.kt` — `loadConfig` | :46 读 `etc/channel.json`；:58 回退 assets；:83 非 https 一律拒 | 完整 |
拉 manifest 并与本地状态裁定是否安装 | `ota/ProgramOtaUpdater.kt` — `checkAndUpdate`/`checkAndUpdateNet` | :89/:103；:95 持 wifi 锁；:161 预算耗尽则不下载 | 完整 |
健康通过才提交序列号，失败可撤销以便同版重装 | `ota/ProgramOtaUpdater.kt` — `promotePendingSequence`/`dropPendingSequence` | :237/:250，写 `pendingSequence` 于 :213 | 完整 |
为灰度分桶生成稳定安装标识 | `ota/ProgramOtaUpdater.kt` — `installId` | :280，读写 `etc/install-id.json` | 完整（写失败退化为临时 UUID）|
裁定 OTA 是否允许安装（过期/重放/回退/灰度） | `ota/OtaPolicy.kt` — `evaluate` | :31；:32-58 必填校验；:59-64 重放拒；:68-80 降级需显式 allowDowngrade | 完整 |
按分段比较版本号，判断新旧与是否低于下限 | `ota/ProgramOtaVersions.kt` — `compare`/`isNewer`/`isBelowFloor` | :4/:23/:26 | 完整 |
持久化 CURRENT/FLOOR/PENDING 三个指针 | `ota/ProgramOtaStateStore.kt` | :16-49 原子写；:31 setFloor 只升不降；:51 rollbackTo 不校验入口 | 完整 |
断点续传下载并校验 SHA-256 | `ota/ResumableDownloader.kt` — `download` | :24；:74 Range/206/416/200；:119 sha256 不符删半包；默认 32MB 上限 :13，重试 3 次 :14 | 完整 |
识别残留半包 | `ota/ResumableDownloader.kt` — `isPartialFile` | :20 | 完整 |
安全解包，拒绝目录穿越 | `ota/ProgramArchive.kt` — `unzip` | :7；:15-17 canonical 前缀校验；:28 空包抛错 | 完整（⚠️ 调用关系待确认）|
安装并登记程序包（快应用），fail-closed | `ota/ProgramInstaller.kt` — `install` | :35；:53-59 清单声明 signature 无文件则拒；:87 低于 FLOOR 拒；:184-215 失败回滚 | 完整 |
统一「程序」与「件」两种落位路径 | `ota/ProgramInstallPipeline.kt` — `install` | :61；件路径 :161 `installComponent`；:192 OTA 以 COMPONENT 调用 | **有缺口**：`installComponent` 用 SupplyProvisioner.unzipFromFile 而非 ProgramArchive；`depsOf`(:312) 定义未被调 |
按 ELF 段事实铺齐依赖库 | `ota/ProgramInstallPipeline.kt` — `satisfyElfDeps` | :348；:334 linkerCanFind；:412 resolveRunPath | 完整 |
只信包内元信息登记件形态 | `ota/ProgramInstallPipeline.kt` — `PieceMeta` | :280/:303 读 component-meta.json | 完整 |
给件建入口软链（含 package.json bin 别名） | `ota/ProgramInstallPipeline.kt` — `linkEntry` | :460；:463 先 ExecBits.apply；:490 Os.symlink | 完整 |
判定运行时启动前程序包是否就绪 | `ota/ProgramOtaResolution.kt` — `resolve` | :18，三态 ABSENT/INCOMPLETE/READY | 完整 |
一次性跑完设备端 OTA 自检 | `ota/ProgramOtaSelfCheck.kt` — `run` | :17；:86-105 本地状态段仅单程序时可读；**:143 policy 项恒 ok=true** 不论裁定结果 | **有缺口** |
把自检条目汇总成报告与总判定 | `ota/SelfCheckReport.kt` — `format`/`verdict` | :47/:35，三态区分 :29-32 | 完整 |

### 快应用与兼容层（quickapp + pieces 12 文件）

| 功能点 | 实现 | 关键行 | 成熟度 |
|---|---|---|---|
把 dimina 运行时注册为 JS 扩展模块 | `quickapp/QuickAppHost.kt` — `registerCapabilities` | :20；OsApplication.kt:50 调用 | 完整 |
校验快应用包结构与 appId 一致性 | `quickapp/QuickAppPackage.kt` — `check` | :15；:19-31 六类校验 | 完整 |
把后端地址与入口页写进前端 config.json | `quickapp/QuickAppPackage.kt` — `withEndpoint`/`withEntry` | :47/:38；:49-53 覆写 backend 不保留原值 | 完整 |
前端目录打包 zip 并装入 dimina | `quickapp/QuickAppHost.kt` — `install` | :37；:39/:43/:48/:63 四步各有独立失败原因 | 完整 |
打开一个程序的前端界面 | `quickapp/QuickAppHost.kt` — `open` | :95；:97 先 ensureBackendRunning；:122 startMiniProgram | 完整 |
关闭/隐藏前端界面 | `quickapp/QuickAppHost.kt` — `close`/`hide` | :129/:131 | **有缺口**：`hide` 全仓零调用（已核实）|
探测 manifest 是否声明快应用前端 | `quickapp/QuickAppRegistry.kt` — `isQuickApp` | :17，判 `ui.type=="quickapp"` :22 | 完整 |
把图标/名字/端口抄进程序注册表 | `quickapp/QuickAppRegistry.kt` — `register` | :29；:37-49 写 uiPackage/uiName/httpPort… | 完整 |
列出全部已登记的快应用程序 | `quickapp/QuickAppRegistry.kt` — `listed` | :57 | **有缺口**：全仓零调用 |
安装/OTA 完成时自动配对前后端 | `quickapp/QuickAppBinder.kt` — `bindIfQuickApp` | :11；调用点 ota/ProgramInstaller.kt:100,:221 | 完整 |
打开前端前确保后端已在跑 | `quickapp/ProgramGroup.kt` — `ensureBackendRunning` | :46；:50 已在跑直接返回；:52 STOPPED 不擅自拉起 | 完整 |
前端关闭时按声明决定后端是否跟着停 | `quickapp/ProgramGroup.kt` — `shouldStopBackendOnUiClose` | :73；:76 keep-alive 之外一律停 | **⚠️ 与 `ManifestSchema.ON_CLOSED` 默认值相反，待确认** |
后端进程退出时把前端界面收掉 | `quickapp/ProgramGroup.kt` — `onBackendExit` | :116；调用点 runtime/InstanceHost.kt:674 | 完整 |
开机核对「登记了前端」与「dimina 里真装了」 | `quickapp/ProgramGroup.kt` — `reconcile` | :133；调用点 os/OsInit.kt:71；**:142 只记日志不触发重装** | **只做了一半** |
把快应用的 JS 调用路由到宿主事件 | `quickapp/LobosBridge.kt` — `handle` | :16；:17-27 分派表 | 完整 |
让快应用调宿主能力方法（带作用域隔离） | `quickapp/LobosBridge.kt` — `invoke` | :62；:71 SCOPE_SYSTEM 拒；:83 转发 invokeLocal | 完整 |
让快应用增删桌面图标并查状态 | `quickapp/LobosBridge.kt` | :95/:110/:122 | 完整 |
查询自己后端的 HTTP 地址 | `quickapp/LobosBridge.kt` — `backendEndpoint` | :86 | 完整 |
把快应用固定到桌面并管理生命周期 | `quickapp/DesktopIcons.kt` — `request` | :41；:77 用 disableShortcuts；id 前缀 `qa_` :16 | 完整 |
追踪前台 Activity 判定「前端结束」 | `quickapp/Foreground.kt` — `attach` | :24；OsApplication.kt:25 调用；:53 从 intent extra 取 id 不猜 | 完整 |
从桌面图标点开一个程序 | `quickapp/QuickAppLaunchActivity.kt` | :10；:37 EXTRA_ID；:26 失败给 Toast | 完整 |
把编译期系统件铺到设备并逐件判定 | `pieces/PieceProvisioner.kt` — `prepare` | :149；:200 verifyInternal 四问；:87-88 区分「APK 有未解压」vs「打包期丢」 | 完整 |
给出程序运行时的库搜索路径 | `pieces/PieceProvisioner.kt` — `libSearchPath` | :240；:234-237 明确不含 nativeLibraryDir | 完整 |
登记 POSIX 兼容驱动的替代语义清单 | `pieces/DriverRegistry.kt` — `DRIVERS` | :24，6 条驱动 | 完整 |
统计兼容层实际发生过哪些降级替换 | `pieces/DriverRegistry.kt` — `ingest` | :118；OsHostService.kt:152 周期调；:123 统计后清空日志 | 完整 |
导出驱动安装情况 + 降级统计报告 | `pieces/DriverRegistry.kt` — `report` | :161；:168-170 查注册表而非文件在否 | 完整 |
如实标注与 Linux 的兼容语义对齐情况 | `pieces/CompatSemantics.kt` — `write` | :37 ITEMS 11 项；:162 写 json | 完整（**只写不读**）|

**遗留缺口（源码事实）**
- `quickapp/QuickAppHost.kt:131` `hide` 零调用（已核实）
- `quickapp/QuickAppRegistry.kt:57` `listed` 零调用
- `quickapp/ProgramGroup.kt:142` reconcile 只报告不修复
- `pieces/PieceProvisioner.kt:243/:272` `parseErrno`/`err` 定义未调用 → `AssetStatus.NotExecutable` 的 errnoHint 恒 null（:226）

### 日志与宿主（log + ui + 根目录）

| 功能点 | 实现 | 关键行 | 成熟度 |
|---|---|---|---|
把带序号的启动/运行事件追加写 JSONL 并 fsync | `log/Journal.kt` — `append` | :109-129；:122-126 flush + sync | 完整 |
把人类可读记录按成功/失败自动分级 | `log/Journal.kt` + `log/Level.kt` | :100-107；Level.of :9-13 | 完整 |
把系统退出原因码翻译成死因分类枚举 | `log/Journal.kt` — `Reason.fromExitInfo` | :36-62，13 个取值 | 完整 |
日志超 512KB 时归档并只留最近 500 行 | `log/Journal.kt` — `rotate` | :131-140；:132-133 ≤500 行时不归档 → 大文件继续增长 | **有缺口** |
读回最近 N 条事件 | `log/Journal.kt` — `events` | :142-167；:166 每次全量 readLines 后 takeLast | **有缺口**（非索引查询）|
读系统历史退出记录、去重后转成死因日志 | `log/KillAudit.kt` — `auditOnce` | :34-64；:41-42 读 32 条；:36-39 API<R 记 UNREADABLE | 完整 |
把游标存成「时间戳 pid」避免重复记录 | `log/KillAudit.kt` | :86-96 | **有缺口**：无回退保护，系统清空退出史会导致历史反复重判为「新」|
从日志找出本进程死因并生成归因文本 | `log/KillAudit.kt` — `attribution` | :66-80；:70 靠 detail 里 `process=` 前缀匹配，格式强耦合 | 完整 |
把「读不到退出史」也记为可观测事件 | `log/KillAudit.kt` — `reportUnreadable` | :82-84，pkg 参数冗余未用 | 完整 |
把最近事件按日志级别分桶统计 | `log/Exporter.kt` | :47-51 | **有缺口**：**`Level.of` 只返回 INFO/ERROR，WARN 枚举值无产生路径，warn 计数恒 0**（已核实）|
采集设备型号/系统版本/ABI 作为日志包头 | `log/Exporter.kt` — `deviceInfo` | :34-40 | 完整 |
把事件/诊断/设备信息打包成带 schema 的 JSON | `log/Exporter.kt` — `exportBundle` | :42-66；**:44 diagnostics 字段第二次调 Journal.events 而非读 RuntimeDiagnostics，MAX_DIAG 形同虚设** | **有缺口** |
把日志包写到缓存目录 | `log/Exporter.kt` — `writeToCache` | :68-72；**非原子写**，大包可能半截 | 完整（无上限保护）|
记录启动各阶段的诊断行（人类可读 + 结构化双写） | `RuntimeDiagnostics.kt` — `append` | :55-70；:86-87 两份文件；:89-93 Journal | 完整 |
按阶段读取最新一条诊断事件 | `RuntimeDiagnostics.kt` — `latestByStage` | :97-128；:123 硬编码 limit=400 | 完整（早期阶段可能被挤出）|
读取/清空人类可读诊断日志 | `RuntimeDiagnostics.kt` — `read`/`clear` | :130-132 / :49-53 | 完整 |
单独捕获并限长保存 Node stderr | `RuntimeDiagnostics.kt` — `recordNodeStderr` | :134-145；:136 空文本静默丢弃 | 完整 |
在系统快捷设置磁贴显示状态并点击拉起宿主 | `ui/StatusTileService.kt` | :10-31 | **只做了一半**：**:25 状态硬编码 STATE_ACTIVE 不反映真实存活；无 onTileRemoved，开/关语义缺失** |
应用启动时建渠道/初始化快应用/拉起宿主/注册唤醒 | `OsApplication.kt` — `onCreate` | :22-30 五步；:37-41 dimina 参数硬编码无开关；:60 CatalogClient.refresh 失败被吞**无日志** | 完整（两处静默）|
dimina 初始化与能力桥注册 | `OsApplication.kt` — `initQuickAppRuntime` | :32-56；:54 成功事件无条件写（即使 registerCapabilities 失败）| 完整（语义偏乐观）|
开机/解锁/亮屏时确保宿主在跑 | `OsApplication.kt` — `registerWakeupEdges` | :64-75 | **有缺口**：**:74 无 flag 的 registerReceiver，API 34+ 很可能注册失败，且被 runCatching 吞掉无日志**（已核实）|
把日志包导出为可分享的 JSON 文件 | `log/Exporter.kt` 全套 | :42-72 | **有缺口**：本批范围内**未见任何调用方** |

---

## 补读新增的系统性问题（在原 45 条之外）

| # | 问题 | 位置 |
|---|---|---|
46 | **`selfCheck` 的 policy 项恒为 ok=true** —— 不论 `OtaPolicy` 裁定结果如何，只体现在文案 | `ota/ProgramOtaSelfCheck.kt:143` |
47 | **`installComponent` 用了第三套解包实现** —— `SupplyProvisioner.unzipFromFile` 而非正宗的 `ProgramArchive.unzip` | `ota/ProgramInstallPipeline.kt` 件分支 |
48 | **`onUiClosed` 默认值两处相反** —— `ManifestSchema` 默认 `keep-alive`，`ProgramGroup` 未声明时按 stop-with-ui | `os/ManifestSchema.kt:163` vs `quickapp/ProgramGroup.kt:76` |
49 | **API 34 动态 receiver 无 EXPORTED/NOT_EXPORTED flag** —— 且异常被 runCatching 吞掉无日志 | `OsApplication.kt:74` |
50 | **`Level.WARN` 枚举值无产生路径** —— `Level.of` 只返回 INFO/ERROR，导致日志包 warn 计数恒 0 | `log/Level.kt:9-13` |
51 | **`Exporter` 的 diagnostics 字段取错源** —— 二次调 `Journal.events` 而非 `RuntimeDiagnostics.events`，`MAX_DIAG` 形同虚设 | `log/Exporter.kt:44` |
52 | **磁贴状态硬编码 `STATE_ACTIVE`** —— 不反映宿主服务真实存活；点击只有拉起、无开/关语义 | `ui/StatusTileService.kt:25` |
53 | **`Journal.rotate` 有阈值死角** —— 行数 ≤500 但 >512KB 时直接 return 不归档，文件继续增长 | `log/Journal.kt:132-133` |
54 | **`ProgramGroup.reconcile` 只报告不修复** —— 注释说「留待下次装包时补」，但它自己不触发重装 | `quickapp/ProgramGroup.kt:142` |
55 | **`KillAudit` 游标无回退保护** —— 系统清空退出史导致最新记录变旧，历史会被反复判为「新」 | `log/KillAudit.kt:86-87` |
56 | **三处静默失败无日志** —— `CatalogClient.refresh`（OsApplication.kt:60）、dimina 注册（:54 语义偏乐观）、`recordNodeStderr` 空文本（RuntimeDiagnostics.kt:136） | 见各处 |

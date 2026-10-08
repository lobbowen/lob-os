# 分发体系现状核对与执行方案

> 这份文档记录的是**核实过的事实**，不是设计构想。每条结论都能追到仓内某个文件。
> 核实日期：2026-10-08 · 对应 commit：见 `git log -1`

## 一、已确立的共识（本方案的前提）

1. **只有一条分发路径：OTA**。清单全部由控制面板侧持有与下发，不在 APK 里。
2. **基础环境件**提供系统基础能力，出厂内置（用户不必下载），**但仍走同一条 OTA 路径** ——
   区别只是「首发那份来自 APK」，不是「另一类东西」。
3. 基础环境件有版本号，未来更新**不需要出新 APK**，只走 OTA 换版本。
4. **系统里不存在「APK 原生件」这一类**。现有代码里的这个概念是混乱的产物，要拆掉。
5. **一张注册表**登记系统里所有可安装的东西（程序 / 运行时 / 工具 / 库），
   每件写明形态、落位、入口、版本。系统逻辑从它读，不从代码里的硬编码集合读。

## 二、事实核对（逐条可追）

### 2.1 APK 内只有锚点，没有清单本体

| 文件 | 大小 | 内容 | 有读者 |
|---|---|---|---|
| `assets/supply/channel.json` | 553 B | `baseUrl` + 三份清单的**文件名** | ✅ `SupplyProvisioner.channelAnchor` |
| `assets/program-feed.json` | 192 B | `baseUrl` + `program-manifest` 名 + 预算 | ✅ `OtaPolicy` / `ProgramOtaUpdater` / `ProgramOtaSelfCheck` |
| `assets/supply/seed.json` | 1.2 KB | 一份「基础环境件」清单（7 条） | ❌ **无任何读者** |
| `assets/supply/component-public.pem` | 113 B | 信任根 | ✅ 3 处 |

清单本体（`program-manifest.json` / `component-manifest.json` / `native-manifest.json`）**都不在 APK 里**，走 OTA 下载。✅ 与共识 1 一致。

### 2.2 三处问题

**① `program-feed.json` 是 `channel.json` 的旧格式残留**

`ProgramOtaUpdater.parseConfig()`（第 65-82 行）**优先读 `channel.json` 的 `manifests.program`**，
`program-feed.json` 只在 `channel.json` 缺该字段时回退（`?:` 运算符）。

所以不是「两份并立的真相源」，而是**新旧两代共存、两代都能跑**。
问题在于旧格式仍在 APK 里，且 `ProgramOtaUpdater` 有一整套 `program-feed-state-*.json`
状态文件名（第 231、284 行）与 `program-feed-install.json` —— 命名仍绑在旧概念上。

删除旧格式前要先确认那些状态文件的迁移路径（设备升级后旧状态读不读）。

**② `seed.json` 是一份死表，且内容与共识矛盾**

- 无任何代码读它
- 它声明 `bundled` 里是「系统基础环境（APK 内置）」，
  并写「运行时（node/python）与工具/产品**一律经商店安装**」
- 这与共识 2、3 冲突：运行时与工具同样走 OTA 到控制面板，且基础环境件不是「APK 内置」这一类

**③ `component-manifest.json` 无设备端消费者**

13 件（`sysroot` `make` `cmake` `pkg-config` `jq` `curl` 等）在构建侧照常签名发布，
但 `container/` 全量 grep 不到任何代码消费它。**发了没人读。**

### 2.3 动态路径写了一半

`SupplyProvisioner` 11 个能力中，**4 个无调用者**：

| 能力 | 状态 | 它本该做什么 |
|---|---|---|
| `versionDir` | ❌ 无调用者 | 建 `$PREFIX/lib/toolchain/<id>/<版本>/` |
| `unzipInto` | ❌ 无调用者 | 把件解压到落位目录 |
| `selectVersion` | ❌ 无调用者 | 记录「这件当前选哪个版本」 |
| `selectedVersion` | ❌ 无调用者 | 读回记录 |

这四个正是「**首次安装一个 zip 件**」的实现。而现在：

| 路径 | 谁装 | 状态 |
|---|---|---|
| 程序 / node（`ProgramIndex`） | `ProgramInstallPipeline` | ✅ 完整 |
| 原生件 OTA 更新 | `NativeAssetUpdater`（换软链） | ✅ 完整 |
| **zip 件首次安装** | **无人** | ❌ 断在这里 |

### 2.4 能力下放有 3 个互不相认的清单

| 清单 | 位置 | 管谁 |
|---|---|---|
| `BIN_IDS` / `LIB_IDS` | `NativeAssetRegistry.kt:124,129` | 9 个 id |
| `ProgramIndex` | 设备端运行时数据 | node + 应用程序 |
| `BUSYBOX_APPLETS` | `PrefixProvisioner.kt:14-17` | 18 个软链名 |

「系统里有哪些能力、装到哪」由这三处代码里的硬编码决定，不由数据登记。

### 2.5 「APK 原生件」这个类别的具体内容

`NativeAssetRegistry.kt` 13 条 = 11 条 `CAPABILITY` + 2 条顶层：

| 行 | id | libName | version | buildTier |
|---|---|---|---|---|
| 9 | `libcxx` | `libc++_shared.so` | — | 未声明 |
| 20 | `node` | `libnode.so` | — | 未声明（note 自述「APK 内不该有这份」） |
| 35 | `bash` | `libbash.so` | 5.2.15 | upstream |
| 43 | `ripgrep` | `liblobosrg.so` | **—** | upstream |
| 50 | `flock` | `liblobosflock.so` | — | self-c |
| 56 | `posix` | `liblobosposix.so` | — | self-c |
| 62 | `ptyprobe` | `liblobosptyprobe.so` | — | self-c |
| 68 | `ptysession` | `librivospty.so` | — | self-c |
| 80 | `busybox` | `libbusybox.so` | 1.36.1 | upstream |
| 92 | `zlib` | `libz.so` | 1.3.2 | upstream |
| 99 | `openssl` | `libssl.so` | 3.6.3 | upstream |
| 106 | `crypto` | `libcrypto.so` | **—** | upstream |
| 112 | `curl` | `libcurl.so` | 8.22.0 | upstream |

两处版本号缺失（`ripgrep`、`crypto`）导致它们**进不了 OTA 清单**
（`publish-native-manifest.js:117` 过滤无 version 的条目）。

## 三、执行方案

分四层，每层做完可独立验证，不跨层混改。

### 第 1 层：把 APK 里的死表与旧格式清掉

目标：APK 里只留**一个锚点** + 信任根。

1. 删 `assets/supply/seed.json`（无读者，且内容与共识矛盾）
2. `program-feed.json` 退场：`ProgramOtaUpdater.parseConfig()` 只读 `channel.json`，
   并把状态文件名从 `program-feed-state-*` 改成 `ota-state-*`（先做旧名回退读取，
   读不到再读新名 —— 设备升级不能丢已装状态）
3. 同步改 `OtaPolicy`（第 72 行文案）与 `ProgramOtaSelfCheck`（第 23 行文案）里
   提到 `program-feed.json` 的地方
4. 门禁：APK assets 里只允许存在一份锚点（防再分叉）

**验收**：APK 打包后 assets 只有 `channel.json` + `component-public.pem` + `ca-bundle.pem` + `node/`

### 第 2 层：注册表落地（一张表，登记全部件）

目标：系统里有哪些能力、装到哪、什么版本，由**数据**决定，不由代码里的集合决定。

**注册表结构**（位置待定，见下方待定项）：

```
schema
件清单: [
  { id, kind,            // 程序 / 运行时 / 工具 / 库
    entry,               // 可执行入口（bin/node）
    landing,             // 落位规则
    bundled,             // true = 出厂内置（首发那份来自 APK），false = 需下载
    updatable,           // 能否 OTA 换版本
    version,             // 当前版本
    deps }               // 依赖哪些件
]
```

**消费方全部改为读它**（不再读代码里的硬编码集合）：

| 现状 | 改为 |
|---|---|
| `BIN_IDS` / `LIB_IDS`（`NativeAssetRegistry.kt:124,129`） | 读注册表的 `landing` |
| `BUSYBOX_APPLETS`（`PrefixProvisioner.kt:14-17`） | 读注册表里 busybox 的 applet 列表 |
| `NodeRuntime.path()` 三级回退 | 读注册表定位 |

**门禁**：
- 每件只能登记一次（重份判红）
- 每件必须有 `entry` 或明确声明「无入口」
- 代码里不得再出现硬编码能力清单（`BIN_IDS`/`LIB_IDS`/`BUSYBOX_APPLETS` 三处清空）
- `bundled: true` 的件必须在 `NativeAssetRegistry` 有对应条目（交叉核对）

### 第 3 层：接上「首次安装 zip 件」这段断路

目标：`SupplyProvisioner` 那 4 个无调用者的函数被真正用起来。

**这四个函数是完整实现，不需要新写**（已核实）：
- `versionDir(ctx, id, version)` → `$PREFIX/lib/toolchain/<id>/<版本>/`
- `unzipInto(zipBytes, dest)` → 解压（含路径穿越防护）
- `selectVersion(ctx, id, version)` / `selectedVersion(ctx, id, fallback)`
  → 读写 `filesDir/toolchain-selected.json`，那本就是「件 → 当前版本」的记录表

**新逻辑**（落在 `SupplyProvisioner`，因为那四个函数就是为它准备的）：

```
装一个件(manifest 里的条目):
  1. 验签（复用 verifyEd25519 + component-public.pem）
  2. 落位到 versionDir(ctx, id, version)
  3. 解压（unzipInto）
  4. 建入口软链（entryLink）
  5. 记 selectVersion(ctx, id, version)
```

**更新**：同一路径，版本不同则重装 + 改软链。`NativeAssetUpdater` 现在那套
「从软链目标反推当前版本」的做法可以保留，但**版本记录的真相源应统一到
`toolchain-selected.json`**（现在它靠解析软链路径段数反推，是推断而非记录）。

**验收**：`component-manifest.json` 里的 13 件能被装上并被程序用

### 第 4 层：拆掉「APK 原生件」这个类别

目标：基础环境件与其他件走同一条路，只是 `bundled: true`。

1. `NativeAssetRegistry.kt` 13 条按注册表重建 —— 每条对应一个注册表条目
2. `crypto` / `ripgrep` 补版本号（否则永远进不了 OTA 清单）
3. `node` 那条删除 —— 它的 note 已自述「APK 内不该有这份」，正路是 `ProgramIndex`
4. `PrefixProvisioner.provision()` 的「从 jniLibs 拷」改为「从注册表 + 落位规则」，
   首次内置仍从 APK 取源（这是「bundled」的实现，不是新类别）
5. 门禁：代码里不再出现 `nativeLibraryDir` 作为能力来源的判断

## 四、依赖关系

```
第 1 层（清死表）  ← 独立，可先做
      ↓
第 2 层（注册表）  ← 需要第 1 层的锚点合并先定
      ↓
第 3 层（接断路）  ← 需要第 2 层的注册表提供清单内容
      ↓
第 4 层（拆类别）  ← 需要第 2、3 层都通了
```

**不能在第 2 层之前动第 4 层** —— 否则又会出现「两套并存」，
那正是当前混乱的成因。

## 五、当前阻塞项（需要定）

| # | 待定 | 为什么必须先定 |
|---|---|---|
| 1 | **APK 现在构建不出来** | `build-base-libs.sh` 里 openssl 的 `$ORIGIN` 被 perl 吃掉（RUNPATH 实测为 `RIGIN`）。第 1 层要验证 APK 打包，得先能出 APK。openssl 的真因待 CI 日志的字节级取证（已在 `check_lib` 加 `od -c`）。 |
| 2 | **注册表放哪、叫什么** | `scripts/registry.json`？还是你有既定位置？它要被构建期生成、编译进 APK assets、被 Kotlin 读 —— 位置决定生成器与消费代码的写法。 |
| 3 | ~~`node-pty` 谁编的~~ **已查清** | `container/native/` 下**没有它的 C 源码**。它不是自研 C，而是 node-pty 的 `pty.node`：`build-native-capabilities.sh` 第 219-228 行用 `node-gyp rebuild --arch=arm64` 编出来，改名成 `liblobospty.so` 放进 jniLibs。**soft 档**（失败只降级不判红）。它是 node 的原生扩展，与 `librivospty.so`（`container/native/d3/pty-session.c`，自研 C）是两件事。注册表里要登记为「依赖 node 的 self-c 件」。 |

## 六、本方案不做的事

- 不新增分发通道（只有 OTA 一条）
- 不在 APK 里放任何清单本体
- 不为「最小改动」而妥协结构 —— 第 2 层不落地，第 3、4 层都建在流沙上
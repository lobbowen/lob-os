# OTA 三通道统一规格

**状态**：规格已定，实现待对齐。

组件链与原生链**已经共用同一套原语**（`SupplyProvisioner.*`：验签、下载、大小上限、落位）。
程序链是唯一的例外，它自己起了一套。

本文档规定三条链必须一致的项。**任何一条链出现不同的做法，都算缺陷。**

---

## 一、通道定义

一个 OTA 机制，三个通道，由控制面板驱动。

| 通道 | 筐 | 清单 | 消费方 |
|---|---|---|---|
| 1 第三方应用 | tool + 第三方 | `program-manifest.json` | `ProgramOtaUpdater` → `ProgramInstallPipeline` |
| 2 系统组件 | runtime + tool | `component-manifest-2.json` | `CatalogClient` → `SupplyProvisioner` |
| 3 系统更新 | base | `native-manifest.json` | `NativeAssetUpdater` |

---

## 二、必须一致的七项

### 1. 签名：ed25519，覆盖清单的规范化形式

三条链都做 ed25519 验签，用同一把公钥。差别只在**签名的载体**：

| 通道 | 载体 | 验签对象 | 状态 |
|---|---|---|---|
| 2、3 | 独立 `.sig` 文件 | 清单正文（原字节） | 已符合 |
| 1 | 清单内的 `signature` 字段 | `canonical(manifest)` —— **去掉 signature 字段后的规范化 JSON** | 已符合 |

**通道 1 的形态是安全的**：`ProgramPackageVerifier.kt:61` 验的是
`canonical(manifest)`，签名不覆盖 `signature` 自身。攻击者替换版本号或包 URL
后无法重算签名（没有私钥），验签必然失败。

> 早先我判断「内嵌签名等于没签」是**错的** —— 没有细看 `canonical()` 的语义。
> 两种形态都成立，差别只是可读性：独立 `.sig` 能在不解析 JSON 的情况下核对签名。

**要统一的是**：都用 `SupplyProvisioner.verifyEd25519`（现已如此），
都用同一把公钥（见第 2 项）。载体形态可以保持现状 —— 若要改成独立 `.sig`，
需同步改 `ProgramPackageVerifier`、`sign-program-manifest.js` 与七牛上的存量文件。

### 2. 信任根：同一个文件

```
assets/supply/component-public.pem
```

三条链读同一把公钥。

**现状**：已核实是同一把（sha256 前 16 位 `c1699cdb002480ba`），但**挂了两个文件名**：
`assets/supply/component-public.pem`（通道 1 的三个类在读）与 `assets/supply/component-public.pem`
（通道 2、3 在读）。收敛成一个。

### 3. 通道锚点：一份配置，含三条通道

```json
{
  "schema": 2,
  "baseUrl": "https://lobcdn.zll.ink",
  "channel": "canary",
  "manifests": {
    "program":  { "name": "program-manifest.json",     "sigName": "program-manifest.json.sig" },
    "component":{ "name": "component-manifest-2.json", "sigName": "component-manifest-2.json.sig" },
    "native":   { "name": "native-manifest.json",      "sigName": "native-manifest.json.sig" }
  }
}
```

设备侧位置：APK `assets/supply/channel.json`（兜底）+ 设备侧覆盖文件（可改，不需重装 APK）。

**现状**：三份配置
- 通道 1：`assets/program-feed.json`（另有 `releaseTag`、`autoCheck`、`startupBudgetMs`）
- 通道 2：`assets/supply/channel.json`
- 通道 3：**寄生在通道 2 的锚点上**（`NativeAssetUpdater.kt:73-79` 调 `channelAnchor`）

### 4. 下载与大小上限：同一套原语

```kotlin
SupplyProvisioner.uncached(url)              // 绕过缓存
SupplyProvisioner.httpGet(url, MAX_BYTES)    // 有上限的取回
MAX_MANIFEST_BYTES                            // 清单大小上限
```

**现状**：通道 2、3 已共用；通道 1 自建 `httpGetText`，且
`MAX_MANIFEST_BYTES = 64 * 1024`（通道 2/3 是 2 MB）。

### 5. 落位：同一个前缀

```
files/usr/lib/toolchain/<件>/<版本>/      通道 1、2
files/usr/lib/toolchain/<native-id>/…    通道 3（走 PrefixProvisioner 的 CAPABILITY 路径）
```

底座件的原件永远保留在 APK 原生库，OTA 版通过软链接管 —— 回滚只需删软链。

### 6. 决策：共用 `OtaPolicy`

`ota/OtaPolicy.kt` 已经是通道无关的：输入
`remoteVersion / currentVersion / floorVersion / expiresEpochMs / sequence /
lastSequence / rolloutPercent / allowDowngrade`，输出
`UpToDate` / `Holdback` / `Available` / `Downgrade` / `Reject`。

三条链都必须走它，不各写一套版本比较。

### 7. 触发：全部由控制面板发起

| 通道 | 触发 |
|---|---|
| 1 | 面板自动检查 + `os.appmgr.checkUpdate` / `install` / `upgrade` / `uninstall` |
| 2 | 面板 |
| 3 | 面板（`lobos.sys.native.update` / `rollback` / `status` 已就位） |

**控制面板的自更新走这里，保留不动。**

`InstanceHost.kt:262-274` 的自动检查是控制面板自更新的那条路 ——
日志文案写的是「启动自动升级控制面板」，更新的就是控制面板这个系统内置程序。
控制面板现在是 APK 里的 Activity，它要更新自己，只能在宿主进程启动时做。
**这一条不是缺陷。**

要核实的只有一点：它是否只更新控制面板。当前实现是**对每个被监督的程序都跑一遍**：

```
SupervisorPool.wantedPrograms()   ← 优先 RUNNING 的 APPLICATION 程序，
                                    都没有时取第一个可启动程序
  └─ InstanceHost(host, id)
       └─ ProgramOtaUpdater.checkAndUpdate(…)   ← 每个实例一次
```

也就是说：控制面板与其他 APPLICATION 程序**共用**这条自动检查。
若将来要「只有控制面板自动更新、其他由面板按需触发」，
需在 `wantedPrograms()` 或 `InstanceHost` 里按程序身份区分 ——
**不是摘掉这条检查。**

> 早先我把它记成「程序不该在启动时自动更新，应改由面板触发」—— **错了**，
> 因为控制面板此刻就是被自动更新的那一个。

---

## 三、不做的事

- 不为任何通道新开链路 —— 同一套机制，不同通道。
- 不重写决策逻辑 —— `OtaPolicy` 已是通道无关的。
- 不为「程序还没做」而推迟规格 —— 规格先立，程序做了直接套用。

---

## 四、对齐顺序

依赖顺序，每步做完都能独立验证。

| 步 | 内容 | 风险 |
|---|---|---|
| 1 | **通道 1 签名改独立 `.sig`** | 要动 `ProgramOtaUpdater` 的验签与 `ProgramPackageVerifier` |
| 2 | 信任根收敛成一个文件 | 改三个类的资源路径 |
| 3 | 三份配置合并成一份通道定义 | 改 `channel.json` schema + 三个读取方 |
| 4 | 通道 1 换用共用原语（下载/上限） | 删 `httpGetText`，改用 `SupplyProvisioner` |
| 5 | 摘掉 `InstanceHost` 的自动触发 | 行为变更：程序不再自动更新，改由面板发起 |
| 6 | 三条链都接 `OtaPolicy` | 检查通道 2、3 是否已等价 |

**第 5 步是行为变更** —— 程序不再在每次启动时自动更新。
需要确认这正是你要的。

---

## 五、发布侧对应关系

| 通道 | 生成命令 | 上传路径 |
|---|---|---|
| 1 | 程序清单（**程序还没做，暂缺**） | `component-<channel>/program-manifest.json` |
| 2 | `publish-component-manifest.js dist release <key> <channel>` | `component-<channel>/component-manifest-2.json` |
| 3 | `publish-native-manifest.js "" release <key> <channel>` | `component-<channel>/native-manifest.json` |

通道 3 的签名与上传是本次新补的（此前 workflow 只投影、从不签名，
所以七牛上从来没有过 `native-manifest.json`）。
# OTA 三通道架构

**状态**：设计已定，实现未对齐。本文记录「应该是什么」与「现在是什么」的差距，
以及每一步的边界。**动代码前先对本文档** —— 前几轮的教训是边改边发现前提没对齐。

---

## 一、架构

**一个 OTA 机制，三个通道，全部由控制面板驱动。**

```
控制面板（最终形态：小程序，可脱离 APK 自更新）
│
├─ 通道 1  第三方应用（应用商店）
│    清单  program-manifest.json
│    触发  面板自动检查 + 面板手动安装/升级/卸载
│
├─ 通道 2  系统组件（运行时 / 工具）
│    清单  component-manifest-2.json
│    触发  面板
│
└─ 通道 3  系统更新（基础环境件）
     清单  native-manifest.json
     触发  面板
```

关键点，也是这段架构容易误解的地方：

- **系统更新不单独开链路。** 它走同一个 OTA 机制，只是另一个通道。
  基础环境件（`llvmtoolchain` / `sysroot` / `make` / `cmake` / `pkg-config` …）
  与运行时/工具（`node` / `python3` / `git` / `sqlite3` / `npm` / `pnpm`）
  的差别只是**筐归属**，不是链路形态。
- **控制面板是唯一的驱动方。** APK 侧不主动检查任何通道。
- **清单归面板托管。** APK 侧只保留信任根（公钥）与兜底默认值。
- **不因为轻量就重打包 APK。** 三条通道的更新都通过清单 + 验签 + 分发完成。

---

## 二、现在是什么（逐条核实过的代码事实）

| 维度 | 现状 | 位置 |
|---|---|---|
| 控制面板形态 | **原生 Activity 顶替**（小程序未做） | `ui/PanelActivity.kt`（301 行）；启动点三处：`MainActivity:82`、`OsHostService:291`、`SetupActivity:409` |
| 小程序运行时 | **已建成但无人调用** | `quickapp/` 8 文件 666 行；`QuickAppRegistry.register` **0 个调用点** |
| 桥接层 | 已为小程序备好 | `LobosBridge.kt` 暴露 `lobos.os.*` / `lobos.bridge.*` |
| 决策逻辑 | **已是通道无关** | `ota/OtaPolicy.kt`：输入版本/序号/灰度/有效期/是否降级，输出 `UpToDate` / `Holdback` / `Available` / `Downgrade` / `Reject` |

### 三条链的触发方

| 通道 | 现状 | 是否符合架构 |
|---|---|---|
| 第三方应用 | **自动触发挂在 `InstanceHost.kt:264`** —— 每次宿主进程启动跑一次检查（`startupBudgetMs` 默认 12000）；另有 `os.appmgr.checkUpdate` / `install` / `upgrade` / `uninstall` 供面板手动调 | ❌ 自动那条不该在程序运行时 |
| 运行时/工具 | 只有 `SupplyProvisioner` + `CatalogClient` 的「拉清单 → 验签 → 装件」，**没有 checkUpdate**，装完即止 | ⚠️ 缺更新判定 |
| 系统更新 | `lobos.sys.native.status` / `update` / `rollback`，**纯手动桥接**，无自动检测 | ⚠️ 依赖面板调，但面板不是小程序，等于半条链 |

### 通道配置目前是三份

```
assets/program-feed.json          通道 1 配置（含 autoCheck / startupBudgetMs）
assets/supply/channel.json        通道 2 配置（baseUrl / channel / manifestName）
NativeAssetUpdater 读锚点          通道 3 配置 —— 复用 SupplyProvisioner.channelAnchor
```

第三处是耦合的证据：通道 3 的 `baseUrl` 寄生在通道 2 的锚点上
（`NativeAssetUpdater.kt:73-79`）。

### 两个信任根并存

```
assets/ota-public.pem              ProgramPackageVerifier / ProgramInstaller / NodeProvisioner 读它
assets/supply/component-public.pem CatalogClient / NativeAssetUpdater 读它
```

**已核实：内容完全相同**（sha256 前 16 位均为 `c1699cdb002480ba`）——
是同一把公钥的两个文件名，信任根没有分叉。

所以这里只是「同一把钥匙挂了两个名字」，不是安全问题。
但仍应收敛到一个文件：通道 1 读 `ota-public.pem`、通道 2/3 读 `component-public.pem`，
一旦换钥匙就要改两处。

---

## 三、差距清单（按依赖顺序）

### 第 0 步：控制面板从小程序形态落地

**这是前置。** 面板现在在 APK 里，它自己无法脱离 APK 更新 ——
而整个架构的前提就是「面板能驱动三条通道，且面板本身可独立更新」。

现状支撑：`quickapp/` 运行时已建成（666 行），只差面板本体的移植。
`QuickAppRegistry.register` 没有调用点，说明注册通路是通的、只是没人注册。

### 第 1 步：把 `InstanceHost` 的程序 OTA 自动触发摘掉

`InstanceHost.kt:262-274`。按架构，通道 1 的自动检查应由面板发起，
不该在每次宿主进程启动时跑（12 秒预算 × 每次启动）。

改成面板通过已有的 `os.appmgr.checkUpdate` 触发 ——
**桥接方法已经在了，只需要换触发方**。

### 第 2 步：通道配置统一到面板侧

三份配置收成一份「通道定义」，由面板持有。APK 侧 `assets/` 只留兜底默认值。

注意 `program-feed.json` 已经支持设备侧覆盖
（`files/program-feed.json`，注释写明「可随时暂停/改通道，无需重装 APK」）——
这个机制是对的，可以作为统一后的落地形态。

### 第 3 步：系统更新通道独立出来

现在系统更新混在 `lobos.sys.native.*` 桥接里，与商店件共用桥接层。
按架构它只是通道 3，不是「商店的一种」。

改法：复用 `OtaPolicy`（它本来就通道无关），加通道标识，不新写决策逻辑。

### 第 4 步：通道 2 补 checkUpdate

现在装完即止，没有「有新版本吗」的判定。补上后与其他两条链对齐。

---

## 四、不做的事

- **不为系统更新新开一条链路。** 同一套 OTA 机制，不同通道。
- **不在 APK 侧做清单托管。** 清单归面板，APK 只留信任根。
- **不重写决策逻辑。** `OtaPolicy` 已经是通道无关的，三条链共用。

---

## 五、待确认

1. **通道配置的托管边界**：面板是「生成并下发清单」，还是只管「何时检查」而清单仍在别处？
2. ~~两个公钥是否同一把~~ —— **已核实是同一把**（见上）。剩下的是要不要收敛成一个文件。
3. **通道 3 的清单谁产出**：`native-manifest.json` 现在由
   `publish-native-manifest.js` 生成，与通道 1/2 的清单投影是分开的三条命令。
   统一后是否合并成一次投影？
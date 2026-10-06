# 安装通道规范

## 一句话

**两条路（应用商店 / 内置件 OTA），一个安装点（ProgramInstallPipeline）。**

## 为什么要两条路

它们的清单来源与作用不同，合并会耦合：

| | 应用商店 | 内置件 OTA |
|---|---|---|
| 清单 | `CatalogClient`（商店下发，在线） | `program-feed.json`（APK 内） |
| 装什么 | 商店那一批：应用程序、运行时、工具 | 控制面板等内置应用与内置系统组件 |
| 谁触发 | 控制面板的应用商店 | 开机自检 + 控制面板的"检查更新" |
| 会增加吗 | 会，商店随时上新 | 会，将来增加新的内置应用/组件 |

「退役 OTA」是个错误说法：OTA 不是某条路的实现，是"内置件自己升级"这件事本身。
该退役的只是"写死单个 URL 配全部程序"这种配置方式。

## 但安装必须只有一份

原先两边各写一套安装器，能力互相缺失：

| 能力 | 原 ota/ProgramInstaller | 原 os/PackageInstaller |
|---|---|---|
| 包内清单签名校验 | 有（`ProgramVerifier`） | 无（只校验 zip sha256） |
| 12 道落位后判据 | 有 | 无 |
| 后端平铺 + 入口改写 | 有 | 无 |
| 写注册表 `ProgramIndex` | **无** | 有 |
| 留审计 `Journal` | **无** | 有 |
| 快应用配对 | 有 | 无（装得上但打不开） |

现在：

```
应用商店路 ──┐
             ├─→ ProgramInstallPipeline.install(Spec) ─→ 唯一安装实现
内置件 OTA ──┘
```

`Spec` 描述「装什么」：`from`（来源）/ `programId` / `zip` / `shape`（包型）/ 期望版本。
两条路只负责**怎么拿到 zip** 和**给什么 spec**。

### 两种包型

`shape` 必须区分，不是可以省掉的细节：

| Shape | 是什么 | 落位规则 |
|---|---|---|
| `APPLICATION` | 带 `program-manifest.json` 的应用程序包 | 校验签名 → 12 道判据 → 后端平铺 → 入口改写 → 写注册表 → 提交 CURRENT |
| `COMPONENT` | 运行时/工具，整目录可执行件 | 解包 → 落位 → entry 软链 → 写注册表 → 提交 CURRENT |

硬套会让工具件栽在「包内无 `program-manifest.json`」上。

## 快应用配对

装包与"是不是快应用"无关。但快应用装完还差三步才可运行：

1. 分配后端端口（`PortBroker`）
2. 把地址写进前端 `config.json`（`QuickAppPackage.withEndpoint`）
3. 装入 dimina（`QuickAppHost.install`）

这三步在 `lobos/quickapp/QuickAppBinder.kt`，由**落位器**（`ota/ProgramInstaller`）
在安装流程内调用——不是"各调用方记得调"。

## 工具链不再自动装

原先 `OsApplication.supplyOnStartup()` 每次 App 启动无条件调
`SupplyProvisioner.ensure`，从商店拉 node/curl/git/jq/npm/pnpm/sqlite3。
这既绕过注册表与控制面板，也让这些件的落位规则和安装器不一致。

`SupplyProvisioner.ensure` 及其私有辅助（`applyLinkFarm` / `farmBroken` /
`aliasesOf` / `TrueName` / `linkEntry` / `ensureEntry`）已删除（-301 行）。
`SupplyProvisioner` 现在只剩纯工具函数：下载、哈希、解包、验签、路径。

## `$ORIGIN` 事故的根因

没有"一个安装器负责把一个包的依赖放在它的 `$ORIGIN` 旁边"，就必然出这种事：

node 的 `DT_RUNPATH` 是 `$ORIGIN`，只在自身所在目录找 `libc++_shared.so`。
`SupplyProvisioner` 把 node 装到 `toolchain/node/bin/`，把 `.so` 装到 `usr/bin/`
——两者不相关，于是裸环境启动必然：

```
F linker: CANNOT LINK EXECUTABLE ".../usr/bin/node":
cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstreamIc..."
```

安装合一后，这类"谁装了谁、装在哪"的问题只有一个答案。

## 未解决：校验依赖运行时

`ProgramVerifier` 用 node 跑 `program-verify.js` 来校验包，
而 node 是"按需装"的那一类。**内置应用安装校验需要 node，node 自己安装也需要校验。**

真机上因此报过 `runtime-missing`。

解法是把签名校验移到 Kotlin 侧（Ed25519 + 逐文件 sha256），不依赖运行时。
这一步尚未做，本轮不动。

## 门禁

`tools/check-install-single-path.js` 钉死六条：

1. `OsApplication` 启动不调 `SupplyProvisioner.ensure`
2. `SupplyProvisioner.ensure` 无任何调用方
3. `ensure` 本身已退役
4. 取包在调用方；解包/登记/提交 CURRENT 在 pipeline
5. 两条路都走 `ProgramInstallPipeline`，`From` 同时有 `STORE` 与 `BUILTIN`
6. 快应用配对在安装流程内必发生

`tools/check-ota-sequence-scope.js` 另管序列号隔离。

## 相关

- `docs/OTA-SEQUENCE-SCOPE.md` — sequence 隔离的来龙去脉
- `docs/NODE-NATIVE-DEPS.md` — `$ORIGIN` 那个坑的完整取证
- `docs/MULTI-MINI-PROGRAM.md` — dimina 跨快应用导航的能力边界

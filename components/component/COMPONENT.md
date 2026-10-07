# component —— 分发链上的件

程序自己用的东西。**哪些件走商店、哪些是底座，以
`docs/ENV-EXECUTION-PLAN.md` 的「2.3.1 三筐总表」为准**（分类的唯一真相）。
本文件只描述每一件**怎么编、怎么发**。

## 当前清单

| 件 | 版本 | 入口 | 上游 | 筐 |
|---|---|---|---|---|
| node | 24.21.0 | `bin/node` | nodejs.org | 运行时 |
| python3 | 3.14.8 | `bin/python3` | python.org | 运行时 |
| git | 2.55.0 | `bin/git` | kernel.org（链 zlib + openssl + curl） | 工具 |
| sqlite3 | 3530400 | `bin/sqlite3` | sqlite.org amalgamation | 工具 |
| npm | 11.19.0 | `bin/npm-cli.js` | 需 node | 工具 |
| pnpm | 12.7.0 | `bin/pnpm` | 需 node | 工具 |
| llvmtoolchain | 21.1.0 | `bin/clang` | 交叉编译 LLVM | 底座·基础环境 |
| make | 4.4.1 | `bin/make` | GNU | 底座·基础环境 |
| cmake | 4.4.4 | `bin/cmake` | cmake.org | 底座·基础环境 |
| pkg-config | 3.0.7 | `bin/pkg-config` | freedesktop | 底座·基础环境 |
| sysroot | — | `bin/sysroot` | NDK 自带 | 底座·基础环境 |
| jq | 1.8.2 | `bin/jq` | jqlang/jq（oniguruma 静态链入） | 底座·基础命令 |
| curl | 8.22.0 | `bin/curl` | curl.se（链 zlib 1.3.2 + openssl 3.6.3） | 底座·基础命令 |

底座的 `libssl`/`libcrypto`/`libz`/`libcurl` 由 `build-base-libs.sh` 编成 `.so`，
不作为独立件进商店清单。

**底座那批目前仍在 `build-component.yml` 里编**，尚未迁到 APK 内置 + OTA 链 ——
那是**链路归属**问题，与分类无关，另议。

## 版本真相

- 上游版本 + 钉值：`scripts/component-sources.json`
- 入口声明：`scripts/read-component-entry.sh`（每件唯一入口，别名从件内 `package.json` 的
  `bin` 映射投影，**入口只能有一个真相**）
- 验收探针：`scripts/component-verify.json`（每件一条，必须真跑功能）
- npm/pnpm 的版本来自 `scripts/component-sources.json` 的 `npm` 键，与
  `read-node-versions.sh npm` 同源

## 两条容易踩的坑（已在代码里钉死）

1. **构建时间必须钉死**。curl/git 静态链进 openssl，而 openssl 的
   `util/mkbuildinf.pl:19` 会把构建时刻写进件字节。墙钟进字节 ⇒ 同版本号每次重建
   sha 全变 ⇒ 内容寻址的键堆积、设备全体重下。取
   `buildTimeEpoch = 1767225600`（2026-01-01T00:00:00Z，一个过去的整点）。
   判据宿主：`scripts/verify-component-build-date.sh`。
2. **NDK 版本必须钉死**。件的身份 = 源码批次 × 构建时刻 × 工具链。前两格已钉，
   第三格此前由 runner 镜像决定，镜像换一版 NDK 六颗件 sha 全变而 CI 不红。
   现在钉 `ndkVersion = 29.0.14206865`，`build-component.yml` 的「定位 NDK」步
   同时打「钉值/实测/本镜像现有」三个读数，不等就红。

## 上架

```bash
node scripts/publish-component-manifest.js dist            # 签名，产出清单
node scripts/publish-component-manifest.js dist --project  # 只投影不签名（CI 用）
```

- 签名私钥：`keys/ota-private.pem`（**不入库**，由 secret `OTA_PRIVATE_KEY_PEM` 落到 CI 工作区）
- 公钥：`container/app/src/main/assets/supply/component-public.pem`（ed25519 信任根，随 APK 焊死）
- 通道锚：`container/app/src/main/assets/supply/channel.json`
  （`baseUrl` + `channel` + `manifestName` + `sigName`，**清单文件名的唯一来源**）
- 上传：`scripts/upload-qiniu.js <本地文件> <远端 key>`

## 发布（tag 触发）

`.github/workflows/build-component.yml` 的四段链路：

```
resolve   从 tag 解出 channel / revision / publish
   │     tag 形如 component-<canary|stable>-<正整数>；
   │     revision 落进清单的 revision 格，是商店唯一的单调判据
   ↓
build ×7  sqlite3 jq git curl pnpm npm + node（node 走 Release 复用已编译件）
   │     各自：构建 → 打包（包名带 sha12）→ 投递对象存储
   ↓
manifest  与线上清单逐格对账 → 组装并签名（用 APK 公钥自检）→ 发布清单与 .sig
```

非 tag 触发时 `publish=false`：只构建与投影，不投递。

需要的 secrets（**本仓目前 0 个**，配齐后打 tag 即可）：

| secret | 用途 |
|---|---|
| `QINIU_AK` / `QINIU_SK` / `QINIU_BUCKET` | 投递件与清单到对象存储 |
| `OTA_PRIVATE_KEY_PEM` | 签清单（ed25519 私钥，仓内与 artifact 都不留） |

**同 name@version 不许换字节** —— 对账步会判红（内容寻址的键一旦漂移，
设备全体重下且无法回退）。件的身份 = 源码批次 × 构建时刻 × 工具链，
三格都由 `component-sources.json` 钉死。

**信任根跨仓一致**：`component-public.pem` 与 dsh-mobile 仓逐字相同（同一把
ed25519 公钥，已实测用本仓公钥能验过线上清单的 64 字节签名）。所以本仓签的
清单，已装机的旧 APK 也能验过 —— 换仓不换信任根。

## 门禁

- `scripts/verify-component-artifact.sh`（件形态：aarch64 动态件 / shebang 入口形状）
- `scripts/verify-component-build-date.sh`（构建时间钉值）
- `scripts/check-component-manifest-drift.js`（清单与实际件不漂移）
- `scripts/component-verify.json` 里的每条探针（件起得来且功能对）

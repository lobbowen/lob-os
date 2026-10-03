# userland —— L1/L2 工具链件

程序自己用的工具。当前 6 件，全部已上架 CDN。

## 当前清单

| 件 | 版本 | 入口 | 上游 |
|---|---|---|---|
| curl | 8.22.0 | `bin/curl` | curl.se（链 zlib 1.3.2 + openssl 3.6.3） |
| git | 2.55.0 | `bin/git` | kernel.org（链 zlib + openssl + curl） |
| jq | 1.8.2 | `bin/jq` | jqlang/jq（oniguruma 已静态链入） |
| sqlite3 | 3530400 | `bin/sqlite3` | sqlite.org amalgamation |
| npm | 11.19.0 | `bin/npm-cli.js` | 需 node（**声明 `requires: ["node"]`**） |
| pnpm | 12.7.0 | `bin/pnpm` | 需 node（同上） |

## 版本真相

- 上游版本 + 钉值：`scripts/userland-sources.json`
- 入口声明：`scripts/read-userland-entry.sh`（每件唯一入口，别名从件内 `package.json` 的
  `bin` 映射投影，**入口只能有一个真相**）
- 验收探针：`scripts/userland-verify.json`（每件一条，必须真跑功能）
- npm/pnpm 的版本来自 `scripts/userland-sources.json` 的 `npm` 键，与
  `read-node-versions.sh npm` 同源

## 两条容易踩的坑（已在代码里钉死）

1. **构建时间必须钉死**。curl/git 静态链进 openssl，而 openssl 的
   `util/mkbuildinf.pl:19` 会把构建时刻写进件字节。墙钟进字节 ⇒ 同版本号每次重建
   sha 全变 ⇒ 内容寻址的键堆积、设备全体重下。取
   `buildTimeEpoch = 1767225600`（2026-01-01T00:00:00Z，一个过去的整点）。
   判据宿主：`scripts/verify-userland-build-date.sh`。
2. **NDK 版本必须钉死**。件的身份 = 源码批次 × 构建时刻 × 工具链。前两格已钉，
   第三格此前由 runner 镜像决定，镜像换一版 NDK 六颗件 sha 全变而 CI 不红。
   现在钉 `ndkVersion = 29.0.14206865`，`build-userland.yml` 的「定位 NDK」步
   同时打「钉值/实测/本镜像现有」三个读数，不等就红。

## 上架

```bash
node scripts/publish-userland-manifest.js dist            # 签名，产出清单
node scripts/publish-userland-manifest.js dist --project  # 只投影不签名（CI 用）
```

- 签名私钥：`keys/ota-private.pem`（**不入库**，由 secret `OTA_PRIVATE_KEY_PEM` 落到 CI 工作区）
- 公钥：`container/app/src/main/assets/supply/userland-public.pem`（ed25519 信任根，随 APK 焊死）
- 通道锚：`container/app/src/main/assets/supply/channel.json`
  （`baseUrl` + `channel` + `manifestName` + `sigName`，**清单文件名的唯一来源**）
- 上传：`scripts/upload-qiniu.js <本地文件> <远端 key>`

## 发布（tag 触发）

`.github/workflows/build-userland.yml` 的四段链路：

```
resolve   从 tag 解出 channel / revision / publish
   │     tag 形如 userland-<canary|stable>-<正整数>；
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
三格都由 `userland-sources.json` 钉死。

**信任根跨仓一致**：`userland-public.pem` 与 dsh-mobile 仓逐字相同（同一把
ed25519 公钥，已实测用本仓公钥能验过线上清单的 64 字节签名）。所以本仓签的
清单，已装机的旧 APK 也能验过 —— 换仓不换信任根。

## 门禁

- `scripts/verify-userland-artifact.sh`（件形态）
- `scripts/verify-userland-build-date.sh`（构建时间钉值）
- `scripts/check-userland-manifest-drift.js`（清单与实际件不漂移）
- `scripts/userland-verify.json` 里的每条探针（件起得来且功能对）

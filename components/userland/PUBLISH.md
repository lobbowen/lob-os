# 发布一次 node 商店件 —— 只差凭据

链路已在 CI 里验证到「只差凭据」这一步：run `e6e7a372` 的九个 job 全绿，
其中 `node` job 从 dsh-mobile 的 Release 取到已编译件、校验 sha256、落成
`userland-node-24.21.0-75695f75a08d-android-arm64.zip`（35MB，内含
`bin/node` 116,846,080 字节，sha256 `e94c5669…` 与源 Release 逐字节相同）；
`manifest` job 把 7 件投影成清单并校验了每件的 entry 与能力判据。

## 商店件发布需要的 secret（本仓目前 0 个）

| secret | 内容 | 从哪来 |
|---|---|---|
| `QINIU_AK` / `QINIU_SK` | 七牛 access key / secret key | 七牛控制台「密钥管理」 |
| `QINIU_BUCKET` | 存储空间名 | 发布用的那个空间 |
| `OTA_PRIVATE_KEY_PEM` | ed25519 私钥（PEM 全文） | 与 APK 里 `container/app/src/main/assets/supply/userland-public.pem` 配对的那一把 |

## 顺带：APK 签名需要的 secret（与商店件无关，但同一批发布要用）

| secret | 内容 | 缺了会怎样 |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | `keys/release.keystore` 的 base64 | 校验轮放行 debug 签名包；`os-release-*` 发布轮判红 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 口令 | 同上 |
| `ANDROID_KEY_ALIAS` | 密钥别名（默认 `lobos`） | 同上 |
| `ANDROID_KEY_PASSWORD` | 密钥口令（默认同 store 口令） | 同上 |

不接签名链的后果：每次出的都是 AGP 现场生成的 debug 签名，指纹每次不同 ——
新包装到已装设备上会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
判据在 `scripts/verify-apk-signing.sh`（校验轮只查签名配置生效，发布轮
`--require-stable` 把 debug 身份判红）。

设置（GitHub 网页或 CLI）：

```bash
gh secret set QINIU_AK --repo lobbowen/lob-os
gh secret set QINIU_SK --repo lobbowen/lob-os
gh secret set QINIU_BUCKET --repo lobbowen/lob-os
gh secret set OTA_PRIVATE_KEY_PEM --repo lobbowen/lob-os < ota-private.pem

# APK 签名（同一批发布要用；不配则发布轮判红）
base64 -w0 release.keystore | gh secret set ANDROID_KEYSTORE_BASE64 --repo lobbowen/lob-os
gh secret set ANDROID_KEYSTORE_PASSWORD --repo lobbowen/lob-os
gh secret set ANDROID_KEY_ALIAS --repo lobbowen/lob-os
gh secret set ANDROID_KEY_PASSWORD --repo lobbowen/lob-os
```

`release.keystore` 用 `bash scripts/keygen-android-keystore.sh [别名] [天数]`
生成，落 `keys/release.keystore`（本机需要 `keytool`）。
若沿用 `dsh-mobile` 仓里那把 key，设备端覆盖安装才能继续 ——
换 key 等于换身份，Android 没有"换回旧签名"的机制。

## 私钥必须是**现有这把**

设备端验签用的是 APK 焊死的 `userland-public.pem`。换一把钥匙 = 设备端
全部验不过，等于全量重新配对。所以：

- 配好后先用**线上清单**自检一次（下面那条命令），确认新签的能被旧公钥验过；
- 私钥只落在 CI 工作区（`$RUNNER_TEMP`），不入库、不进 artifact。

## 私钥没在本机时的自检办法

线上清单的签名是现成的，可以直接验 —— 这一步不需要私钥，只需要公钥：

```bash
curl -fsS https://hubcdn.zll.ink/userland-canary/userland-manifest-2.json -o /tmp/man.json
curl -fsS https://hubcdn.zll.ink/userland-canary/userland-manifest-2.json.sig -o /tmp/man.sig
node -e '
const fs = require("fs"), crypto = require("crypto");
const pub = fs.readFileSync("container/app/src/main/assets/supply/userland-public.pem", "utf8");
const sig = Buffer.from(fs.readFileSync("/tmp/man.sig", "utf8").trim(), "base64");
console.log(crypto.verify(null, fs.readFileSync("/tmp/man.json"), pub, sig) ? "通过" : "失败");
'
```

已实测：本仓公钥能验过线上清单的 64 字节 ed25519 签名（2026-10-04）。
所以「本仓签的清单，旧 APK 也能验过」这一点是成立的。

## 发布

配齐 secret 后打 tag 就是一次发布：

```bash
git tag userland-canary-7     # revision 必须正整数，且严格单调（线上现为 6）
git push origin userland-canary-7
```

`resolve` job 会从 tag 解出 `channel=canary` / `revision=7` / `publish=true`，
之后：7 件投递 → 与线上清单逐格对账（同 name@version 换字节即红）→
签清单（用 APK 公钥自检）→ 发布清单与 `.sig`。

线上清单 `expiresEpochMs` 是 2026-10-30T21:38:52Z，**30 天后过期**。

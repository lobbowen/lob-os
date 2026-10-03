# components/ —— 构建物类目

本目录是**唯一的构建物类目索引**。它不复制脚本，只声明三类构建物的形态契约与入口。
新增一类产品（例如未来的 python、java、wasm 运行时）时，在本目录加一个子目录 + 一份
`COMPONENT.md`，并在下表登记一行。**不允许把构建脚本散落到仓库其它位置。**

## 三类构建物

| 类目 | 子目录 | 消费路径 | 身份载体 | 可否离线首启 |
|---|---|---|---|---|
| **userland** | `userland/` | 商店程序件：`programs/<name>/<version>/` | `userland-manifest-2.json`（ed25519 签名，锚 `supply/channel.json`） | 否，走商店安装 |
| **runtime** | `runtime/` | 商店程序件：`programs/<name>/<version>/`（L1 运行时类） | 同上 | 否，走商店安装 |
| **native** | `native/` | APK 原生件：`jniLibs/arm64-v8a/` | `native-cap-<sha256>-arm64-v8a.zip` + `manifest.txt` | **是**，随 APK 交付 |

判定规则（唯一）：

```
构建物需要被「程序」用裸名调用（node/curl/git/...）  → userland 或 runtime
构建物被内核自身 dlopen / LD_PRELOAD / 链接器依赖    → native
```

`runtime` 与 `userland` 共用商店通道，区别只在 L1/L2 分层：
runtime 是**别的程序依赖它才能跑**（node），userland 是**程序自己用的工具**（curl/git）。
两者在 `ProgramIndex` 里都靠 `deps` 声明依赖关系。

## 不属于三类构建物的：APK 自身的签名

签名不是构建物，是**投递凭据**，单独记在这里以免与三类混：

| 项 | 值 |
|---|---|
| 注入 | `scripts/inject-apk-keystore.sh`（`KS_B64` + `KS_PASS` + `KS_ALIAS` + `KS_KEYPASS`） |
| gradle 侧 | `container/app/build.gradle.kts` 读 `LOBOS_KEYSTORE_PASSWORD` / `LOBOS_KEY_ALIAS` / `LOBOS_KEY_PASSWORD` |
| 落点 | `keys/release.keystore`（**不入库**；`.gitignore` 白名单制，`keys/*` 只放行 README） |
| 生成 | `scripts/keygen-android-keystore.sh` |
| 验签 | `scripts/verify-apk-signing.sh`（**未搬入**，见下） |

**为什么必须接**：不接就是 AGP 现场生成的 debug 签名，指纹每次不同 ——
新包装到已装设备上会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。所以
`build-apk.yml` 按轮次分两种处理：

- 校验轮（push main）无密钥 → 放行，出 debug 签名包并明说「仅供验证」
- 发布轮（`os-release-*` tag）无密钥 → 判红，列出需要的四个 secret

`inject-apk-keystore.sh` 的退出码语义：`0` 注入成功 / `10` 未配置（可放行）
/ `2` 配坏了（密码不对、别名不匹配、解码失败 —— 一律判红，不当"没配"处理）。

### 已知未搬入的脚本

`components/NOT-PORTED.json` 是那份清单（19 项）。它们依赖 dsh-mobile
独有的树（`container/engine/`、`programs/`、`.github/gate-policy.json`），
在 lob-os 语境下无对应物，搬进来只会在仓里留下调不通的死代码。

`tools/check-components.js` 把那份清单当白名单读，所以本文件提到它们
不算「承诺了一个不存在的脚本」；反过来，清单里列的文件如果其实已在仓内，
门禁会判红（那说明当初判错了，该从清单里划掉）。
本文件不写它们的名字之外的引用，`tools/check-components.js` 会守住这一点：
文档里提到的仓内文件必须真实存在。

## 商店通道（userland / runtime 共同遵守）

1. 身份 = `name` + `version` + `sha256`（内容寻址，包名带 sha12 前缀）
2. 清单 = `userland-manifest-2.json` + `.sig`，文件名与锚点唯一来源是
   `container/app/src/main/assets/supply/channel.json` 的 `manifestName` / `sigName`
3. 信任根 = `container/app/src/main/assets/supply/userland-public.pem`（ed25519）
4. 落盘 = `PackageInstaller.unzipInto` → `programs/<name>/<version>/`，
   经 `ProgramDir` 三态指针（`CURRENT`/`FLOOR`/`PENDING`）提交，可回滚
5. 每件必须有一条 `userland-verify.json` 验收探针，且**探针要真跑一次功能**
   （起进程 + 读回结果），不接受只看 `--version`

## 不变量（由门禁守护）

- 件内入口名只有一个真相：`scripts/read-userland-entry.sh`
- 静态链进件的构建时间必须钉死（`userland-sources.json` 的 `buildTimeEpoch`），
  否则同版本每次重建 sha 全变，内容寻址失效
- NDK 版本钉在 `userland-sources.json` 的 `ndkVersion`，与 runner 镜像不符即红
- `scripts/` 下不得硬编码绝对路径（`tools/check-tool-paths.js`）
- 设备端清单名不得硬编码，只能从通道锚读（`SupplyProvisioner.anchorName`）

## 守护这些不变量的门禁

| 门禁 | 守什么 |
|---|---|
| `tools/check-components.js` | 本文件的分类规则：三个类目齐全、每颗件有 entry + 能力判据、构建脚本与声明双向对得上、runtime 类不进 APK |
| `tools/check-spec-tables.js` | API 三表自洽 |
| `tools/check-android-consts.js` | 不得有裸的 Android 常量引用 |
| `tools/check-dead-refs.js` | 已删符号的悬空引用 |
| `tools/ktcheck.js` | `.kt` 结构（括号配平、companion 唯一等） |
| `scripts/comment-gate.js` | 零遗留注释、无 JSON 散文键（判据的理由写在本目录，不写进代码） |
| `scripts/gen-native-assets.js` 幂等 | 资产清单由 Kotlin 声明生成，不手改 |

## 目录

```
components/
  README.md            ← 本文件
  userland/COMPONENT.md
  runtime/COMPONENT.md
  native/COMPONENT.md
```

# components/ —— 构建物类目

本目录是**唯一的构建物类目索引**。它不复制脚本，只声明三类构建物的形态契约与入口。
新增一类产品（例如未来的 python、java、wasm 运行时）时，在本目录加一个子目录 + 一份
`COMPONENT.md`，并在下表登记一行。**不允许把构建脚本散落到仓库其它位置。**

## 三类构建物

| 类目 | 子目录 | 消费路径 | 身份载体 | 可否离线首启 |
|---|---|---|---|---|
| **component** | `component/` | 商店程序件：`programs/<name>/<version>/` | `component-manifest-2.json`（ed25519 签名，锚 `supply/channel.json`） | 否，走商店安装 |
| **runtime** | `runtime/` | 商店程序件：`programs/<name>/<version>/`（L1 运行时类） | 同上 | 否，走商店安装 |
| **native** | `native/` | APK 原生件：`jniLibs/arm64-v8a/` | `native-cap-<sha256>-arm64-v8a.zip` + `manifest.txt` | **是**，随 APK 交付 |

判定规则（唯一）：

```
构建物需要被「程序」用裸名调用（node/curl/git/...）  → component 或 runtime
构建物被内核自身 dlopen / LD_PRELOAD / 链接器依赖    → native
```

`runtime` 与 `component` 共用商店通道，区别只在 L1/L2 分层：
runtime 是**别的程序依赖它才能跑**（node），component 是**程序自己用的工具**（curl/git）。
两者在 `ProgramIndex` 里都靠 `deps` 声明依赖关系。

## 不属于三类构建物的：APK 自身的签名

签名不是构建物，是**投递凭据**，单独记在这里以免与三类混：

| 项 | 值 |
|---|---|
| 注入 | `scripts/inject-apk-keystore.sh`（`KS_B64` + `KS_PASS` + `KS_ALIAS` + `KS_KEYPASS`） |
| gradle 侧 | `container/app/build.gradle.kts` 读 `LOBOS_KEYSTORE_PATH`（默认 `keys/release.keystore`）/ `LOBOS_KEYSTORE_PASSWORD` / `LOBOS_KEY_ALIAS` / `LOBOS_KEY_PASSWORD` / `LOBOS_APP_ID`（默认 `lobos.app`） |
| 落点 | `keys/release.keystore`（**不入库**；`.gitignore` 白名单制，`keys/*` 只放行 README） |
| 生成 | `scripts/keygen-android-keystore.sh` |
| 验签 | `scripts/verify-apk-signing.sh`（校验轮只查一致性，发布轮带 `--require-stable`） |

**为什么必须接**：不接就是 AGP 现场生成的 debug 签名，指纹每次不同 ——
新包装到已装设备上会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。所以
`build-apk.yml` 按轮次分两种处理：

- 校验轮（push main）无密钥 → 放行，出 debug 签名包并明说「仅供验证」
- 发布轮（`os-release-*` tag）无密钥 → 判红，列出需要的四个 secret

`inject-apk-keystore.sh` 的退出码语义：`0` 注入成功 / `10` 未配置（可放行）
/ `2` 配坏了（密码不对、别名不匹配、解码失败 —— 一律判红，不当"没配"处理）。

### 为什么签名要能分两把（`LOBOS_KEYSTORE_PATH` + `LOBOS_APP_ID`）

**签名身份不可回退** —— Android 没有"换回旧签名"的机制，一把 keystore 一旦发过
就必须是那把。所以「已发布的那把」与「开发验证用的那把」必须能分开。

**而且光换签名不够**：同 `applicationId` + 不同签名**不是并存，是安装失败**
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）—— 系统认为你要覆盖装那个应用。
要真正并存，必须 `applicationId` 也不同。两处一起换，系统才当成两个应用。

用途：`.github/workflows/build-isolated-apk.yml` 手动触发，造一把只用于并存验证的
keystore + 换一个 id，产出与设备上现有开发包并存的第二个应用。这样**新代码能在
真机上跑，而开发用的机器完全不受影响**。

生产发布轮什么都不用改（两个环境变量都有默认值，且默认值就是当前行为）。

### 已知未搬入的脚本

`components/NOT-PORTED.json` 是那份清单，**按依赖分四组**（发布回执台账 /
投递与归档 / 门禁族 / 开发期工具），每组写明「为什么现在不搬」与「条件具备时
整组搬」。门禁强制每组必须有 `why` —— 不写原因，后来人只能靠猜。

`tools/check-components.js` 把那份清单当白名单读，所以本文件提到它们
不算「承诺了一个不存在的脚本」；反过来，清单里列的文件如果其实已在仓内，
门禁会判红（那说明当初判错了，该从清单里划掉）。文档里提到的仓内文件
必须真实存在，这一点门禁也守着。

**不要零散搬**：这些脚本多数互相调用（读台账的喂给判版本号的、判版本号的
喂给判形态的），单独搬一个进来就是调不通的死代码。

## 商店通道（component / runtime 共同遵守）

1. 身份 = `name` + `version` + `sha256`（内容寻址，包名带 sha12 前缀）
2. 清单 = `component-manifest-2.json` + `.sig`，文件名与锚点唯一来源是
   `container/app/src/main/assets/supply/channel.json` 的 `manifestName` / `sigName`
3. 信任根 = `container/app/src/main/assets/supply/component-public.pem`（ed25519）
4. 落盘 = `PackageInstaller.unzipInto` → `programs/<name>/<version>/`，
   经 `ProgramDir` 三态指针（`CURRENT`/`FLOOR`/`PENDING`）提交，可回滚
5. 每件必须有一条 `component-verify.json` 验收探针，且**探针要真跑一次功能**
   （起进程 + 读回结果），不接受只看 `--version`

## 不变量（由门禁守护）

- 件内入口名只有一个真相：`scripts/read-component-entry.sh`
- 静态链进件的构建时间必须钉死（`component-sources.json` 的 `buildTimeEpoch`），
  否则同版本每次重建 sha 全变，内容寻址失效
- NDK 版本钉在 `component-sources.json` 的 `ndkVersion`，与 runner 镜像不符即红
- `scripts/` 下不得硬编码绝对路径（人工 review；原先有个门禁专门查这条，
  已按 `docs/GATE-CLASSIFICATION.md` 的 A 类删除 —— 它只约束门禁脚本自身，
  是工作习惯而非产品共识）
- 设备端清单名不得硬编码，只能从通道锚读（`SupplyProvisioner.anchorName`）
- 桥协议号三处声明必须一致：`protocol.js` 的 `PROTOCOL_VERSION`、
  `CapabilityBroker` 的 `PROTOCOL_MIN`、`version.json` 的
  `shell.bridgeProtocol`（→ `BuildConfig.BRIDGE_PROTOCOL`）。
  三个消费方各读一处：引擎握手答自己的、壳按下界收客户端、
  `program-verify.js` 拿 `BuildConfig` 比内核的 `requiresProtocol`。
  漂移的后果是**握手在真机上失败、而仓内全绿**。

## 守护这些不变量的门禁

| 门禁 | 守什么 |
|---|---|
| `tools/check-components.js` | 本文件的分类规则：三个类目齐全、每颗件有 entry + 能力判据、构建脚本与声明双向对得上、runtime 类不进 APK |
| `tools/check-spec-tables.js` | API 三表自洽 |
| `tools/check-android-consts.js` | 不得有裸的 Android 常量引用 |
| `tools/ktcheck.js` | `.kt` 结构（括号配平、companion 唯一等） |
| `scripts/comment-gate.js` | 零遗留注释、无 JSON 散文键（判据的理由写在本目录，不写进代码） |
| `scripts/gen-native-assets.js` 幂等 | 资产清单由 Kotlin 声明生成，不手改 |
| `tools/check-protocol-version.js` | 桥协议号三处声明一致；壳侧下界不得大于 `version.json` 的上界（区间为空则所有客户端被拒） |

门禁总数与分类见 `docs/GATE-CLASSIFICATION.md`。
**门禁的唯一职责是固化已达成共识的行为**；编译器能报的错误（缺 import、
符号不存在、括号不配平）不做门禁，CI 编译直接兜住。

「门禁有没有调用点」这类元检查也已删除（见 `docs/GATE-CLASSIFICATION.md`）：
它管的是门禁自身的整洁，不是产品共识，属于为了门禁而门禁。

## 目录

```
components/
  README.md            ← 本文件
  component/COMPONENT.md
  runtime/COMPONENT.md
  native/COMPONENT.md
```

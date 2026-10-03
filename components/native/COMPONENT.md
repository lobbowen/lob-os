# native —— APK 原生件

被内核自身 `dlopen` / `LD_PRELOAD` / 链接器依赖的构建物。**随 APK 交付，不走商店**
——它们是内核自身的零件，不是程序可调用的工具。

## 当前清单

声明源：`container/app/src/main/java/lobos/native/NativeAssetRegistry.kt`

| id | 文件名 | 来源 | 用途 | 缺失后果 |
|---|---|---|---|---|
| libcxx | `libc++_shared.so` | NDK sysroot | APK 内 C++ 原生件的运行期依赖 | 原生件全部加载失败 |
| bash | `libbash.so` | upstream | bash 执行器 | 无 bash 程序 |
| ripgrep | `liblobosrg.so` | upstream | glob/grep | `SEARCH_FAILED` |
| flock | `liblobosflock.so` | self-c | `flock(2)` 原生桥 | 回退 vendor 实现 |
| posix | `liblobosposix.so` | self-c | `link`/`linkat` 用户态替代（LD_PRELOAD） | 会话落盘失败 |
| ptyprobe | `liblobosptyprobe.so` | self-c | PTY 探针（静态可执行） | PTY 探测不可用 |
| pty | `liblobospty.so` | soft | PTY 支撑件 | 见 `native-deps.txt` |

## 为什么这批不走商店

- `posix` 靠 `LD_PRELOAD` 注入，**必须在进程启动前就位**，商店件来不及。
- `libc++_shared.so` 是 APK 内其它原生件的运行期依赖，必须与它们同在
  `nativeLibraryDir`。
- 这批件没有「版本号 + 升级」的用户语义 —— 它们随内核版本走。

## 构建与发放

- 构建：`scripts/build-native-capabilities.sh`（NDK r29 交叉编译）
- 依赖声明：`scripts/native-deps.txt`
- 资产清单：`.github/native-assets.txt`（**APK 必须含哪些 .so 的唯一真相**）
- 生成/校验：`scripts/gen-native-assets.js`（幂等，CI 校验不漂移）
- 指纹：`scripts/native-capabilities-fingerprint.sh`
- 兜底复用：`scripts/ensure-native-capabilities.sh`
- 产物形态校验：`scripts/verify-apk-native.sh`
- 分发形态：Release `native-cap-<sha256>-arm64-v8a.zip` + `manifest.txt`
  （每行 `来源 lib名 sha256`，来源取 `self-c` / `upstream` / `soft`）
- 固化记录：`.github/native-capabilities-pin.json`（指纹 → 不可变 Release）

### 为什么固化机制要做成「命中就用，不命中就编」

固化（pin）的目的是「编译一次，之后不再编」。但固化机制本身会坏：Release 被删、
下载失败、sha256 对不上、包不完整。判据有三条，缺一即回退现场编译：

1. 当前**源指纹**在 `.github/native-capabilities-pin.json` 里有条目；
2. 能下载并**逐字节校验 sha256**（清单里那份）；
3. 解包后**档位清单里每一件都在**（缺件 = 固化包不完整，不取用）。

任一不满足就 `exec build-native-capabilities.sh` 现场编译。这样「固化机制坏了」
只退化成「慢」，不退化成「打不出包」。

**源指纹** = `container/native/**` 的内容 + `build-native-capabilities.sh` 的内容。
构建输入变了指纹就变，从而「能不能用旧产物」有唯一判据。
诚实记下一个缺口：**NDK 版本不在指纹里**——它由 runner 预装，本仓未钉。
若 runner 的 NDK 大版本变更，产物可能变而指纹不变。

**当前 lob-os 的指纹与 dsh-mobile 的固化记录不匹配**（`container/native/*` 的 7 个 C 源
两边都已分叉）。这是正确行为：源不同 → 产物可能不同 → 不复用。走现场编译。

## 不变量

- `gen-native-assets.js` 幂等：跑一次不得产生 diff（CI 校验）
- 件的指纹 = 文件内容的 sha256，不接受用文件名或版本号当身份
- 声明（`NativeAssetRegistry`）与实物（`jniLibs`）必须一致；
  `required=true` 的件缺失即中止启动，不允许降级放行

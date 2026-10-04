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
- 产物形态校验：`scripts/verify-apk-native.sh`（APK 条目 vs 三份声明表）
- 原生件 ELF 形态与依赖闭环：`scripts/verify-runtime-elf.sh`（架构 / 对齐 /
  解释器 / DT_NEEDED 闭环 / DT_RUNPATH）
- OTA 锚点判据：`scripts/verify-ota-anchor.sh`（Ed25519 公钥可解析；
  配 `--private` 时额外判私钥与锚点是否同对）
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

## 依赖必须自带 `$ORIGIN` RUNPATH（实测缺陷，已修）

`bionic` **不查 `nativeLibraryDir`**，`LD_LIBRARY_PATH` 只在进程环境里有效。
任何依赖同目录 `libc++_shared.so` 的件，都必须自带含 `$ORIGIN` 的 `DT_RUNPATH`，
否则载荷 `run_code` 起子进程时是空环境，必然 `CANNOT LINK`。

- `liblobosrg.so`：Rust 侧显式传 `-Wl,-rpath,$ORIGIN`，**已合规**。
- `liblobospty.so`（node-pty，走 node-gyp）：**原先没有** —— 实测 APK 里
  `readelf -d` 只有 `NEEDED libc++_shared.so` 而无 `RUNPATH`。
  现由 `build-native-capabilities.sh` 给 `binding.gyp` 的 `ldflags` 注入
  `-Wl,--enable-new-dtags -Wl,-rpath,$ORIGIN`（gyp 默认写 `DT_RPATH`，
  而 bionic 忽略 `RPATH`，所以必须用 new-dtags 换出 `RUNPATH`），
  落位后 `readelf -d` 自检；不合格只降级不判红（它是 `soft` 档）。

### `$ORIGIN` 的字面量必须绕开 shell / gyp 两层展开

实测踩到：把 `'-Wl,-rpath,$ORIGIN'` 写进 `binding.gyp` 源码后，CI 产出的
`liblobospty.so` 的 `DT_RUNPATH` 是 **`[RIGIN]`** —— `$O` 被吃掉了
（gyp 自己会展开 Makefile 变量，`$O` 未定义即展开成空）。
门禁的自检如实报了这个不合格，所以那轮是**绿中带降级**，不是误放行。

因此注入的字面量由 `String.fromCharCode(36) + "ORIGIN"` 拼出，
不依赖任何一层展开 —— 判据的输入不能是被判据本身要检查的那类字符串。
自检口径是 `RUNPATH` 必须**恰好含 `$ORIGIN`**：`[RIGIN]` / `[$LIB]` /
无 `RUNPATH` 三种坏形态都被判红（实测）。


## 档位语义：判据按「缺了会怎样」分档

判据 3/4/5（架构 / 16KB 对齐 / 解释器 / 依赖闭环 / RUNPATH）对
`.github/native-capabilities.txt` 里**每一件**都跑，但结论按档位给：

| 档位 | 形态或依赖不合格 |
|---|---|
| `self-c` / `upstream` | 判红（自有的编不出来是环境问题；上游的 `$PREFIX` 依赖它且无回退） |
| `soft` | `::warning` 降级，**不判红** |

这与 `verify-apk-native.sh` 的档位语义同源（一份数据、两处消费）。
一视同仁地判红会让一条可降级的终端能力把整轮构建判死 —— 降级路径
反而被门禁堵死。档位表读不到即判红（exit 2）：降级语义无从判定时不得放行。


## 不变量

- `gen-native-assets.js` 幂等：跑一次不得产生 diff（CI 校验）
- 件的指纹 = 文件内容的 sha256，不接受用文件名或版本号当身份
- 声明（`NativeAssetRegistry`）与实物（`jniLibs`）必须一致；
  `required=true` 的件缺失即中止启动，不允许降级放行

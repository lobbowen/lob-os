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

### `$ORIGIN` 的字面量：gyp 是 Python，值要过 make 与 shell 两层

`gyp` 的 `binding.gyp` 是 **Python** 源码（`gyp/input.py` 直接 `eval`），
不是 JSON。往 `ldflags` 里塞 `-Wl,-rpath,$ORIGIN`，踩了五个坑，
每个都是 CI 实测（不是推断）：

| 写进 gyp 的字面量 | 实际结果 |
|---|---|
| `'-Wl,-rpath,$ORIGIN'` | `DT_RUNPATH=[RIGIN]` —— `make` 把 `$O` 当未定义变量展开成空 |
| `'-Wl,-rpath,$$ORIGIN'` | `DT_RUNPATH=[-soname=pty.node]` —— flag 被挤掉 |
| `'-Wl,-rpath,<!(printf %s '$ORIGIN')>'` | `SyntaxError: invalid syntax` —— Python 里单引号套娃，gyp 崩 |
| `"-Wl,-rpath,<!printf %s '$ORIGIN'>"` | `/bin/sh: cannot open !printf` —— **`<!cmd>` 在 `ldflags` 里不被求值**，原样进了 make 命令 |
| `"-Wl,-rpath,'$$ORIGIN'"` | 待验：`$$` 给 `make`、`'$…'` 给 shell |

最后一行的两层分工是这件事的本质：

- **gyp** 只是 Python 源码解析 —— 外层引号必须让 Python 解析得过；
- **make** 拼 Makefile 命令 —— `$$` 还原成单个 `$`；
- **shell** 执行链接命令 —— 单引号让 `$ORIGIN` 不被 shell 展开。

三层各要各的转义，缺一层就坏在三处之一。而 `<!cmd>` 这种「求值」写法
只在 gyp 的 `variables` 块里成立，放进 `ldflags` 不会被求值。

**判据：只认 CI 日志里 `readelf -d` 那一行**，不认注入代码「看起来对」。
本机无法复现（无 `make`、`node-gyp` 只在 CI 跑），所以每版只能靠 CI 判。

落位自检口径：`RUNPATH` 必须**恰好含 `$ORIGIN`**；
`[RIGIN]` / `[$LIB]` / `[-soname=…]` / 无 `RUNPATH` 都判红。
不合格不判红而是降级（`soft` 档）并写进日志 ——
所以「CI 绿」与「PTY 真的对了」必须分开看：
**看 `liblobospty.so` 那一行有没有 `::warning` 降级提示。**

判据的输入不能是被判据本身要检查的那类字符串：这几个坑形状相同，
都是「用含 `$` 的字符串去构造对 `$` 的判据」。

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

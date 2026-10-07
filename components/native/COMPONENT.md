# native —— APK 原生件

被宿主自身 `dlopen` / `LD_PRELOAD` / 链接器依赖的构建物。**随 APK 交付，不走商店**
——它们是宿主自身的零件，不是程序可调用的工具。

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
| ptysession | `librivospty.so` | self-c | PTY 会话宿主（常驻可执行件） | `shell.exec` 只能退回 ADB；终端不可用 |
| pty | `liblobospty.so` | soft | PTY 支撑件 | 见 `native-deps.txt` |
| busybox | `libbusybox.so` | upstream | 基础命令集（tar/gzip/grep/sed/ls/cp/mv…） | 程序找不到基础命令 |

### busybox 的三个核实结论（都是踩过的坑，不是推断）

**零、与 toybox 的关系（本机实测，别当成「重复建设」去撤）。**
这台设备（OPPO PLP120 / Android 17）的 toybox 已提供 18 个 applet 里的 **17 个**
（`/system/bin/grep -> toybox` 实测如此；唯一缺的是 `awk` —— 说明**厂商裁剪是真实发生的**）。
即便如此仍要自带，理由不是「toybox 没有」，而是：

- toybox 在 `/system/bin`，随 OTA 变、厂商可裁剪 —— 我们要的是**可预期**；
- 我们 pin 了版本、验了判据、能经阶段8 的 OTA 通道更新；
- applet 行为与 GNU/BSD 有细微差异（选项支持不同）。

优先级：`$PREFIX/bin` 在 PATH 第 1 位、`/system/bin` 在第 6 位（实测），
所以底座件**天然优先**于 toybox —— 这正是要的行为。

**一、不能用 `configs/android_ndk_defconfig`。** 核实它是 **BusyBox 1.24.0 (2015)**，
而源码取的是 1.36.1 —— 配置项名跨了十几个版本。而且它的 `EXTRA_CFLAGS` 硬编码了
`-march=armv7-a`（32 位 ARM）与 `-nostdlib`（Bionic 下链接必失败）。照抄会编出错误架构。
正确做法是 `make defconfig` 取**本版本自己的**基线。

**二、核实 applet 配置项名不能用错工具。** applet 的 config **不在** `Config.in` 里
（`grep '^config TAR$' Config.in` 永远找不到）。busybox 源码树里有一个生成步骤，
把各 applet 的 `.c` 文件里的 `//config:` 注释拼接成 `<dir>/Config.in` ——
那个生成器在上游源码里，**不在本仓**（所以按下面的表查源码树，不要查本仓）。

| 查什么 | 正确命令 |
|---|---|
| applet（如 TAR） | `grep '^//config:config NAME$' --include='*.c' .` |
| 编译选项（如 STATIC） | `grep '^config NAME$' Config.in` |

本轮据此逐个核实了 18 个 applet 名与 4 个编译选项，全部真实存在
（`SHOW_SPLASH_WATER` 是我最初臆造的，源码里没有，已删）。

**版本号是三段分开写的**：`VERSION = 1` / `PATCHLEVEL = 36` / `SUBLEVEL = 1`。
写成一行 `VERSION = 1.36.1` 去 sed，取到的是 `1`，于是「版本不符」判死 ——
那是判据自己写错，不是源码有问题。

**静态编、不链底座 `libz`**：gzip/gunzip 的 zlib 编进去，代价 ~100-200KB，
换来 busybox 完全自足。底座件之间不互相依赖到「少一件就起不来」的程度。
（阶段0 的 `libz.so` 是给 base 筐里 jq/curl 改动态链用的。）

**两个下载源的压缩格式与目录名都不同**（实测）：`busybox.net` 给
`busybox-1.36.1.tar.bz2` → `busybox-1.36.1/`；github mirror 给
`1_36_1.tar.gz` → `busybox-1_36_1/`。所以按源分别带解包方式与期望目录名，
不能「所有源都用 tar xjf + 同一个目录名」。

### ptysession 为什么是「常驻可执行件」而不是共享库

`ProcessBuilder` 不分配 PTY —— `isatty()` 为假、程序不进交互模式、读不到窗口大小。
Android 的 `ProcessBuilder` 不暴露 `setsid`/`TIOCSCTTY`，**无 JNI 做不到**。

仓内已有两种原生范式（`LD_PRELOAD` 注入 / 可执行件探针），本件属第三种形态但
**仍然不写 JNI**：常驻可执行件 + 定长小端帧协议（三条流一一对应
`ProcessBuilder` 的 stdin/stdout/stderr）。理由：

- 避开 `System.loadLibrary` 的装载路径问题，也不必担心它被误当共享库 `dlopen`；
- 协议是定长头 + 裸字节，因为 **PTY 输出是任意二进制**（含 NUL 与 0xFF），
  JSON 编码会破坏它或变得昂贵；
- 会话宿主起来要几十毫秒，所以**全设备一个常驻实例**，`shell.exec` 与
  第 9 阶段的内置终端窗口共用同一个 `PtySession.Host`。

落位名是 `$PREFIX/bin/pty-session`（不是 `librivospty.so`）—— 用户在 PATH 里
敲的应该是工具名，不该看到一个 `.so` 当命令。APK 里的打包名与系统名不同这件事，
`PtySession.locateBin()` 两个位置都认。

数据回调按 **sid** 路由而不是「当前谁在跑」——挂 host 的话两个并发会话的输出会
互相串，那是最难查的一类 bug：单独测都对，并发就错。

### 协议必须逐字段核字节序（本轮教训：连着三个真缺陷都在这里）

写完「两侧各自都像对的」不等于「两侧对得上」。逐字段核的结果：

| 帧 | C 写 | Kotlin 读 | 状态 |
|---|---|---|---|
| OPEN | payload = NUL 分隔 argv | 按 NUL 切 | 曾丢参数（只传 argv[0]） |
| READY | slave路径(NUL) + winsize(8) | 按 nul 位置切 + 小端解 | 曾用默认大端 |
| WINSIZE | payload = struct winsize(8) | 小端解 4 个 short | 曾用默认大端 |
| EXITED | status(4) + signal(4) | 小端解两个 uint32 | 曾**完全不读** |
| ERROR | NUL 结尾文本 | trimEnd 掉 NUL | 原假设「恰好一个 NUL」 |
| 帧头 | kind@0 sid@1 flags@2 length@4，sizeof=8 | 逐字节显式赋值 | ✓ |

三个缺陷的后果都是**不报错**：

1. `bash -c "npm install"` 变成裸 bash（参数被丢）——「终端一开就没反应」
2. 窗口大小 24 被读成 6144——判据 4 失效而现象是「改了没反应」
3. 退出码恒为 0——「命令失败了但系统说成功」，程序会以为部署成功

**同一协议里两种写法并存本身就是风险**：帧头用显式移位（本来就小端），
winsize 用 `ByteBuffer`（默认大端）——后者就会踩默认值。所以
**凡跨语言的定长字段，一律显式写字节序**，不用有默认值的 API。

判据上也要分清两件事：

- `completed` = 命令真的跑完了（拿到退出码或被信号终止）
- `ok` = 跑完了**且**退出码为 0

只用一个 `ok` 的话，「命令失败」会被当成「PTY 不可用」而触发通路回落 ——
把诊断指向 PTY（真正没问题的地方）。

### 路径推导不要「数层级」（第二个同源教训）

`NativeAssetUpdater.states()` 原先用「从 real 往上数三层再 `relativeTo(toolchain/)`」
推已装版本。逐场景算下来，**两种正常落位形态都返回 null**：

| 落位 | 往上三层落到 | 推出版本 |
|---|---|---|
| `<TC>/bash/5.2.21/bash` | `<TC>` | `null` |
| `<TC>/lib/1.2.3/lib/libssl.so` | `<TC>/lib` | `null` |

也就是 `installedVersion` **从来没读出来过**。后果全是「看起来正常，只是多做/
做不出来的事」：更新判据永远不成立（每次重装）、`prune` 认不出在用的那一份、
界面永远显示不出当前版本。

改法：取**相对 `toolchain/` 的段**（段 0 = id、段 1 = 版本）。落位形态
`toolchain/<id>/<版本>/<entry…>` 是固定的，所以取段 1 与 `entry` 有几级无关。

**教训与上面那条同源**：跨语言协议、以及**任何跨边界的路径推导**，
都不能靠「我这边看着对」。数层级把「entry 是单级」当前提，
而那只是当前四件恰好如此 —— 巧合不是保证。

## 为什么这批不走商店

- `posix` 靠 `LD_PRELOAD` 注入，**必须在进程启动前就位**，商店件来不及。
- `libc++_shared.so` 是 APK 内其它原生件的运行期依赖，必须与它们同在
  `nativeLibraryDir`。
- 这批件没有「版本号 + 升级」的用户语义 —— 它们随宿主版本走。

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
| `"-Wl,-rpath,'$$ORIGIN'"` | **成立** —— `RUNPATH=[$ORIGIN]`，门禁判「可自解析同目录依赖」 |

最后一行的三层分工是这件事的本质：

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

## 版本化与 OTA 更新（原件保留，可回滚）

底座件随 APK 交付，**原件在 `nativeLibraryDir` 里永远不动**，就是回退基线。
但底座件恰恰是最常需要打补丁的一批（bash / busybox / openssl 都是上游件，
上游出安全更新是常规节奏）—— 若只能换 APK 才能换件，修一个 bash 的 bug
就要重发整个 APK。

```
原件（APK）   nativeLibraryDir/libbash.so         ← 永远不动 = 回退基线
更新件        $PREFIX/lib/toolchain/bash/<版本>/  ← OTA 落这里
入口          $PREFIX/bin/bash → 软链到更新件     ← 优先指向新版本
回滚          删软链，让 provision 用 APK 原件重建
```

`version` 为空串的语义是「随 APK、不单独更新」，那些件**不进 OTA 清单** ——
让它们出现在清单里会让人以为能更新。当前有版本的五件：
bash 5.2.15 · busybox 1.36.1 · zlib 1.3.2 · openssl 3.6.3 · curl 8.22.0
（版本值一律照抄构建脚本常量或 `component-sources.json` 钉值表，不凭记忆）。

### 三个必须知道的事实

**一、`provision()` 每次进程启动都跑。** 所以它**无条件覆盖** `usr/bin/<name>`
会把 OTA 更新下来的件冲回 APK 原件，而且没有任何日志提示。
`isManagedByUpdate()` 的判据形态是「软链且指向 `libDir/toolchain/`」——
以文件系统的实际形态为准，比查清单可靠（清单可能没刷新而软链已经切过去了）。

**二、落位规则与商店件不同，所以是两个安装点。**
`ProgramDir.assertNotDirectlyExecutable` 明确断言「控制面板入口不该在 filesDir 里
直接 exec」，而底座件**必须**能在 filesDir 里 exec（本机实测：chmod +x 的脚本
输出 `EXEC_OK`）。所以不复用 `ProgramInstallPipeline` 的落位，
但复用它的**验签与下载**（`SupplyProvisioner`）。

**三、不新增签名体系。** 底座件清单与商店清单用**同一把 Ed25519 公钥**
（`assets/supply/component-public.pem`）。两套信任根意味着两处要轮换、
两处可能只更新一处。

### 桥接方法（系统作用域）

| 方法 | 作用 |
|---|---|
| `lobos.sys.native.status` | 每一件当前在用哪个版本、是否已被 OTA 接管 |
| `lobos.sys.native.update` | 比对清单并安装；**`dryRun` 默认 true** |
| `lobos.sys.native.rollback` | 回滚一件到 APK 原件 |

三者都在 `ApiSpec.SCOPES` 里登记为 `SCOPE_SYSTEM` —— 漏登记的后果不是文档少一条，
而是**程序会话能替换底座件**（等于让程序替换 bash 与 openssl 库）。

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
- **返回 `Boolean` 的动作函数，调用处必须判它的返回值。**
  判据存在却没接上，与没有判据等价 —— `linkEntry` 曾经返回建链成败而调用处
  忽略它，于是「件已落位但入口软链没建成」时安装照报成功，程序第一次 spawn
  才炸。上面那句注释「不建链等于装了个没人调得动的件」说明它当时就该判。

## 自写工具的产出，先验证工具本身

本会话在自写扫描器/断言工具上栽了四次（都是拿它的输出当依据，然后发现它错）：

| 工具 | 它报了什么 | 实际 |
|---|---|---|
| 覆盖率检查器 | 「CSI `H`/`J`/`m` 全部未覆盖」 | 正则按 12 空格找 `when` 体，实际是 8 空格 |
| 反例套件 | 判红脚本「本该过却过了」 | `cmd \| grep; echo $?` 取的是 **grep** 的退出码 |
| 注册表解析器 | 「4 件全部没有 installName」 | 可选组 + 非贪婪 → 为了「不匹配」而放弃整个组 |
| 忽略返回值扫描器 | 18 处「返回 Boolean 但被忽略」 | **全误报**：漏判 `when` 分支体与 `&&` 左侧 |

共性：**工具本身没被验证过**。所以它们的「**没扫出问题**」同样不能当证据 ——
那只是「这台工具没看见」，不是「那里没有问题」。

判据：写完扫描/断言工具，先造一个**已知会被它抓到**的用例；
抓不到就说明工具本身坏了，而不是「没问题」。
按这条纪律，上面四条工具都已被反例抓到过（详见 `_scripts/verify-tmp/`）。

**这类工具不要写进门禁**：它们只能判「明显形态」，判不了「语义对不对」，
而门禁全绿会被读成「已验证」。写进契约当提醒更诚实。

## 验证过的清单（反例脚本在 `_scripts/verify-tmp/`，不入仓）

那些脚本依赖本机环境（真 APK、假 NDK、本机网络），所以不入库；
但「验过什么」这件事该可查，不该只散在提交信息里。

| 脚本 | 验的是什么 | 断言数 |
|---|---|---|
| `check-lib-counterexample.sh` | `check_lib` 能否抓真机故障（反例源是真 arm64 产物） | 7 |
| `sysroot-counterexample.sh` | sysroot 配方的三道闸门（版本/头文件/库） | 5 |
| `ndk-llvm-counterexample.sh` | `verify-ndk-llvm.sh` 的判红能力（假 NDK） | 7 |
| `native-manifest-counterexample.sh` | 底座件清单生成器的判红能力 | 5 |
| `alias-symlink-proof.js` | 一件多命令时，只有本名能调用的形态 | 7 |
| `sysroot-include-proof.js` | `$PREFIX/include` 软链（含「升级后跟着换」） | 10 |
| `pty-argv-proof.js` | PTY 协议两侧 argv 传递（真字节往返） | 14 |
| `pty-winsize-endian.js` | winsize 字节序（大端会把 24 读成 6144） | 9 |
| `terminal-screen-algorithm.js` | 终端仿真的三条关键性质（CJK 双列/分片/LF 不回列） | 18 |
| `terminal-covers-real-pty.js` | 真实 PTY 输出里出现的序列是否都会被处理 | — |
| `pack-extract-proof.js` | 从固化包取件（`entry` vs `libName` 的角色） | 4 |
| `entry-link-proof.js` | 「装了但调不到」这个形态 | — |
| `runpath-branch-cover.sh` | 补判据 5 那一支的反例 | 2 |

最后改动时全部重跑过一遍（约 74 项断言全过）—— 说明那些缺陷是真被修掉的，
不是被绕过去的。**改了这批代码的任何一处，就该重跑它们**。

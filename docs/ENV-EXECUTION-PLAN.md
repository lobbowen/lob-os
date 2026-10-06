# 基础环境与终端：统一执行方案

把此前分散定下的所有决定收成**一条执行线**。不是并列的清单，是有依赖顺序的工程。

依据：`docs/BASE-ENV-INVENTORY.md`（清单）· `docs/BASE-ENV-DECISIONS.md`（三项判断）
· `docs/DEV-ENVIRONMENT.md`（开发环境）· `docs/TERMINAL-PTY-PLAN.md`（终端）
· `docs/NATIVE-COMPONENT-UPDATE.md`（底座件更新）

---

## 一、目标形态

一个标准且稳定的系统环境，四层：

```
┌─ 能力层 ────────────────────────────────────────┐
│  终端（PTY + termios）  shell.exec 本地执行       │
│  内置终端窗口（给人用）                          │
├─ 工具层 ────────────────────────────────────────┤
│  bash  busybox  rg  jq  curl  git  sqlite3      │
│  clang lld binutils make cmake  python3          │
├─ 运行层 ────────────────────────────────────────┤
│  libc++_shared  libssl  libcrypto  libz          │
│  libpcre2-8  libiconv  libcurl                    │
│  运行时（程序）                                  │
├─ 数据层 ────────────────────────────────────────┤
│  ca-bundle  zoneinfo  locale 定义                │
└──────────────────────────────────────────────────┘
```

四条规矩（贯穿全部）：

1. **底座件原生打包进 APK**，不随商店分发
2. **商店件（node/npm/curl/git…）走商店安装**，代码不单独认识它
3. **开发环境必备**，无"选装"概念
4. **底座件有版本、走 OTA 更新、原件保留可回滚**

---

## 二、全部已定事项

### 2.1 底座件（`usr/bin`）

| 件 | 现状 | 动作 |
|---|---|---|
| `bash` | ✅ 自编 `libbash.so` | 保持 |
| `rg` | ✅ 自编 `liblobosrg.so` | 保持 |
| `busybox` | ❌ | **新增**（依赖 `libz`） |
| `jq` | ✅ 商店件（判据、sha256、升级路径齐备） | **保持商店**（原写「升入底座」，核实后撤回，见下） |
| `node` `npm` `npx` `pnpm` `curl` `git` `sqlite3` | ✅ 商店 | 保持（改动态链） |

**关于 jq 曾写「升入底座」——撤回。** 逐条核实三条判据，全部不成立：

1. 「几乎每个程序都会碰」？不是。只有处理 JSON 的程序才碰它。
2. 「缺了会静默出错」？不会。缺 jq 会明确报 `command not found`。
3. 「用户能自己装」？能，且商店通道已经把它做成一件完整件
   （`userland-verify.json` 里有判据、清单里有 sha256、能独立升级）。

底座的判据是「基础设施——缺了**静默**出错」。jq 缺了是**响亮**地缺。
把可响亮失败的能力塞进底座，是拿「完整」当借口扩大底座 —— 那会让底座
越来越难更新、每一件都变成「不能动」。

### 2.2 底座库（`usr/lib`）

| 库 | 现状 | 动作 |
|---|---|---|
| `libc++_shared.so` | ✅ `required=true`，但落错在 `usr/bin` | 保持，**改落 `usr/lib`** |
| `libssl` `libcrypto` | ⚠️ 两处各编一遍静态 | **新增共享**（编一次） |
| `libz` | ⚠️ 两处各编一遍静态 | **新增共享**（编一次） |
| `libcurl` | ⚠️ 两处各编一遍静态 | **新增共享**（编一次） |
| `libpcre2-8` | ❌ 无配方、无消费者 | **条件件**（rg 真用上才进） |
| `libiconv` | ❌ 无配方、无消费者 | **条件件**（git 真开 `NO_ICONV=0` 才进） |
| **`librivospty.so`** | ❌ | **新增**（终端，`self-c`） |

不进：`libstdc++.so.6`（Bionic ABI）· 自编 `linker64` · `libpthread`
· `libonig`（jq 的 vendored oniguruma 静态进去即可）

### 2.3 开发环境（`usr/lib/toolchain`）

必备：clang · lld · binutils（`as` `ld` `ar` `nm` `strip` `objdump` `readelf`）·
make · cmake · **sysroot**（头文件 + 静态库）· python3 · pkg-config

`usr/include/` → sysroot 软链

### 2.4 数据

| 件 | 动作 |
|---|---|
| `ca-bundle.pem` | 已有，建议归位 `usr/etc/` |
| ~~zoneinfo~~ | **取消** —— 实测三类消费者全部不需要，见下 |
| ~~locale 定义~~ | **取消** —— Bionic 上没有消费者，见下 |
| `/etc/services` | **不进**，放控制面板 |

#### zoneinfo —— 取消（本机实测推翻原判断）

原判「进底座」的三条理由，逐一实测后**都不成立**。本机就是 Android 设备
（OPPO PLP120 / Android 17），实测：

| 潜在消费者 | 实测结果 | 要 zoneinfo 吗 |
|---|---|---|
| Bionic C 程序 | `/system/usr/share/zoneinfo` 里只有 `tzdata` 与 `tz_version`，**零个时区文件**；而 `date` 仍输出 `CST+0800`、`getprop persist.sys.timezone` = `Asia/Shanghai` → **Bionic 读系统属性取时区，根本不查 zoneinfo 文件** | 不要 |
| node 的 `Intl` | `process.versions.icu` = **78.3**，full-icu；四个时区（Asia/Shanghai / America/New_York / Europe/London / Australia/Sydney）全部输出正确 | 不要（自带 ICU 时区数据） |
| 宿主 Kotlin | `TimeZone.getDefault()` / `ZoneId.systemDefault()` 走 Android 的 ICU，与 node 同源 | 不要 |

原判里「设备上的 zoneinfo 常常只有少数时区」「缺了会静默退回 UTC」两条本身也不对：
前者实际是**零**，后者不成立（Bionic 有独立通路）。

顺带查实一件事：给 Bionic 程序设 `TZDIR` 是**无效的** —— 那是 glibc 的变量，
Bionic 不认。这类「看起来该设、其实没用」的环境变量不能写进方案。

**真正该做的是另一件事**（见 2.6），不是自带时区库。

#### locale 定义 —— 取消

- **Bionic 没有 locale 目录**。`locale-archive` 是 glibc 的东西；`setlocale()`
  只支持 `C` / `POSIX` 与 UTF-8 变体，`LC_COLLATE` 完整排序规则不支持。
- 所以 `LANG=zh_CN.UTF-8` 对 C 程序要么失败要么静默退回 C；node 的 `Intl`
  不看 `LANG`（用自带 ICU 数据）。
- 真要支持 zh_CN 得走 ICU，而 **node 已经是 full-icu**（实测）。
- 已经做了的那件是对的：`LANG=C.UTF-8`（`RuntimeEnvironment.treeRootEnv` 里已设），
  它是 Bionic 上**唯一能真兑现**的值。

### 2.5 机制

- 底座件**版本 + OTA 更新**，原件保留作回退基线
- `curl` `git` **改动态链**
- 商店件八件**硬编码已清零**（19 道门禁钉住）

### 2.6 时区：真正要做的是让 `TZ` 生效（本机实测发现的缺口）

取消 zoneinfo 之后，实测发现了一个**真实的、影响用户的功能缺口**：

```
$ node -e 'new Date("2026-10-07T00:00:00Z").getHours()'
8                                   ← 不设 TZ
$ TZ=America/New_York node -e '…getHours()'
8                                   ← 设了 TZ，值没变
$ TZ=Asia/Tokyo node -e '…getHours()'
8                                   ← 还是没变
$ TZ=UTC node -e '…getHours()'
8
$ TZ=Europe/London node -e '…getHours()'
8
```

**node 完全忽略 `TZ` 环境变量**（四个时区全部返回同一个值）。这是 Linux 上的标准
机制（`TZ=Asia/Tokyo date` 会给 +0900），我们的程序宿主没有兑现它。

对照实测（同一时刻 `2026-10-07T00:00:00Z`）：

| 方式 | 结果 | 生效 |
|---|---|---|
| 不设 `TZ`，`getHours()` | 8 | — |
| `TZ=Asia/Tokyo`，`getHours()` | 8 | **否** |
| `TZ=UTC` / `TZ=America/New_York` / `TZ=Europe/London` | 8 | **否** |
| 进程内 `process.env.TZ=` 之后再读 | 8 | **否** |
| `Intl.DateTimeFormat(…, {timeZone:"Asia/Tokyo"})` | 09 | **是** |
| `Intl.DateTimeFormat(…, {timeZone:"America/New_York"})` | 20 | **是** |

所以正解不是「想办法让 `TZ` 生效」（`--icu-data-dir` 是 ICU **数据目录**，
不是时区设置，传它只会指错方向），而是：

1. **程序侧**：要按某时区显示时间，就显式给 `Intl.DateTimeFormat` 传 `timeZone`。
   这是唯一实测有效的路径，且不依赖任何宿主能力。
2. **宿主侧**（我们）：若要在不修改程序的前提下改变默认时区，
   只能起进程时注入 ICU 的默认时区（待实测哪一种真正生效）；
   **在测出来之前不写实现**。

判据（能测）：`TZ=Asia/Tokyo node -e '…getHours()'` 与不设时**必须不同** ——
这条现在是**红的**，它就是这项工作的验收线。

---

## 三、依赖顺序（不是清单，是工程）

```
第 0 阶段  底座共享库
              │  新增 scripts/build-base-libs.sh：编一次、编成共享
              │  libz / libssl+libcrypto / libcurl 三件
              │  顺手改对 PrefixProvisioner：DEPS 落 usr/lib（今天落错了）
              │  指纹纳入 userland-sources.json + 新脚本
              ↓
第 1 阶段  开发环境本体
              │  clang / lld / binutils / make / sysroot / python3
              │  sysroot 用 NDK 那套（Bionic ABI 兼容）
              ↓
第 2 阶段  商店件改动态链
              │  curl  → -lz -lssl -lcrypto
              │  git   → -lz -lssl -lcrypto -lcurl
              │  jq    → 保持 oniguruma 静态（无消费者不换）
              │  此刻底座里的库第一次被真正使用
              ↓
第 3 阶段  终端：PTY 原生件
              │  librivospty.so（openpty + termios + 窗口大小）
              ↓
第 4 阶段  shell.exec 改本地执行
              │  断开 ADB 也能执行命令 ← 根本问题在这解决
              │  支持 stdin 交互（build 这类工具离了这个不行）
              ↓
第 5 阶段  busybox 进底座（依赖第 0 阶段的 libz）
              ↓
第 6 阶段  ~~jq 升入底座~~ → **取消**（核实后判定 jq 是商店件，见 2.1）
              ↓
第 7 阶段  数据：zoneinfo / locale
              ↓
第 8 阶段  底座件版本化 + OTA 更新机制
              ↓
第 9 阶段  内置终端窗口（给人用，原生 View）
```

**顺序不能乱的理由**：

| 约束 | 为什么 |
|---|---|
| 库先于工具 | 工具动态链库，库不在则工具起不来 |
| sysroot 先于工具重编 | 重编要头文件 |
| PTY 先于 `shell.exec` | 改了 `shell.exec` 就要 PTY 可用 |
| libz 先于 busybox | busybox 的 gzip/tar 要 zlib |

---

## 四、每阶段做完得到什么

| 阶段 | 用户能做什么 | 程序能做什么 |
|---|---|---|
| 0 | — | 底座有三件共享库，openssl/zlib 不再编两遍 |
| 1 | — | 现场编译 C/C++（node-gyp 可用） |
| 2 | — | 装到 `usr/lib` 的库被多个件共享 |
| 3 | — | `isatty()` 为真、行编辑、窗口大小 |
| 4 | — | **不配对 ADB 也能执行命令** |
| 5 | `tar gzip grep sed awk ls cp mv vi` | 找得到基础命令 |
| 6 | ~~`jq` 全局可用~~ → 取消（商店件，用户自己装） | — |
| 7 | 时间与 locale 正确 | 本地时间正确、locale 不静默降级 |
| 8 | 底座件能打补丁，不换 APK | — |
| 9 | **在设备上有终端** | — |

**第 4 阶段是关键转折**：之前"程序要终端就报错"，做完就解决了。

---

## 五、验收判据（可测，不靠感觉）

| # | 阶段 | 判据 | 怎么测 |
|---|---|---|---|
| 1 | 0 | `usr/lib` 下有 `libz.so` `libssl.so` `libcrypto.so` `libcurl.so` | `adb shell ls` |
| 2 | 0 | 四件都带含 `$ORIGIN` 的 `DT_RUNPATH` | `readelf -d`（判据 5 已自动覆盖） |
| 3 | 0 | 共享库不重复编 | 构建日志里 openssl 的 `Configure` 只跑一次 |
| 4 | 0 | `libc++_shared.so` 落在 `usr/lib` 而非 `usr/bin` | `adb shell ls` |
| 5 | 1 | 关掉无线调试，程序仍能执行命令 | `shell.exec` |
| 6 | 2 | 程序内 `process.stdout.isTTY === true` | 跑 node 一行 |
| 7 | 3 | `^C` 能中断、`^D` 能 EOF | 终端里实测 |
| 8 | 3 | 改窗口大小后 `TIOCGWINSZ` 返回新值 | 终端里实测 |
| 9 | 3 | `bash`、`vi` 能进交互模式 | 终端里实测 |
| 10 | 2 | curl/git 在没有静态 openssl 的情况下工作 | 断网跑一次 |
| 11 | 1 | node-gyp 能现场编译一个 C 扩展 | 装一个带原生模块的包 |
| 12 | 7 | `new Date().toString()` 返回本地时区 | 跑一行 |
| 13 | 8 | 底座件 OTA 更新后可回滚 | 装一个新版本再退回 |
| 14 | 全 | 19 道门禁全绿 | CI |

---

## 六、第 0 阶段的真实缺口（已核实）

### 6.1 四个库进底座，两个降为条件件

初版把底座运行层写成六件。逐个核实后要**改成四件** —— 另两件现在没有真实消费者，
按既定判据「有真实消费者才补」不能进底座。

| 库 | 现在怎么编的 | 有没有消费者 |
|---|---|---|
| `libz` | `build-userland-curl.sh` / `-git.sh` 各自编**静态** `.a` | **有** —— curl 与 git 都链它 |
| `libssl` `libcrypto` | 同上，`./Configure ... no-shared` 编**静态** | **有** —— 同上 |
| `libcurl` | 同上，`--disable-shared --enable-static` 编**静态** | **有** —— git 链 `-lcurl` |
| `libpcre2-8` | **无配方**（钉值表无此键） | **无** —— rg 以默认 features 编（脚本无 `--features`，rg 默认不含 pcre2）；jq 用的是 vendored oniguruma，不用 pcre2 |
| `libiconv` | **无配方** | **无** —— git 的构建参数写着 `NO_ICONV=1` |

「静态链重复」是真的：openssl 在 `build-userland-curl.sh` 和 `build-userland-git.sh` 里
**各编了一遍**，两个不同的 `work/` 目录，最后各自静态进各自的二进制。
第 0 阶段做的就是：编一次、编成共享、放底座 `usr/lib`，三处共用。

`libpcre2-8` 与 `libiconv` 改为**条件件**：等 rg 真的以 `--features pcre2` 编、
或 git 真的开 `NO_ICONV=0` 时再进底座，且进底座前要先有门禁钉住那个条件。

### 6.2 配方不在 `build-native-capabilities.sh`，在 `build-userland-*.sh`

`scripts/build-native-capabilities.sh`（249 行）实测只支持两类：

| 类 | 编法 | 在册 |
|---|---|---|
| **self-c** | `"$CC" -shared -fPIC -O2 <自有 .c>` | flock / posix / ptyprobe |
| **upstream 配方** | 下载 tarball → 补 bionic 桩 → `configure --host=aarch64-linux-android` → `make` | bash |

而 zlib / openssl / curl 的配方**已经存在**，在 `build-userland-curl.sh` 与
`build-userland-git.sh` 里，源、版本、校验全走 `scripts/fetch-pinned.sh` +
`scripts/userland-sources.json`（zlib 1.3.2 / openssl 3.6.3 / curl 8.22.0 / git 2.55.0）。

所以第 0 阶段**不是新增第三类配方**，是三件事：

| # | 事 | 落点 |
|---|---|---|
| 1 | 把 `curl.sh` / `git.sh` 里重复的静态编法提成一个共享的**编共享库**的函数 | 新增 `scripts/build-base-libs.sh`，两个脚本改为调它 |
| 2 | 产物从 `work/` 落 `jniLibs`，进 `.github/native-capabilities.txt` | `NativeAssetRegistry.kt` 加四条，`buildTier="upstream"` |
| 3 | 三处 `--disable-shared --enable-static` / `no-shared` 改成编共享 + `-Wl,-soname` | 见 6.4 的 RUNPATH 判据 |

静态库也要留 —— git 静态链 curl 是为了单文件自足；改动态链是**第 2 阶段**的事，
第 0 阶段只保证底座共享库编得出来、装得对，不动商店件的链接方式。

### 6.3 一个已存在的实质缺陷：库没落 `usr/lib`

`PrefixProvisioner.provision`：

```kotlin
for ((items, dir) in listOf(BINS to binDir(ctx), DEPS to binDir(ctx)))
```

两行都落 `binDir(ctx)` —— `DEPS`（C++ 运行库 `libc++_shared.so`）现在落在 `usr/bin`，
不是 `usr/lib`。而 `RuntimeEnvironment.libSearchPath` 里明确加的
`PrefixProvisioner.libDir(ctx)` 今天**指向一个空目录**。

现在侥幸能用，因为 `libc++_shared.so` 由 `nativeLibraryDir` 找到，
而它是 `libSearchPath` 的第一项。但「库在 `usr/lib`」这条 Linux 判据
在代码里**没有兑现**，而 `libDir` 已有活的消费者（`SupplyProvisioner.toolchainDir`
= `usr/lib/toolchain`）。第 0 阶段第一件事就是把它改对 —— 改对之后四件新库才有地方放。

### 6.4 判据 5 会拦住新库

`scripts/verify-runtime-elf.sh` 判据 5：凡依赖「同目录随包库」的件，
必须带含 `$ORIGIN` 的 `DT_RUNPATH`，否则判硬红（bionic 忽略 `DT_RPATH`）。

现有 `bash` 的编法**没加** `--enable-new-dtags` 也没加 `-rpath` —— 它不依赖同目录随包库，
所以过得去。四件新库**依赖同目录库**（`libcurl` → `libssl` + `libz`），
所以配方里必须显式带 `-Wl,--enable-new-dtags -Wl,-rpath,'$ORIGIN'`，
否则这一阶段会红。这是第 0 阶段新增的唯一编译开关。

### 6.5 固化指纹漏掉了配方参数

`scripts/native-capabilities-fingerprint.sh` 的哈希范围只有两项：
`container/native/**` 与 `scripts/build-native-capabilities.sh` 自身。

上游配方（bash 的 `BASH_VER`、curl.sh/openssl 的编译开关）**都不在指纹里**。
所以今天改 `BASH_VER` 不会触发重固化，CI 会继续用旧的固化产物；
新加的 `build-base-libs.sh` 更不会被计入。第 0 阶段要把
`scripts/userland-sources.json` 与 `build-base-libs.sh` 纳入指纹。

### 6.6 其余发布侧事项

| 事 | 位置 |
|---|---|
| 开发环境件声明 | `NativeAssetRegistry`，`buildTier` 加一档说明「体积大、不进 APK」 |
| 生成 `native-manifest.json` | 新增，底座件的版本与下载地址（第 8 阶段消费） |
| curl/git/jq 改动态链 | **第 2 阶段**，不在第 0 阶段 |
| 流水线调顺序 | 库 → 工具；PTY → `shell.exec` |

`components/native` 的定位是「小体积内核零件」，开发环境体积是它的十倍量级，
**不能塞进同一个类目**，要另立。

---

## 七、本轮不动代码

这是执行方案，不是实施记录。确认顺序与判据后再动手 —— 从第 0 阶段开始。

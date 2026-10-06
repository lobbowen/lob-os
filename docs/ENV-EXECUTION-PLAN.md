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

### 2.6 时区：`TZ` 不生效 —— 但那是 node 的行为，不是我们的缺口（更正）

早先这里写的是「实测发现的、影响用户的**功能缺口**」，并把修复挂在宿主上。
本轮重新核实后**更正**：那是 node 自身的行为，宿主侧没有 bug。

```
$ TZ=Asia/Tokyo node -e 'console.log(process.env.TZ)'
Asia/Tokyo                            ← TZ 确实传进了进程
$ TZ=Asia/Tokyo node -e 'Intl.DateTimeFormat().resolvedOptions().timeZone'
Asia/Shanghai                         ← 但 node 忽略它
$ TZ=Asia/Tokyo node -e 'new Date("2026-10-07T00:00:00Z").getHours()'
8                                     ← 跟着系统时区走，不跟 TZ
```

`TZ` 在环境里读得到、`Intl` 仍报系统时区 —— **node 就是不读 `TZ`**
（Android 上它靠 ICU 取默认时区）。宿主改不了，除非改 ICU 或给 node 打补丁。

**而我们也不需要它生效**：

| 程序想要 | 正确做法 | 实测 |
|---|---|---|
| 按某个特定时区显示时间 | 显式给 `Intl.DateTimeFormat` 传 `timeZone` | Tokyo → 09、New_York → 20，**有效** |
| 按本地时区 | 用 `Intl` 的默认 | 它取系统时区（实测 `Asia/Shanghai`，来自 Android 系统设置）✓ |
| 按 POSIX TZ 字符串解析 | 需要显式解析，node 不做 | — |

宿主侧现状核过，**是对的**：

- `LANG=C.UTF-8`（`RuntimeEnvironment.treeRootEnv`）—— Bionic 上唯一能真兑现的值
- **不设 `TZ`** —— 设了就是替用户选时区，跨时区场景下是错的
- node 从 ICU 拿系统时区，实测 `Asia/Shanghai` 与 Android 设置一致 ✓

所以**这一条不列入待做**。原先把它记成「宿主缺口」是我把 node 的行为当成了我们的
责任 —— 判据要分清「谁的问题」：**宿主能修的才列进去，不能修的写清楚为什么**。

## 三、依赖顺序（不是清单，是工程）

```
第 0 阶段  底座共享库
              │  新增 scripts/build-base-libs.sh：编一次、编成共享
              │  libz / libssl+libcrypto / libcurl 三件
              │  顺手改对 PrefixProvisioner：DEPS 落 usr/lib（今天落错了）
              │  指纹纳入 userland-sources.json + 新脚本
              ↓
第 1 阶段  开发环境本体
              │  clang / lld / binutils / cmake / make / pkg-config / sysroot / python3
              │  sysroot 用 NDK 那套（Bionic ABI 兼容）
              │
              │  已完成（配方 + 判据齐备，待 CI 实编）：
              │    sysroot（1a）· make · cmake · pkg-config · python3
              │  已完成的配套：
              │    $PREFIX/include 软链 · 别名软链（多命令件装完在 PATH 里可见）
              │  待 CI：llvmVersion → 按它重钉 LLVM → 跑 build-userland-llvmtoolchain.sh
              │  详见下面「阶段1c 的三步解锁链」
              ↓
第 2 阶段  商店件改动态链
              │  curl  → -lz -lssl -lcrypto
              │  git   → -lz -lssl -lcrypto -lcurl
              │  jq    → 保持 oniguruma 静态（无消费者不换）
              │  此刻底座里的库第一次被真正使用
              │
              │  状态：**原方式已撤回**（核实后判定它与当前架构冲突）。
              │  核实到的结构性事实（2026-10-07）：
              │    · 商店件在 build-userland.yml 编，底座 .so 在 build-apk.yml 编
              │    · 两个 workflow **独立**，商店件构建时拿不到底座那批 .so
              │    · 若让两边各编一份，字节可能不同 —— 那与阶段8 的
              │      「底座件有版本、单一事实源、可回滚」直接冲突
              │    （即：商店件链的那份 libssl.so 与设备上跑的那份不是同一个）
              │
              │  对齐 Linux 的真实形态：**发行版不重编已装的包**。
              │    apt 装 curl 链的是 libssl.so.1.1；后来升级 openssl，
              │    curl 不被重编，靠**同名替换**继续工作 —— 这正是 SONAME 的意义。
              │    我们的阶段8（原件保留 + 软链切换）提供的正是这个替换能力。
              │
              │  所以底座那四件 .so 的**真实消费者**不是既有商店件，而是：
              │    ① 程序自己编出来的原生模块（node-gyp 编译 .node 时 -I/-L）
              │    ② 用户在设备上跑 ./configure，探测 libssl/libz 后链上
              │  两者都只需要「设备上有 .so」，而那已经成立：
              │    libSearchPath 含 $PREFIX/lib，DEPS 铺了 libz/libssl/libcrypto/libcurl。
              │
              │  换句话说：**阶段2 的目标（底座库被真正使用）已经达成**，
              │  只是消费者不是 curl/git —— 那是本方案最初写错的地方。
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
              │  状态：**已撤回**（核实后判定是假需求）——
              │    Bionic 读系统属性取时区（/system 那份 zoneinfo 实测零个时区文件）、
              │    node 是 full-icu 自带时区数据、locale-archive 是 glibc 的东西。
              │    曾记入 2.6 说「TZ 被 node 忽略 = 宿主缺口」—— **那一处也更正了**：
              │    TZ 不生效是 node 自身行为，宿主没有 bug（详见 2.6 的对照实验）。
              ↓
第 8 阶段  底座件版本化 + OTA 更新机制
              │  状态：**已完成**（底座件版本化 + OTA 更新 + 回滚，dc64173）——
              │    原件在 APK 永远不动 = 回退基线；更新落 toolchain/<id>/<版本>/；
              │    入口软链切换；回滚 = 删软链让 provision 重建。
              ↓
第 9 阶段  内置终端窗口（给人用，原生 View）

**状态：已完成**（d1ac5b1）—— 原生 View + ANSI 仿真
（`ui/TerminalScreen.kt` / `TerminalView.kt` / `TerminalActivity.kt`），
复用同一个常驻 `PtySession` 宿主。判据 5（`vi` 能编辑）与 6（颜色与中文正确）
要求做 ANSI 仿真 —— 不解析转义序列时屏幕上就是 `[?2004h` 这种垃圾。
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

## 四·五、阶段1c 的三步解锁链（已逐段验过，只差 CI 那一格）

阶段1c（编 clang）卡在一个本机答不出的问题：NDK r29 内置的 LLVM 是哪个版本。
**不要猜** —— 猜错的代价是编出来的 clang 与 sysroot 悄悄不同源且无人察觉。

链是三步，每步都能独立验证：

| 步 | 做什么 | 现在能做吗 |
|---|---|---|
| 1 | 跑 `scripts/verify-ndk-llvm.sh`，从报错里读到真值 | 只能 CI（需要真 NDK） |
| 2 | 把真值填进 `userland-sources.json` 的 `llvmVersion` | 拿到真值即可 |
| 3 | **按该版本重钉 LLVM 源码**（版本 + sha256） | 之后，但**不必下载**（见下） |

**第 3 步不需要下载 171 MiB。** GitHub 的 release API 在每个资产上给
`digest` 字段（`sha256:…`），所以「取 sha256」是读一次 API 而不是拉一次大包：

```
GET https://api.github.com/repos/llvm/llvm-project/releases/tags/llvmorg-<版本>
  → assets[] 里 name ~ /llvm-project-.*\.src\.tar\.xz$/ 的那条
    digest = "sha256:6898f963c8e938981e6c4a302e83ec5beb4630147c7311183cf61069af16333d"  （20.1.8 的实测值）
```

**核过这个 digest 可不可信**：拿同一 release 里最小的资产（8 KB）实测对照——
先跟重定向（GitHub release 资产是 302 到 CDN）再算 sha256，
结果与 API 的 `digest` **完全一致**；且该 release 的 62 个资产**全部**带 digest，
不是个别字段。

（顺带记一笔我的工具错误：第一次对照时 node 脚本**没跟重定向**，拿到 0 字节
（`e3b0c442…` 就是空内容的 sha256），于是我一度判「API 的 digest 不能信」。
错在工具不在 API —— 与本会话其它几次一样。）

顺带一条纪律：**当前钉的是 23.1.3（GitHub 的 latest）**，那**不是**按 NDK 挑的。
若 NDK r29 内置 20.x，则该换的是 20.1.8 那一系。**但不能凭「更可能」就换** ——
等 CI 报出真值再改，否则又是一次「先猜后核实」。

（本会话早前为 23.1.3 算 sha256 是**下载 171 MiB 后实测**的；那时没先查
API 有没有 `digest`。下次先查字段，再决定要不要下载。）

`build-userland.yml` 里有独立的 `ndk-llvm` job 专门跑第 1 步，并且：

- **不进 `manifest` 的 needs** —— 它红不断商店件那条链（那 8 件与 llvmVersion 无关）
- `continue-on-error` —— 这一步**当前的产出就是报错里的真值**，红是取答案的途径
- 额外把实测版本写进 step summary —— CI 一跑答案就在摘要里

第 2、3 步的判据已在本地用假 NDK 验过：

- 填 `llvmVersion = 20` 后，`verify-ndk-llvm.sh` 转绿（`NDK 29.0.14206865 / LLVM 20.1`）
- 配方拿**钉的 23.1.3** 与 **NDK 的 20** 对比 → **正确地拦住**（不配就 die）

也就是说：现在钉的 LLVM 23.1.3 与 NDK r29 **不配**，跑配方会被挡住——
这是判据在起作用，不是配方坏了。真正的做法是按 NDK 报的版本重钉 LLVM 源码。

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

---

## 六、当前状态（截至 `fd0caf0`）

| 阶段 | 状态 | 提交 |
|---|---|---|
| 0 底座共享库 | ✅ | `106c85c` |
| 1a sysroot | ✅ | `14815ad` |
| 1d make/cmake/pkg-config/python3 | ✅ 配方+判据齐备，待 CI 实编 | `7fc912d` `94ae7a9` `776a2e9` `2db801c` |
| 1c clang/lld/binutils | ⏳ 只差 `llvmVersion` 一格 | `f6328e5` `116669e` |
| 2 商店件改动态链 | ↩️ 原方式撤回（目标已由阶段8 达成） | `c4b8e24` |
| 3 PTY | ✅ | `11fcf8d` |
| 4 shell.exec 改本地 | ✅ | `11fcf8d` |
| 5 busybox | ✅ | `c78fa1d` |
| 6 jq 升底座 | ↩️ 撤回 | — |
| 7 zoneinfo/locale | ↩️ 撤回，改记 TZ 缺口 | `af8e4f3` |
| 8 底座件版本化+OTA+回滚 | ✅ | `dc64173` |
| 9 内置终端窗口 | ✅ | `d1ac5b1` |

### 唯一剩余：`llvmVersion`（需要 CI，本机答不出）

本轮**已把三步解锁链逐段验过**（用一个自称指定 LLVM 版本的假 NDK 驱动，
因为本机没有编译器也没有真 NDK）：

1. `verify-ndk-llvm.sh` 判红，**报错里带实测值** ✅
   → `::error title=钉值表没有 llvmVersion::NDK 29.0.14206865 内置 LLVM 20.1.8。…`
2. 填进 `userland-sources.json` 后转绿 ✅
3. 版本不符时判红（实测 19.0.2 / 20.0.0 对钉 20.1.8 均判红）✅

第 2 步之后还需按该版本用 `pin-github-release.js` 重钉 LLVM 源码
（**不必下载** 171 MiB —— GitHub release API 的 `digest` 字段可用）。

**本轮更正过一次的判断**：我一度以为 `case "$PINNED_LLVM" in "$WANT_LLVM"|"$WANT_LLVM".*)`
是反的，枚举六种组合后确认它是**对的** —— 钉得短（`20`）允许源码更长（`20.1.8`），
钉得完全匹配也过，不同 patch 才红。那次「红」是我的测试填错了格，不是代码错。

### 本机仍然做不到的

**没有编译器**（gcc/clang/cc 全无、无 make/cmake/xz）。
所以「19+ 道门禁全绿」**不等于**能编译通过 —— 本会话已两次因缺编译器漏过编译错误。
阶段 1c/1d 的真编只能在 CI 上验。

---

## 七、更正：NDK r29 的 LLVM 版本**不是语义版本**（本轮查上游得到）

### 查到了什么（官方 changelog，不是猜）

`android.googlesource.com/platform/ndk/+/mirror-goog-main-ndk/docs/changelogs/`：

| NDK | release notes 原文 |
|---|---|
| r27 | `Updated LLVM to clang-r522817.` |
| r28 | `Updated LLVM to clang-r530567e/d/b.` |
| **r29** | **`Updated LLVM to clang-r563880c.`** |
| r30 | `Updated LLVM to clang-r574158c.` |
| r31 | `Updated LLVM to clang-r596125.` |

**NDK 按 AOSP clang 修订号（`clang-rNNNNNN`）记录 LLVM，不是 LLVM 语义版本。**

顺带核实到的两条：
- `ndk;29.0.14206865` 与我们钉的 `ndkVersion` **完全一致**（来自 `repository2-3.xml` 的
  `<remotePackage path="ndk;29.0.14206865">`，以及 r29 release 的 gradle 片段）。
- release notes 指向 toolchain 内的 `clang_source_info.md` 才知道确切来源 ——
  **那个文件在 NDK zip 里，本机没有 NDK 就读不到**。

### 这推翻了什么

`verify-ndk-llvm.sh` 现在抓的是 `clang version ([0-9][0-9.]*)`，
而 `fetch-pinned.sh --llvm` 只接受 `/^[0-9]+(\.[0-9]+){0,2}$/`。

实测这个校验对真实取值的态度：

```
20.1.8      通过
20          通过
r563880c    判非法形态     ← NDK 真正给的形态
563880c     判非法形态
```

所以**「填上实测值」这一步在 r29 上根本走不通** ——
不是我不填，是填进去会被自己的形态校验判红。

### 因此要改什么（尚未实施，等定）

1. `llvmVersion` 这一格要能记 `clang-rNNNNNN`，且 `fetch-pinned.sh --llvm`
   的形态校验要接受它。
2. 但**编 LLVM 源码仍然需要语义版本**（`llvmorg-20.1.8` 那种 release tag）。
   `clang-r563880c` 对应哪个 `llvmorg-*` **需要真 NDK 里的 clang_source_info.md**
   才能定，而本机拿不到 —— 这正是原来「只能 CI」的那一格的真实原因：
   **不是缺一个版本号，是缺一份「修订号 → 语义版本」的映射**。
3. 所以三步解锁链要改成四步：先从 CI 拿到 `clang_source_info.md` 里的语义版本，
   再填 `llvmVersion`，再重钉。

**不要再凭「NDK r29 大概是 LLVM 20」去填 20.1.8。**
上一轮我钉的 `20.1.8` 现在**没有任何依据**支撑它与 r29 同源 —— 它是猜的。

---

## 八、再查上游：AOSP clang 的版本机制（比第七节更确定）

第七节只查到「NDK 记的是 clang-rNNNNNN」。本轮把**机制**也查清了，
来源是 AOSP 自己的仓（不是猜）：

### 1. AOSP clang 的版本号是怎么来的

`android.googlesource.com/platform/external/clang/+/refs/heads/main/version.py`：

```python
major = '3'
minor = '8'
patch = '275480'
```

配 `clang-version-inc.py`：

```python
version_string = '%s.%s.%s' % (version.major, version.minor, version.patch)
```

**所以 clang 的版本串 `3.8.275480` 里，patch 段就是 clang 修订号。**
`external/clang/README.version` 写得更直白：

```
Version: Rolling from upstream + cherry-picks
```

—— AOSP clang **按 commit 滚动**，不是按 `llvmorg-<ver>` 发布。

### 2. 「修订号 ↔ 语义版本」的映射文件叫什么

`toolchain/llvm_android/update-prebuilts.py`（第 119-126 行）：

```python
version_file_path = os.path.join(clang_dir, 'AndroidVersion.txt')
contents = [l.strip() for l in version_file.readlines()]
full_version = contents[0]          # 例如 '7.0.1'
revision = contents[1].split()[-1]  # 例如 'r326829'
```

注释原话：`# e.g. for contents: ['7.0.1', 'based on r326829']`

**`AndroidVersion.txt` 就是那张映射表** —— 第一行语义版本、第二行修订号。
它只存在于 **clang 预编包内**，不在 git 树里（实测 `external/clang` 下 404），
预编包又只在 android-llvm CI 的内部存储上（`fetch_kokoro_prebuilts.py`
指向 `pantheon.corp.google.com`）。所以**公开渠道查不到 r29 的那一行**。

### 3. 与 `clang_source_info.md` 的关系

NDK release notes 说「See `clang_source_info.md` in the toolchain」，
而 `update-prebuilts.py` 读的是 `AndroidVersion.txt` —— **两个文件同一个作用**
（给出 LLVM 语义版本 ↔ 修订号的对应）。所以第 3 步「读 clang_source_info.md」
的思路是对的，只是文件在 NDK zip 里。

### 4. 结论：缺的到底是什么（比第七节更精确）

不是「缺一个版本号」，而是**缺一张映射表**，且这张表**只在预编包/NDK zip 里**。

这意味着 `llvmVersion` 那一格**只能靠真 NDK 填**，无论用什么方法。
本机拿不到 NDK，所以这一格在本机**永远填不上** —— 这不是本轮没做完，
是它本来的性质。

### 5. 顺带核实到的一条硬事实

`ndk;29.0.14206865` 与我们钉的 `ndkVersion` 一致，来源是官方
`repository2-3.xml` 的 `<remotePackage path="ndk;29.0.14206865">`。

### 6. 顺带排除掉一条「看起来能绕过去」的路（省得下轮再问）

有人会问：**NDK 里不是就有 clang 吗？直接拿来做设备上的编译器不行吗？**
不行，两条理由，都是上面查出来的事实：

1. **NDK 的 clang 是「宿主 x86_64 → 目标 aarch64」的交叉编译器**，
   宿主是 PC。它在设备上跑不起来（我们要的是「设备本机的编译器」）。
2. **没有官方的「Android 当宿主」的 clang 预编包**。
   `external/clang/build.py` 里 `build()` 接受 `prebuilts_path` /
   `prebuilts_version` 并传 `LLVM_PREBUILTS_BASE` 给 make ——
   **AOSP 自己也是下载宿主预编包**，不为 Android 宿主构建。

所以「直接抄 NDK 的 clang」这条路不存在，交叉编译是唯一形态，
与 Termux 的做法一致（它也是在 Android 上自编 clang）。

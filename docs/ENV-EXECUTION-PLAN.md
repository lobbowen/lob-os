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

## 四·五、阶段1c 的三步解锁链 —— **已完成，见第八/九节**（本节保留原始判断过程）

~~阶段1c（编 clang）卡在一个本机答不出的问题~~ —— **答案是 21.0.0，已填入并让判据转绿。**
下面保留当时的判断过程（当时确实答不出，第九节记了怎么答出来的）。
**不要猜** —— 猜错的代价是编出来的 clang 与 sysroot 悄悄不同源且无人察觉。

链是三步，每步都能独立验证：

| 步 | 做什么 | 现在能做吗 |
|---|---|---|
| 1 | 跑 `scripts/verify-ndk-llvm.sh`，从报错里读到真值 | ✅ **已做** —— 不用 CI，见第九节（Range 请求取 2837 字节） |
| 2 | 把真值填进 `userland-sources.json` 的 `llvmVersion` | ✅ **已做** —— 填 `21.0.0` |
| 3 | **按该版本重钉 LLVM 源码**（版本 + sha256） | ✅ **已做** —— 钉 `21.1.0`，sha256 与 API 对账一致 |

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
| 1c clang/lld/binutils | ✅ 前提判据通过（llvmVersion=21.0.0 已实测填入），待 CI 实编 | `f6328e5` `116669e` `ec8ebce` `d4f0c2b` |
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

---

## 九、`llvmVersion` 查到了 —— 用 HTTP Range 从 NDK zip 里只取那 2837 字节

第八节说「公开渠道查不到，只在 NDK zip 里」。本轮发现**不用下载 783 MB**：
`dl.google.com` 支持 `Accept-Ranges: bytes`，而 zip 的中央目录在**文件末尾**。

### 做法（四步，全部只用 Range 请求）

```
1. HEAD  → content-length = 783549481, accept-ranges = bytes
2. Range 取尾 256 KiB → 找 EOCD（PK\x05\x06）
   → 中央目录 10305 条目，偏移 781785425，大小 1764034
3. Range 取那 1764034 字节 → 逐条解析中央目录，找目标文件名
   → android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/clang_source_info.md
     local header 偏移 341860359, csize 2737
   → android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/AndroidVersion.txt
     local header 偏移 355392783, csize 100
4. Range 取 local header（30 字节定长 + 文件名 + 额外字段）算出数据起点，
   再 Range 取 csize 字节 → **压缩方法是 8（deflate），要 inflateRaw**
```

**总下载量约 2 MiB，不是 783 MiB。**

### 拿到的权威内容

`clang_source_info.md` 第一行：

```
Base revision: [386af4a5c64ab75eaee2448dc38f2e34a40bfed0](https://github.com/llvm/llvm-project/commits/386af4a5c64ab75eaee2448dc38f2e34a40bfed0)
```

后面是 llvm_android 的 patch 清单（每条都带 commit）。

`AndroidVersion.txt` 也取到了（100 字节）。

### 关键：这个 commit 属于哪条 release 线

用 GitHub compare 逐条问（`ahead`/`behind` 的含义别搞反）：

| 比较 | status | ahead | behind | 读法 |
|---|---|---|---|---|
| `release/20.x...SHA` | diverged | 414 | 429 | 既不在 20.x 上 |
| **`release/21.x...SHA`** | **behind** | **0** | 19254 | **SHA 就是 21.x 上的点** |
| `release/19.x...SHA` | diverged | 19920 | 458 | 不在 19.x 上 |
| `SHA...llvmorg-21.1.8` | ahead | 19254 | 0 | SHA 领先于 21.1.8（同一条 21.x 线） |

**结论：NDK r29 的 LLVM 来自 `release/21.x`，不是 20.x。**

### 所以要更正两处

1. **`llvmVersion` 应填 `21`（或 `21.1`）** —— 语义版本的前缀匹配允许短填。
2. **原先钉的 `sources.llvm.version = 20.1.8` 是错的，差一个大版本。**
   它是我上一轮**猜**的（当时的理由是「NDK 29 大概配 LLVM 20」），
   现在有实证推翻了它。**21.x 上该 commit 落在 21.1.0~21.1.8 之后**
   （对每个 tag 都是 ahead:0/behind>0），所以它是 21.1.x 之后的快照 ——
   要编源码应按 **21.x 线**取，而不是任取一个 21.1.x tag。

### 填上之后：拿到的是 `21.0.0`，且**上游没有 llvmorg-21.0.0 这个 tag**

`AndroidVersion.txt` 的完整内容（112 字节，实测）：

```
21.0.0
based on r563880c
for additional information on LLVM revision and cherry-picks, see clang_source_info.md
```

但实测上游 tag 列表是：

```
llvmorg-21-init, llvmorg-21.1.0, 21.1.1, 21.1.2 … 21.1.8
```

**没有 `llvmorg-21.0.0`。** 于是原来的「按完整版本串前缀匹配」判据全判错：

| 钉的源码 | 对 NDK 21.0.0 | 该不该过 |
|---|---|---|
| 21.1.0 | 红 | **该过**（同一条 21.x 线） |
| 21.1.8 | 红 | **该过** |
| 20.1.8 | 红 | 该红 |

**所以判据改成比 major（= LLVM 的 release 线）。** 这不是放松判据，
而是把「同源」这个词落到正确的粒度上 —— LLVM 的 major 就是 release 线，
20.x 与 21.x 是两条独立的线，同线内取哪个 21.1.x 都是同源。

反例仍能拦住：把源码改回 20.1.8 → `红：钉 20.x 对 NDK 21.x` ✅

### 最终钉值（已与 GitHub API 独立对账）

```json
"llvmVersion": "21.0.0",
"sources.llvm": {
  "version": "21.1.0",
  "sha256": "1672e3efb4c2affd62dbbe12ea898b28a451416c7d95c1bd0190c26cbe878825"
}
```

对账方式：再读一次 release API，拿 `assets[].digest` 与仓内比对 → **一致**。

用真实数据（NDK `AndroidVersion.txt` = `21.0.0` + `clang --version` = `21.0.0git r563880c`）
驱动 `verify-ndk-llvm.sh` → **rc=0**，阶段1c 的前提判据通过。

---

## 十、本轮补上：`llvmtoolchain` 的「产出之后」三步

11 件商店件每件都走四步：

```
产出 → 件形态校验 → 打包+sha256 → 发布到对象存储
```

`llvmtoolchain` 原来只有第一步。后果不是「编不出来」，而是更隐蔽的一种：

> **编出来的东西留在 `dist/` 目录里但从未打包** ——
> manifest 收不到它、控制面板看不到它、用户装不到它，
> 而 **job 全程绿**。「编成功了但没人拿得到」。

（`check-userland-manifest-drift.js` 也拦不住：它比的是**已发布清单**之间的漂移，
一件从没被打包过的件根本不在比对范围内。）

所以补上三步，与其余 11 件对齐：

| 步 | 做什么 | 为什么 |
|---|---|---|
| 件形态校验 | `verify-userland-artifact.sh llvmtoolchain` | 逐个别名验 aarch64 ELF / 解释器形状 |
| 打包 + sha256 | `package-userland.sh llvmtoolchain` | 没有它就没有可分发的件 |
| 别名映射核对 | 对着真产物验 `package.json` 的 8 个别名 | 装侧建链与发侧写清单读同一份 |

### 这三步的判据都实测过（不是照抄 11 件那条）

**`verify-userland-artifact.sh` 对 `llvmtoolchain` 的行为**（实测三种件）：

| 造的件 | 结果 |
|---|---|
| 8 个 shebang 别名（`#!/bin/sh`） | rc=0，逐个报 `[ok]` |
| x86-64 的假 ELF（e_machine=0x3E） | **rc=1** `产物不是 arm64/aarch64` |
| dist 空（没编） | rc=1 `缺产物::入口 dist/bin/clang 没产出` |

x86-64 那条正是「拿宿主链接器编了」的形态 —— 而这一件**只可能**是交叉编出来的，
所以这条判据对它比对其它 11 件更要紧。

**别名核对那段的反例**（不验反例就不知道它会不会恒真）：

```
① 少了 clang++                 → 判红「缺 clang++」
② 别名指向不存在的文件          → 判红「llvm-nm 指向不存在的 bin/llvm-nm」
③ 8 个别名齐全且都指向真实文件  → 通过
```

### 仍然只有 CI 能验

本机**没有编译器**，所以「真编一次」在本轮依旧做不到。
补上这三步的意义是：**一旦 CI 编出来，后面三步会立刻指出它形态对不对**，
而不是编完就绿、然后几个月后才发现没人拿得到。

---

## 十一、CI 真编之前：把「runner 上有什么」核过一遍

配方依赖三样宿主工具和一个环境变量。逐条对着 **GitHub 官方 runner 镜像清单**
（`actions/runner-images`，Ubuntu 24.04，Image 20260927.320.1）核实：

| 依赖 | 配方在哪用 | runner 上有没有 | 结论 |
|---|---|---|---|
| `xz` / `unxz` | 解 `.tar.xz` 源码包 | `xz-utils 5.6.1+really5.4.5-1ubuntu0.3` 在 apt 包清单里 | ✅ 有 |
| `ninja` | `cmake -G Ninja` | Tools 段 `Ninja 1.13.2` | ✅ 有 |
| `cmake` | configure/build/install | Tools 段 `CMake 3.31.6`（apt 也会另装一个） | ✅ 有 |
| `ANDROID_NDK_LATEST_HOME` | 交叉编译器所在 | `/usr/local/lib/android/sdk/ndk/29.0.14206865` | ✅ **与我们的 `ndkVersion` 完全一致** |

所以 CI 那一步「只 apt 装 cmake」是够的 —— ninja 与 xz runner 自带。

**注意 runner 上同时有三个 NDK**：

```
ANDROID_NDK_LATEST_HOME = 29.0.14206865   ← 我们要的
ANDROID_NDK_HOME        = 27.3.13750724   ← 那个是 default
ANDROID_NDK_ROOT        = 27.3.13750724
```

三个里两个是 27.x。配方与 `verify-ndk-llvm.sh` 都优先读 `ANDROID_NDK_LATEST_HOME`
（`build-userland.yml` 的「定位 NDK」步也显式校验那一条），
所以不会误用 27.x —— 但这正说明**「哪一条变量」必须写死，不能靠 fallback 顺序碰运气**。

### 顺带核实了钉值本身（不用下载 158 MiB）

```
HEAD https://github.com/llvm/llvm-project/releases/download/llvmorg-21.1.0/llvm-project-21.1.0.src.tar.xz
  → 302 → release-assets.githubusercontent.com → 200
  → content-length = 158971856
```

与 release API 报的 `asset.size` **逐字节一致**（158971856），
version 21.1.0 / sha256 `1672e3ef…878825` 也与 `userland-sources.json` 一致。
即：**钉值三元组（version / sha256 / url）自洽，且资源真实存在。**

### 十一·二、两个 CI 步骤的「查找失败」写法不一致（本轮修）

对比 `build` job 的「定位 NDK」与 `ndk-llvm` job 的「编 llvmtoolchain」，
后者**没有任何存在性检查**：

```bash
# build job（有检查）
NDK="${ANDROID_NDK_LATEST_HOME:-}"
[ -d "$NDK" ] || { echo "::error title=无 NDK::…"; exit 1; }
[ -x "$TC/aarch64-linux-android21-clang" ] || { echo "::error title=无 clang::…"; exit 1; }

# ndk-llvm job（原来没有，直接拿路径用）
TC="$ANDROID_NDK_LATEST_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
```

后果：路径一旦不对，要一直走到配方里才炸，而配方报的可能是
「源码树异常」这类**与真实病因无关**的话。三样先查清：

- NDK 目录在不在
- 交叉编译器 `aarch64-linux-android23-clang` 可不可执行
- `llvm-ar` 在不在（编 LLVM 的归档工具，NDK 自带）

### 顺带修一个 `set -u` 下的报错难看问题

第一步我写成：

```bash
[ -d "$ANDROID_NDK_LATEST_HOME" ] || { echo "::error title=无 NDK::…"; exit 1; }
```

这一步跑在 `set -euo pipefail` 下。**变量没设置时，`-u` 会先杀掉它**，
报出来的是 bash 自己的 `unbound variable`，而不是我们那句有用的话。实测：

```
改前： bash: line 2: ANDROID_NDK_LATEST_HOME: unbound variable
改后： ::error title=无 NDK::runner 上没有 ANDROID_NDK_LATEST_HOME
```

所以取法必须与 `build` job 一致：`NDK="${ANDROID_NDK_LATEST_HOME:-}"`，
**先给空串再判空**。这不是风格问题 —— 前者让人看不出该做什么。

**判据：任何在 `set -u` 下引用可能不存在的环境变量，都要写成 `${VAR:-}`**
（而 `${VAR:?}` 是「没设置就立刻失败」，适合必填项，但同样不会说人话）。

---

## 十二、阶段1 收尾：能离线验的都验了，剩下的明确标注

### 已达成（有据可查）

| 目标要求 | 状态 | 依据 |
|---|---|---|
| `usr/lib/toolchain/<id>/<version>/` 落位 | ✅ | `PrefixProvisioner` 已实现，`usr/bin/<name>` 是指向它的软链 |
| `usr/include` → sysroot 软链 | ✅ | `linkSysrootInclude`，`tools/verify/sysroot-include-proof.js` 验行为 |
| clang · lld · binutils(as/ld/ar/nm/strip/objdump/readelf) | ✅ 配方+判据 | `NEED_BIN` 八个逐个查存在；判据真编 aarch64 `.so` 并验 `e_machine=0xB7` |
| make · cmake · pkg-config · python3 | ✅ 配方+判据 | 四件各有 recipe + `userland-verify.json` 判据（136~186 字） |
| sysroot（头文件+静态库） | ✅ | 阶段1a `14815ad` |
| 别名映射（8 个工具都能调） | ✅ | 上一轮修好两侧分叉与 `clang++` 白名单 |
| 分发链（形态校验/打包/别名核对） | ✅ | 上一轮补上，判据都实测过 |
| 宿主依赖（cmake/ninja/xz） | ✅ | 核对 runner 官方镜像清单，三样都有 |
| 钉值三元组自洽 | ✅ | version/sha256/url 对账，HEAD 跟重定向后 200 |

### **未达成，且我做不到**（不是没做完，是性质如此）

**「真编一次 clang」需要 CI。** 本机没有编译器（gcc/clang/cc/make/cmake/xz 全无），
也没有 NDK，所以「配方能不能真的编出东西」这一条**在本机无法判定**。

我没有推送凭据、`gh` 未安装，且推送属对外动作 —— **触发 CI 需要你做**。

### 因此目标不能标 complete

阶段1 的**九个可离线验证的维度全部达成并有据**，
但「真编」这一维**没有证据**。按本仓一贯的纪律（门禁只固化已达成共识的行为、
判据判行为事实不判代码形状），我**不会**把「配方齐备」当成「阶段1 完成」。

要收尾这一维，需要以下任一：
1. 你 push 一次，让 `ndk-llvm` job 跑起来（它带 `continue-on-error`，
   红了**不会**挡住其余 11 件），把日志给我；
2. 或者明确认可「配方齐备即阶段1 收尾，真编另立一轮」—— 那是一条**约定**，
   我会照办并在上面这段话里标注它是你定的，不是我判定的。

（顺带记一笔：交叉编译 LLVM 到 Bionic 是 Termux 级别的工程量，
首次跑**大概率需要补 patch**。这不是失败，是预期内的第一次 ——
配方里已经把取证的 error 行与末 60 行都打出来了，就是为那次补 patch 准备的。）

---

## 十三、第一次真跑 CI（2026-10-07）暴露的三件事

**此前 102 个提交从未推送，CI 一次都没跑过。** 第一次推送后：

### ① CI 从来没绿过 —— `comment-gate` 卡在第 1 步

它挂在 `ci.yml` 的**第一个** step，HEAD 上就有 1738 条注释，于是每次 push
都在第 1 步死掉，后面 27 步（含真正想看的构建）全跑不到。
剥掉后**首次全绿**（`CI 0d9b203 success`）。

### ② 一道门禁假定宿主是 aarch64，CI runner 是 x64 → 恒红

```
[FAIL] 本机是 aarch64（小端前提成立）
```

`pty-winsize-endian.js` 里是 `process.arch === 'arm64'`。它想问的是
「本机字节序是小端」（因为 C 侧 memcpy 直拷 struct winsize，主机序即字节序），
而**架构名只是小端的代理指标** —— x86_64 与 aarch64 都是小端，所以它在 CI 上判错了。

改为**实测字节序**（`Buffer.writeUInt16LE(1,0)` 后读回首字节），
并加一条探针自洽检查（小端写读为真，且大端写 24 后按小端读 ≠ 24）。

### ③ NDK 已被 runner 升到 r30，而配方钉的是 r29

```
钉值表要 29.0.14206865，runner 上是 30.0.16248370
```

`actions/runner-images` 更新了镜像。已按「跟 runner」处理：

| 项 | 旧 | 新 | 依据 |
|---|---|---|---|
| `ndkVersion` | 29.0.14206865 | **30.0.16248370** | `repository2-3.xml` 的 `<remotePackage path="ndk;30.0.16248370">` |
| `llvmVersion` | 21.0.0 | **21.0.0（不变）** | Range 取 r30 的 `AndroidVersion.txt` → `21.0.0 / based on r574158c` |
| `sources.llvm.version` | 21.1.0 | **21.1.0（不变）** | 同为 21.x 线 |

**为什么 llvmVersion 不用改**：NDK 的 LLVM 版本与 NDK 自身版本是两件事。
r29 与 r30 的 `AndroidVersion.txt` 都是 `21.0.0`（只有修订号不同：
r29 是 `r563880c`，r30 是 `r574158c`）。这正是当初把它单独记一格的原因。

### ④ 顺带修一处 CC 与配方自相矛盾

`build` 矩阵注入的 `CC=…android21-clang`，而**所有配方自己的 `API` 默认就是 23**。
NDK r30 的 `stderr` 是 Android 23 才引入的，于是 pkg-config / curl / git 三件
编译报：

```
error: 'stderr' is unavailable: introduced in Android 23
```

CC 才是真正决定编译目标的那个（配方里的 `API` 变量在 configure 时才用），
所以把 CC 提到 `android23-clang`，与配方自身对齐。

**教训**：「配方里的默认值」与「CI 注入的环境变量」可以各说各话而没人发现 ——
只有真跑一次才暴露。CI 注入的每个值都应该与配方默认值一致。

---

## 十四、git 那条链：查到了确凿事实，但根因仍未定（不猜）

### 已确凿的部分

CI 日志（`build (git)/7_产出 git 件`，run 37557196126）：

```
[git] 诊断：PATH 含 TC_DIR ? 否
[git] 诊断：裸名**不可解析** → 报错 127 就是这个原因
[git] 诊断：  …/linux-x86_64/bin/aarch64-linux-android23-clang   ← 文件确实在
[git] 编静态依赖库（build-shared-deps.sh）
  [deps] openssl … install_sw 成功（install exporters/*.pc、cmake 都装了）
  "make" depend && "make" _build_modules
  make[1]: Entering directory '…/work/openssl'
  aarch64-linux-android23-clang  -Iinclude -Iproviders/implementations/include … -fPIC -pthread
  make[1]: aarch64-linux-android23-clang: No such file or directory
  make[1]: *** [Makefile:14961: providers/legacy-dso-legacyprov.o] Error 127
```

**三处交叉核实过的事实：**

1. `aarch64-linux-android23-clang` **确实在** `$TC_DIR` 下
   （用 Range 读 NDK r30 的 zip 中央目录精确匹配到该路径）。
2. openssl 3.6.3 的 `Makefile` 里**没有** `providers/implementations`、
   `legacy-dso-legacyprov`、`_build_modules` 这三个串（实测 raw.githubusercontent）。
   **这三个都是 git 的** —— 所以执行编译规则的 Makefile 是 **git 的**。
3. git 配方用 `work/git-src`，shared-deps 用 `work/openssl`，
   两者路径不同，且 `build-shared-deps.sh` 把 openssl 那段包在子 shell 里
   （`( set -e; cd …/openssl; … )`），不会把 cwd 留给调用方。

### 仍未定的部分（**不下结论**）

事实 2 与 3 合起来与「make 在 `work/openssl` 里读 git 的 Makefile」不自洽 ——
我没能解释清楚为什么这两个目录会串。当前**唯一已排除**的解释是
「PATH 里没有编译器」（诊断已明说它在 `export PATH` 之前，如实报告）。

**所以我没有再改配方。** 理由：前几轮我已经因为「照报错猜」改错了三次
（CC 层级、NDK 环境变量、诊断变量名）。现在缺的是**能区分的解释性数据**，
不是又一个猜测。

要拿到它，需要能回答「这个 make 进程的 cwd 与它读的 Makefile 各是什么」。
可用的手段：在 git 自己的 make 之前打 `pwd` 与 `head -3 Makefile`，
确认它到底在哪个目录、读的是哪个 Makefile。

### 这一路的教训（比根因更值钱）

我为这一处失败花了**四轮**，四次都是「加诊断 → 诊断没执行/没输出 →
再猜」。真正的失败不是根因难找，是：

| 轮次 | 诊断为什么没用 |
|---|---|
| 1 | 排在 `deps` 之后，`deps` 失败会因 `set -e` 直接吃掉它 |
| 2 | 自己用了不存在的变量 `$TC`（本脚本叫 `$TC_DIR`），`-u` 让它自己炸掉 |
| 3 | 跑起来了，但它报的是**执行前**的状态（`export PATH` 在它后面） |

**「加诊断」不等于「拿到信息」。** 诊断必须满足三条：
排在所有可能失败点**之前**、引用**确实存在**的变量、测的是**失败那一刻**的状态。
三条我第一轮一条都没满足。

---

## 十五、编译产物的留存与复用（本轮补）

### 之前的状态（实测）

```
build job 末尾：
  uses: actions/upload-artifact@v4
  with:
    name: userland-${{ matrix.tool }}
    path: dist/userland-${{ matrix.tool }}-*.zip
    retention-days: 1          ← 只留1 天
```

而**全仓只有 NDK 一处缓存**（`~/.cache/actions-setup-ndk`）。
`work/`（编译中间产物）与 `dist/`（已打的件）**都没有缓存**。

实测 run 37557196126 有 5 个制品（npm/pnpm/sqlite3/jq/curl），
但 `retention-days: 1` 意味着**明天就消失，且无法跨 run 复用** ——
每轮 CI 都在把这 5 件从零重编一遍。

### 补了两件事

**① 制品留存 1 天 → 30 天**（两处 upload-artifact 都改了）。

**② 编译中间产物按件缓存**，命中则增量编（不重跑 Configure）。

key 由 `scripts/cache-key.sh` 算，**含本件真正依赖的源码钉值 + NDK 版本 + API + runner**：

```
uw-curl-curl-8.22.0openssl-3.6.3zlib-1.3.2-ndk30.0.16248370-api23-Linux-X64
uw-make-make-4.4.1-ndk30.0.16248370-api23-Linux-X64
uw-git-git-2.55.0openssl-3.6.3zlib-1.3.2curl-8.22.0|patch:…-ndk30.0.16248370-api23-Linux-X64
```

**为什么 key 必须含源码版本**：work/ 里是 Configure 过的 `.o` 与 `config.status`。
换了源码却复用旧中间产物 = **拿旧对象拼新库**，症状是链接期符号错，
更糟的是编过了但行为是旧的。实测：把 openssl 改成 3.6.4 → curl 的 key 立刻变；
改回 3.6.3 → key 复原。

**git 的 key 还含 7 个 Termux 补丁的 sha**（补丁变了就该重编）。

### 这里踩的坑，值得单列

**第一版我把目录写成 `work/${{ matrix.tool }}-work`** —— 那是**猜的**。
对着每个 recipe grep 它实际 write 的路径后发现：

```
curl        work/curl-src  work/curl-deps
jq          work/jq
sqlite3     work/sqlite
pkg-config  work/$SRC_KEY（=work/pkgconf）
make        work/$TOOL（=work/make）
```

**`work/<tool>-work` 一个都不对。** 缓存命中也是空的 ——
而且**它不会报错**，只是「缓存没起作用」，很容易被当成「缓存没命中」继续查错方向。

**判据：缓存/归档的路径必须对着「谁真的往那儿写」核，不能按命名直觉列。**
这与「门禁的路径要对着实际落位核」是同一条。

### 规范（从这一串修里总结出来的）

1. **产物留存的时长要匹配用途**。1 天只够「这一轮有没有出东西」；
   要能复查、要能跨轮比对，就得给到 30 天以上。
2. **缓存 key 必须包含它依赖的一切外部事实**：源码版本、工具链版本、
   补丁 sha、runner 平台。少一样就会出现「拿旧东西拼新东西」。
3. **缓存路径要对着实际 write 路径核**。写错不报错，只是白存。
4. **不同件用不同 key**，共用目录（work/deps、work/openssl）也要各自隔离 ——
   否则 git 与 curl 的中间产物会互相污染。
5. **key 计算脚本要对未知件名判红**（`cache-key.sh` 现在会），
   否则新加的件会静默拿到一个不含它依赖的 key。

---

## 十六、预制品仓库：编过的件不再重编（照 node 已有的做法）

### node 早就在这么做了，本轮才把同样的做法铺到其余各件

`.github/workflows/node-runtime.yml` 里 node 运行时制品的做法是：

```
「已发布就复用（不重编）」：gh release view <tag> → 命中就 gh release download
「发布制品到 Release」：  编译后 gh release upload --clobber
```

**这不是新发明，是把已验证可用的做法复制到其余 9 件上。**
之前只有 node 有预制品仓，其余每轮都从零编。

### 现在的 build job 流程

```
1. 算「编译一次」标识（key + tag）
     tag = 本件依赖的源码钉值 + NDK + API + runner（字符受限，gh release 拒绝空格/分号）
2. 已发布就复用（不重编）
     gh release view <tag> 命中 → download 回来 → 跳过第 9 步
3. （未命中）产出本件
4. 件形态校验          ← 先验形态，再存
5. 发布预制品到 Release ← 形态过了才存，不合格的不会被当成「可复用」
6. 存回编译中间产物 / 打包 / 发布到对象存储
```

**tag 由依赖算出，所以升级语义是自动的**：
升了任一依赖的源码版本（或 git 的补丁 sha）→ tag 变 → 自动重编一次；
没升 → 直接复用。**不需要人去判断「这个件要不要重编」。**

### 每份预制品都带一份 BUILD.md（这是「编译规范文档」的落点）

存进 Release 的 `BUILD.md` 记录：

```
预制品：curl
Release tag：uw-curl-curl-8.22.0openssl-3.6.3zlib-1.3.2-ndk30.0.16248370-api23-Linux-X64
NDK：30.0.16248370
NDK 内置 LLVM：21.0.0
API 级别：23
缓存 key：…
构建 run：https://github.com/lobbowen/lob-os/actions/runs/<run_id>
## 这份预制品的编译依据
（那串含全部依赖版本的 key）
```

**下次升级时不必重新推断**「这份东西当初是怎么编的、用了哪个 NDK、
哪些源码版本」—— 它随预制品一起被存下来了。

### 这里踩的坑

用脚本批量改 YAML 时，我两次把块插到了**错的 job** 里
（`String.replace` 只替第一处，而「打包 + sha256」「产出 X 件」
在node/build/ndk-llvm 三个 job 里各有一处）。

**症状**：脚本报告「已插入」，但 `grep` 找不到 / 出现在别的 job 里。

**判据**：改多 job 的 YAML，按**行号 + job 边界**定位，不要靠字符串首匹配；
每次改完立刻 `grep -c` 复核落点。这与「缓存路径要对着实际 write 路径核」
是同一条 —— **按命名/首匹配推断出来的位置，要复核**。

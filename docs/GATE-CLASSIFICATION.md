# 门禁分类

依据：门禁的唯一职责是**固化已经达成共识的行为**，防止无意破坏。
不属于"共识"的一律不该做成门禁 —— 否则代码一改就红，门禁就从保护网变成路障。

## A 类：删掉（不是共识，或编译器/其他门禁已覆盖）

| 门禁 | 为什么删 |
|---|---|
| `check-imports` | 缺 import 编译就报。这是我自己踩过 8 次编译错误后加的"补丁"，但正确做法是本机有编译器 —— 没有编译器才需要它，有了就该删 |
| `check-brace-balance` | 括号不配平编译就报。**是我为了绕开"本机没编译器"才造的**。有了 CI 编译，它零价值 |
| `check-kotlin-misuse` | 扩展函数误用、结构错误都是编译期问题。CI 编译直接报 |
| `check-cross-refs` | 符号不存在编译就报。同上 |
| `check-tool-paths` | 查的是门禁脚本自己有没有硬编码路径 —— 这是我的工作习惯，不是产品共识 |
| `check-dead-refs` | 已被 `scan-dead-code.js`（工具，非门禁）覆盖 |
| `check-gate-callers` | 查"门禁有没有接进 CI" —— 元门禁，纯粹为了门禁而门禁 |
| `check-layer-direction` | **本轮新增，直接删**。10 处存量违规会让 CI 立刻红，而分层方向还没达成共识。它该是报告里的"待办"，不是门禁 |

**A 类共同点：全部是"编译器能报但本机没编译器"时代的产物。**
现在有 CI 编译，它们是重复劳动 + 随时误报。

## B 类：保留但瘦身（是共识，但判据太细，动代码就红）

| 门禁 | 现状 | 怎么改 |
|---|---|---|
| `check-quickapp-capability` | 43 处判据，多半是"实现细节的镜像" | 只留行为事实（配对三步、端口释放、system 作用域关闭），删掉"某文件里有某字符串"这类 |
| `check-panel` | 23 处判据 | 同上，只留入口齐不齐、同进程调用这两条共识 |
| `check-desktop-icon` | 20 处判据 | 同上 |
| `check-install-single-path` | 15 处判据 | 同上，只留"两条路一个安装点"这一条共识 |
| `check-ota-sequence-scope` | 13 处判据 | 只留"序列号不共用"这一条 |

**判据标准：判的是"行为事实"还是"代码长什么样"。**
判代码形状的，代码一重构就红，是噪音。

## C 类：保留（是共识，且判据稳定）

| 门禁 | 为什么值得留 |
|---|---|
| `check-api-spec` | 方法表与实现一致 —— 协议层共识，改了要三处同步 |
| `check-protocol-version` | 桥协议三处版本号必须一致 —— 跨进程契约 |
| `check-port-range` | 端口段 41000-50999 固定 —— 跨版本兼容 |
| `check-quickapp-package` | 包结构 `<version>/{manifest,frontend,backend}` —— 跨方契约（发布方按这个打） |
| `check-dimina-spec` | dimina 官方规范（config.json 带 path、zip 名 = appId、页面全局注册）—— 外部规范，改错必灰屏 |
| `check-page-registration` | 同上，是 dimina 规范的具体一条 |
| `check-big-artifact` | 下载必须流式 —— 真机 OOM 过一次 |
| `check-node-native-deps` | `$ORIGIN` 依赖判据 —— 真机 linker 失败过，且成因隐蔽 |
| `check-dual-canonical` | 两份 canonical 逐字一致 —— 签名正确性 |
| `check-spec-tables` | 规范表与方法表自洽 —— 文档与代码不脱节 |
| `check-components` | 构建物类目与声明一致 —— 防止漏打包 |
| `check-android-consts` | Android 常量表 —— 防止用不存在的常量 |
| `check-install-single-path`（保留部分） | 两条路一个安装点 —— 本轮刚达成共识 |

## D 类：工具，不是门禁

`scan-dead-code.js` —— 找出死码供人工判断，不自动删。

## E 类：本轮新增又删掉的

`check-kt-structure.js`（**保留**）—— 只判两条结构完整性，不判风格：

1. 花括号配平（跳过注释与字符串字面量）
2. 无悬空参数列表（形如 `    ctx: Context,` 但上一行不是 fun 头）

价值来自三次真实事故，都是删代码时踩的：

| 事故 | 现象 | 只判括号够吗 |
|---|---|---|
| 删 `bindQuickApp` 63 行 | 漏删外层 `}` | 够 |
| 删 `hostDegraded` | 跨行表达式体的函数体被删，留悬空表达式 | 不够 |
| 删 `recordAttempt` | 参数列表残留 → `Expecting member declaration` | 不够 |

后两次括号是配平的，符号解析也查不出，只有编译才炸。
**只判这两条 —— 多一条就开始误报。**

`remove-dead.js`（**删除**）—— 按行号删除声明，三次出手删坏两次。
改到第四种形态（跨行参数列表）后脚本自身出现静默退出，无法再信任。

**结论：静态按行号删代码的风险高于收益。**
删死码的正确做法：`scan-dead-code.js` 找出来 → **手工编辑 diff** →
`check-kt-structure.js` + CI 编译兜底。

## 原则

1. 门禁判**行为事实**，不判代码形状
2. 编译器能报的，不做门禁
3. 门禁红了，先问"这是我们破坏共识了，还是门禁过时了"——**后者要改门禁，不是改代码**
4. 门禁数量不是目标，能删的就删
5. 门禁红了不要立刻改代码，先确认门禁本身是否还有效
6. **门禁只能挂在与它相关的链上**（本轮踩过，见下）

## 门禁挂在哪条链上：一次具体的误置

原则 6 来自一次真实误置 —— 它比前五条更具体，因为「判据对不对」和
「判据挂在哪」是两个问题，后者更容易被忽略。

我把 `verify-ndk-llvm.sh`（核对 NDK 内置 LLVM 与钉值是否同源）放进了
`build-userland.yml` 的**商店件构建矩阵**里。那一步当时必然红
（`llvmVersion` 还没填 —— 填它需要真 NDK，而本机读不到）。后果：

```
llvmVersion 空 → 判红 → build job 的 8 件全部不产出
                → manifest job 拿不到 dist → 整条链断
```

而那 8 件（sqlite3/jq/git/curl/pnpm/npm/sysroot/zoneinfo）**只跟
`ndkVersion` 走，与 `llvmVersion` 无关**。为一件事挡住八件事，
正是本文开头说的「从保护网变成路障」。

**判据：加判据前先问三句**

| 问 | 若答案是「否」 |
|---|---|
| 它与这条链上的产物有关吗？ | 挂到别的 job，或不挂 |
| 它现在会红吗？ | 会红就先别挂 —— 红的判据挡住的是无关的东西 |
| 它红了会挡住谁？ | 挡住的必须是**它自己要保护的东西**，不是顺带的所有东西 |

**不确定时怎么办**：让它红，但用 `continue-on-error` 隔离成独立 job，
并把它的**产出做成可用的东西**。本次就是这么做的 —— 那一步失败时
报错里带的是 `NDK 29.0.14206865 内置 LLVM 20.0.1`，正是填 `llvmVersion`
需要的真值。所以「红」在这一轮不是路障，是**取答案的途径**。

等 `scripts/build-userland-llvmtoolchain.sh` 真上线（那时它才与商店件
在同一轮里跑），再去掉 `continue-on-error` —— 那才是硬判据的位置。

---

## 第七条：门禁自己也要被反例验（写进仓里，不是记在脑子里）

前面六条都在讲「门禁该判什么」，这一条讲**门禁有没有判的能力**。

### 实测结论：七道「跨语言契约」门禁里，四道是装饰

`tools/verify/` 下七个脚本接进 CI 时，我按「它们声称能抓的缺陷」逐个注入，
看它们到底会不会红：

| 脚本 | 读仓内源码 | 注入后 rc | 结论 |
|---|---|---|---|
| `pty-argv-proof.js` | C + Kotlin | **1** | 真门禁 |
| `pty-winsize-endian.js` | Kotlin + C | **1** | 真门禁 |
| `terminal-covers-real-pty.js` | Kotlin | **1** | 真门禁 |
| `sysroot-include-proof.js` | ✗ JS 复刻 | 0 | **装饰** |
| `terminal-screen-algorithm.js` | ✗ JS 复刻 | 0 | **装饰** |
| `alias-symlink-proof.js` | ✗ | 0 | **装饰** |
| `entry-link-proof.js` | ✗ | 0 | **装饰** |

关键的两组实测：

```
删掉 PrefixProvisioner.kt 里 linkSysrootInclude(ctx)?.let { ready += it }
  → sysroot-include-proof.js  rc=0     ← 调用点没了，它一声不吭

TerminalScreen.kt 里 CJK 宽度 0x4E00..0x9FFF -> 2  改成 -> 1
  → terminal-screen-algorithm.js  rc=0   ← 宽度错了，它一声不吭
```

原因是结构性的：后四道用 JS **复刻**一份逻辑，然后测那份复刻。复刻和它自己
当然一致 —— 改 Kotlin 它看不见，改复刻它当然会红（但那证明不了任何事）。
这类脚本证明的是「我的复刻和我的复刻一致」。

这不是说它们该删。B 类门禁有价值：它们固定的是**形态与环境事实**
（别名软链为什么必要、linkEntry 失败与安装成功的矛盾、include 链的语义），
这些用真目录真软链真 PATH 查找来验是成立的。问题是**不能把它们和真门禁
混在一列 CI 里当同一件事**。

### 做法：分 A/B 两类，并把这个实验固化成脚本

- **A 类（源码耦合）**：必须因源码缺陷而红。当前三道。
- **B 类（形态/环境）**：本来就不该读源码，改源码不该影响它。但必须
  如实声明自己是 B 类，不许冒充 A 类。

`tools/verify/self-check-ci-steps.js` 把这个实验固化成一道门禁：

1. 对每个 A 类门禁，注入它声称能抓的缺陷 → 必须红 → 恢复 → 必须重新变绿；
2. 对每个 B 类门禁，验证它**确实不读**仓内源码（防止有人悄悄升格成 A 类）；
3. A 类清单与注入点一一对应（防止加了新门禁却忘了给注入点，静默没人拦）；
4. 每个注入点在当前源码里真实存在 —— 否则「没红」毫无意义（我第一版就栽在这：
   注入点字符串对不上，实验根本没发生，却输出了「通过」）；
5. 跑完源码必须干净（`git status --porcelain`）。

它自己也被反例验过：把 `alias-symlink-proof.js` 的 `readsSrc` 从 `false`
改成 `true`（即谎称它是真门禁），立刻报红 rc=1。

### 我在这个实验里犯的四个错（都是「工具骗了自己」的老毛病）

1. **注入点在 `finally` 里恢复，第二次跑仍是带缺陷的状态**
   → 「恢复后重新变绿」变成同一断言跑两遍。必须**先恢复再跑第二次**。
2. **注入点字符串与源码对不上**（我按记忆写，实际是另一个文件）
   → 实验没发生，却因「没红」被判成通过。**注入点找不到必须判红**。
3. **给 `terminal-covers-real-pty.js` 挑错了注入点**，连错两次：
   先拿 CJK 宽度（它根本不看宽度），再拿删 `'J'` 分支（它有 `else -> Unit`
   兜底，吞掉本来就在承诺里，删分支确实**不该**红）。
   第三个才对：它真正守着的是兜底分支本身，`else -> Unit` 改成吐字才红。
   —— **挑注入点前先读它的判据在判什么，不要按脚本名猜。**
4. **清单核对那一节只数注入点个数，不可能失败**，属于装饰中的装饰，
   已改成「至少有一个注入点 + 与声明一致」，并实测它会红。

第 3 条是这一节最值钱的一句：**「删掉某个分支该红却没红」有时是正确的**，
因为兜底吞掉本就在承诺里。判据只有先读懂才知道红色是不是应该发生。

---

## 第八条：门禁要在**它会跑的那个 shell** 上验过

本轮踩的坑，值一条：

`verify-ndk-llvm.sh` 里读版本号的这一行：

```bash
grep -o '[0-9][0-9]*\.[0-9][0-9]*\(\.[0-9][0-9]*\)\?' file
```

**本机返回空**，脚本于是静默退化到「读不到版本」的路径 ——
而我以为它读到了，是因为对着自己写的假文件看输出「像是对的」。

真实原因：本机 `grep` 是 **toybox 0.8.13**，不支持 BRE 的捕获组 `\( \)`。
CI 上是 GNU grep，同一行能工作。**所以「CI 能过、本机验不出」**
这类差异，靠读代码看不出来 —— 只有真跑一次本机的 grep 才知道。

改成两边都支持的写法（`-E` + alternation，不用组）：

```bash
grep -o -E '[0-9]+\.[0-9]+\.[0-9]+|[0-9]+\.[0-9]+' file
```

**判据：任何用 grep/sed 提取内容的门禁，必须在目标运行环境之一上实跑过，
且输入要用真实格式而不是自己编的格式。** 本轮还栽了第二层：
我第一版只 `grep llvmorg-`，而 AOSP 真实的 `AndroidVersion.txt`
（`update-prebuilts.py` 第 119-126 行证实）**里面根本没有 llvmorg 字样**，
是 `7.0.1` + `based on r326829` 两行 —— 假文件用的是我自己想的格式，
所以「验过了」其实什么都没验。

两层的共同点：**我验的是我的想象，不是真实输入的形状。**

---

## 第九条：JS 复刻盯不住 Kotlin —— 升格要连注入点一起做

第八条说「门禁要在它会跑的那个 shell 上验过」。这一条是它的续集：
**复刻盯不住本体**。

本轮实测：`tools/verify/alias-symlink-proof.js` 里用 JS 复刻了
`ProgramIndex.safeSegment` 的白名单。加断言「`clang++` 要在两侧别名里」
之后看起来很完整，但**把 Kotlin 里的 `|| c == '+'` 删掉，这道门禁 rc 仍是 0** ——
因为它测的是复刻，Kotlin 改了它不知道。

修法不是「再写一份断言」，而是**让门禁真的读 Kotlin 源码**：

```js
const KT_SEG = path.resolve(__dirname, '../../container/app/src/main/java/lobos/os/ProgramIndex.kt');
// 取 fun safeSegment 的函数体 → 抽出 c == 'x' 的字面量集合 → 与 JS 复刻对账
```

升格后立刻见效：注入 `|| c == '+'` → **rc=1，三条判红**。

### 顺带一个更值钱的发现：两侧规则分叉

升格过程中对照了**装侧**（`ProgramInstallPipeline.aliasNames`）与
**发侧**（`publish-userland-manifest.js` 的 `aliasesOf`）。两边注释都写着
「必须与对方一致」，但实际规则不同：

| | 跳过的「本名」 |
|---|---|
| 装侧 | `programId`（件名）**和** `primaryName`（entry 末段） |
| 发侧（原） | 只有 `name`（件名） |

llvmtoolchain 正好命中（件名 `llvmtoolchain`、entry `bin/clang`）：
发侧清单会多出 `clang` 与 `clang++`，装侧一条都不建 ——
**清单比实际链多，卸载时按清单删不存在的链，真正建过的变成死链。**

而装侧那条链之所以不建 `clang++`，是因为 `safeSegment` 不放行 `+`。
于是叠起来是：**装上了 clang，但 `clang++` 调不到 = 编不了 C++**，
而 `llvmtoolchain` 的判据只跑 `clang --version` 与编一个 `.c`，
**看不出 C++ 编不了** —— 这种缺失很安静。

两处都修了（发侧跳两种本名；白名单放行 `+`），并各配一个反例：

```
反例：发侧只跳件名时确实与装侧分叉（证明上一条断言不是装饰）
反例：白名单去掉 + 后 clang++ 建不出链（证明放行 + 是必要的）
反例：拿掉 + 后字符集确实不同（证明 Kotlin 那两条不是装饰）
```

**判据：** 一道门禁如果只测复刻，那它对本体退化是瞎的。
要它盯住本体，就得让它读本体；读完还要更新它自己的分类
（本轮 `alias-symlink-proof.js` 由 B 类升为 A 类，`self-check-ci-steps.js`
立刻报红提醒补注入点 —— 那个自检就是这么用的）。

---

## 第十条：合法但语义相反的写法，只有按解析语义判才看得见

本仓踩过的真缺陷（`build-userland.yml`）：

```bash
command -v cmake >/dev/null 2>&1 || sudo apt-get update -qq && sudo apt-get install -y -qq cmake
```

读着像「有 cmake 就跳过」。实际 bash 里 `&&` 与 `||` **同优先级、左结合**，
它是 `(A || B) && C` —— **install 无条件执行**，每次 CI 都 apt-get update。

**编译器不报，`sh -n` 不报，node --check 更不报**（它压根不是 JS）。
`bash -c` 也正常返回 0。只有按左结合语义去判才看得见。

### 为什么它归到门禁那一类

它满足前面第��条里的「判据判行为事实」：判的是**这行实际会做什么**，
不是它的样子。反过来说，「检查一下所有 shell 脚本」这种形状检查是没用�� ——
危险的不是「写了 `||` 和 `&&`」，而是「`||` 出现在 `&&` **之前**」。

### 判据必须窄，否则第一版就误报了 200 多处

我第一版判「只要同时出现 `||` 与 `&&` 就危险」，结果：

- 把 `src/` 下 vendored 的 mingw-w64 源码也扫进来（200+ 处，我们不改那些）
- 把仓里两处**完全正常**的 `[ -e "$f" ] || continue` 惯用法判成危险

正确形态是三条一起：

1. **范围明确**：只扫 `scripts/` 与 `.github/workflows/`（我们自己写的）
2. **首个运算符是 `||`**：`A && B || C` 是安全惯用法（左结合后 `||` 收尾），
   只有 `A || B && C` 才危险 —— 两者**只差符号顺序**，语义相反
3. **右段是命令而非测试**：`[ -n "$X" ] && [ -d "$Y" ] || die` 安全，
   `A || cmd && cmd2` 危险

配的反例（否则下面可能恒真）：

```
反例行（真实踩过的那行）被判危险
安全惯用法 A && B || C 不误报
A && B || C 且 || 右段是命令（仓里 verify-apk-native.sh:173 就是）不误报
同名惯用法 A || B && C 会被判危险（方向相反，语义不同）
取到的正是第一个运算符的右段
```

实测：注入那行 → rc=1 并指名文件与行号；恢复 → rc=0。

### 写这道门禁时自己犯的三个错（都记在这里）

1. **`ROOT` 用了 `'../..'`** —— 它在 `tools/` 下（不是 `tools/verify/`），
   两层爬到仓外的 `work/`，结果是 **ENOENT 崩掉、脚本一声不吭 rc=1**。
   看不出是路径写错。（照抄邻居脚本的相对层数，没看自己在哪一层。）
2. **改了函数名没改最后一行** —— `orRightSegment` 改成 `operatorSegments`
   后尾部断言还在调旧名，于是抛异常、**连汇总行都没打出来**，
   表现为「rc=1 但没有 FAIL」。看着像门禁内容有问题，其实是引用坏了。
3. **第一版按「像散文」跳过**（含破折号就跳）—— 那会漏掉真缺陷：
   一条有害命令完全可以写在同一行而旁边有破折号。已收窄成只跳注释与
   YAML 的 step 名/键行。

**共同点：三个错的表现都是「rc=1 但看不出为什么」。**
所以判据类脚本必须满足：**失败时把原因打在 stdout 上**。

---

## 第十一条：优化类步骤失败**不能**挡住在做的事

实测踩出来的（`ndk-cache` 这个 job）：

```
ndk-cache → failure（装 NDK 时）
build          → skipped   ← 十三件商店件一个没编
ndk-llvm       → skipped   ← 编 clang 的那条链
manifest       → skipped
```

`ndk-cache` 的作用只是**省一次 700 MiB 下载**。它一红，整条链就断了 ——
**为省下载，挡住了真正要编的全部**。这正是本文开头说的「从保护网变成路障」。

判据很直接：

| 这个步骤的作用 | 它失败时应该 |
|---|---|
| 产出要用的东西 | 挡住下游（否则下游拿到的是坏的） |
| **只是省时间/省带宽** | **不挡**（挡住没有收益，只有损失） |

所以两条改动：

1. `build` / `ndk-llvm` / `manifest` 的 `needs` 里**去掉** `ndk-cache`；
2. `ndk-cache` 加 `continue-on-error: true`。

判据要挂在**它保护的东西**上。缓存 job 不保护任何产物 —— 它保护的是
「下次不用再下」，那是一次性的收益，不该换取「这轮什么都编不出来」。

### 顺带一条：`if ! yes | cmd` 在 `pipefail` 下会误判

实测：`ndk-cache` 的日志停在 `100% Unzipping…android-ndk-r30/sour` 然后
`exit 1`，**一句原因都没有** —— 而 NDK 其实已经装好了。

原因是那行 `if ! yes 2>/dev/null | sdkmanager …`：

```
set -o pipefail; if ! true | false; then …   # 判失败
set -o pipefail; if ! false | true; then …   # 也判失败
```

`sdkmanager` 装完就关 stdin，`yes` 收到 `SIGPIPE` 退非 0，
`pipefail` 把管道整体判成失败。**判据测的是「管道整体」，不是「sdkmanager」**。

两个同类教训合起来是同一句：**判据必须测你要测的那个东西**。
`if ! A | B` 测的是管道；`if ! B` 测的才是 B。

改法：不用管道，改用 sdkmanager 自己的 `--licenses` 预接受，
并在装成功后明确打一行「装好了：<路径>」，让成功/失败一眼可分。

---

## 第十二条：前提变了就换判据，不是删判据

### 起因

底座件走 OTA 更新，而**静态化会把依赖烧进产物**：升 libz/openssl 时静态件
不跟着更新，换 `.so` 就生效。标准发行版（Debian/Fedora）的 make/cmake/python3
也无一例外是动态。仓库里另有一处直接矛盾 ——
`verify-userland-artifact.sh` 本来就要求产物必须动态
（LD_PRELOAD 容器对静态件失效），配方里再判静态是自相矛盾。

### 换掉的三条断言

`make` / `cmake` / `pkg-config` 各有两条，理由都是「静态编才如何」：

```sh
[ "$SIZE" -gt N ] || die "产物可疑" "只有 $SIZE 字节 —— 静态编不该这么小"
[ -z "$DYN" ]   || die "不是静态产物" "有 PT_DYNAMIC —— 不该依赖任何共享库"
```

这两条**真正想拦的是「件带着找不到的依赖出门」**。静态化时它用
「有 PT_DYNAMIC 就红」来近似，动态化之后这个近似失效了：
动态件一定有 PT_DYNAMIC。判法必须跟着链接方式换。

### 换成什么：依赖闭包可解析

`scripts/check-elf-deps.sh`。每个 `DT_NEEDED` 必须满足其一：

1. bionic 自带（`scripts/native-deps.txt` 白名单）
2. APK 基础库（`libc++_shared` / `libz` / `libssl` / `libcrypto` / `libcurl` / `liblobosflock`）
3. 件自己的 `lib/` 下
4. 用到 3) 的必须有含 `$ORIGIN` 的 `DT_RUNPATH`
   （bionic 忽略 `DT_RPATH`；载荷 `run_code` 起子进程时环境是空的）

第 4 条与第 1~3 条不同级：前三是「有没有」，第 4 是「找不找得到」。

### 八个反例，四个是先写出来才发现判据漏了

判据自己也要被反例验证（第十条）。八个分支全部实测：

| 分支 | 期望 | 实得 |
|---|---|---|
| 只依赖系统白名单内的库 | 过 | ✔ |
| 依赖不在白名单的库（libselinux.so） | 红 | ✔ |
| 依赖 APK 基础库（libcurl/libz/libc++_shared） | 过 | ✔ |
| 依赖同目录库 + 含 `$ORIGIN` 的 RUNPATH | 过 | ✔ |
| 依赖同目录库但无 RUNPATH | 红 | ✔ |
| 依赖同目录库但只有 `DT_RPATH` | 红 | ✔ |
| 依赖同目录库但 `lib/` 下没那个文件 | 红 | ✔ |
| 静态产物（无 `.dynamic`）不误报 | 过 | ✔ |

其中**「只有 `DT_RPATH`」与「库里没那个文件」两条，是先写反例才发现判据漏的**
—— 原判据只看 `DT_RUNPATH`，不查 `DT_RPATH`，也不验文件真的存在。

### 判据挂在哪一层

挂 `verify-userland-artifact.sh`，三个调用点（node / build 矩阵 / llvmtoolchain）
统一在一层，而不是散在各配方里 —— 与第十条「门禁要挂在与它相关的链上」一致。

`node` job 不跑 `locate-ndk.sh`、完全不碰 NDK，拿不到 `LLVM_READELF`。
处理方式不是给它加 NDK，也不是让它静默跳过，而是让 `check-elf-deps.sh`
在没注入时自己去 `PATH` 与 `ANDROID_NDK*` 下找；找不到才报错并说明「判据无依据」。

### 附：busybox 为什么**不**跟着改

`build-native-busybox.sh` 里有同类断言，但这一件核实后不改：

- 静态化形式是 `CONFIG_STATIC=y`（busybox 固有语义），不是链接 flag
- 消费端是 `NativeAssetRegistry` 的 CAPABILITY，按 `libName` 从 jniLibs 找库，
  不是从 `$PREFIX/bin` 找可执行件
- `NativeAssetRegistry.kt:88` 明写「静态编、不链底座 libz：
  底座件之间不互相依赖」—— 若改动态就依赖 `libz`（upstream 档硬依赖），
  违反 `ENV-EXECUTION-PLAN.md` §2.3.1 三筐判据第二条

**判据相同不等于结论相同。** 前三件是「断言写错了」，busybox 是「断言对」。

### 附：`comment-gate` 撞过一次，是我自己引进的

换判据时为了说明「为什么」，在新脚本与搬运处写了 25 + 8 条解释性注释，
正好撞上「不许上注释」门禁，把构建卡住了。注释门禁只允许 shebang 与
工具指令类（见 `scripts/strip-comments.js` 的 `ALLOW`）。

处理：注释删掉，「为什么」落到本文档。**代码里的解释性注释会腐坏**，
而门禁要判的正是这件事 —— 这次它判对了，是我没按规矩写。

---

## 第十三条：报「找不到编译器」时，先看它到底报的是什么

### 起因

`cmake` 件红了，报 `Cannot find appropriate C compiler on this system`。
而 CI 明明注入了 `CC`。第一反应是「CC 没透传」——**错的**。

取证路径当时写的是 `$WORK/bootstrap/bootstrap.log`，
bootstrap 实际写到 `build/Bootstrap.cmk/cmake_bootstrap.log`，两个路径都不对，
所以真因根本没进日志。改成遍历三个路径全打，才看到真相：

```
Checking whether '.../aarch64-linux-android35-clang ... -std=gnu89 ' works.
...cmake_bootstrap_2292_test.c -o cmake_bootstrap_22        ← 编成功（只有一条宏重复 warning）
bootstrap: 930: ./cmake_bootstrap_2292_test: Exec format error
Test produced non-zero return code
```

编译器没问题，**是 bootstrap 把编出来的 aarch64 程序在 x86_64 宿主上执行了**。

### 根因：CMake 的 bootstrap 根本没有交叉模式

`bootstrap` 是 2111 行纯 shell。它的 `cmake_try_run()`（第 911 行起）流程是：

```sh
${COMPILER} ${FLAGS} "${TESTFILE}" -o "${TMPFILE}"   # 930 行之前：编
./${TMPFILE}                                          # 930 行：必定执行
```

交叉编译场景下这一步必然 `Exec format error`。grep 整个 `bootstrap` 也没有
`CMAKE_SYSTEM_NAME` / `CMAKE_CROSSCOMPILING` / `CMAKE_TOOLCHAIN_FILE` 的处理。

**不是配错参数，是工具用错了。** 正解是 CMake 官方支持的交叉编译：
用宿主 cmake + `-DCMAKE_TOOLCHAIN_FILE`。

### 判据纪律

1. **「找不到 X」要先确认真的是找不到 X。** 这条的报错措辞把「跑不起来」
   说成了「找不到编译器」，指向完全不同的方向。
2. **取证路径要覆盖真实写入位置。** 猜路径猜错时，日志里就什么都没有 ——
   而「什么都没有」最容易被误读成「问题很简单」。
3. **工具的能力边界要查源码，不要凭印象。** 我一直以为 CMake 的 bootstrap
   至少能配 `CMAKE_SYSTEM_NAME`（配方注释里就是这么写的），实际没有。

### 同一轮里另两件

**sysroot**：`aarch64-v8a` 是 jniLibs（APK）的 ABI 名，NDK sysroot 的库目录
按 target triple 命名。修法不是维护一张 ABI 白名单，而是**从 CC 的文件名推导**
（`aarch64-linux-android35-clang` → `aarch64-linux-android`）——CC 才是权威来源。
四个分支都造了反例：CI 的 `aarch64-v8a`、`build-native-*` 的 `arm64-v8a`、
从 CC 推导、以及非 aarch64 判红。

**python3**：`PKG_CONFIG_LIBDIR` 隔离**生效了**（日志实证
`checking for libzstd >= 1.4.5... no`、`sqlite3... no`），zstd 问题解决。
但暴露出下一层：产物叫 `python` 而不是 `python3.*` ——
交叉编译时 CPython 的 `LDVERSION` 为空（`Makefile.pre.in:165`
`EXENAME=$(BINDIR)/python$(LDVERSION)$(EXE)`），这是既定形态不是编坏了。
探测放宽成 `python3.*` 与 `python` 两个候选，并加 ELF 魔数校验
（不用 `[ -x ]`：本机某些宿主对它返回假），失败时把 Makefile 的
`EXENAME`/`BUILDPYTHON` 打出来。

顺带记一条：**这已经是本轮第二次被 `comment-gate` 拦下**，第一次是
`check-elf-deps.sh` 的 25 条。解释性注释一律不进代码，判据依据落本文档。

---

## 第十四条：同一个东西只能有一套命名，且发布与消费必须共用

### 起因

`node` job 红了，报「两个仓都取不到 `node-runtime-24.21.0-arm64-v8a`」。
查 release，实际资产是存在的 —— 在**三个不同的 tag** 下：

| tag | 资产名 |
|---|---|
| `rt-node` | `node-24.21.0+arm64-v8a+ndk…+api35.tar.gz` |
| `node-runtime-24.21.0` | `node-runtime-24.21.0-arm64-v8a.tar.gz` |
| `node-runtime-24.21.0-arm64-v8a` | `node-runtime-24.21.0-arm64-v8a.tar.gz` |
| `rt-node`（另） | 同上 |

消费侧找的是第三种，发布侧写死第一种，其余件用 `rt-node` + `cache-key.sh`。
**三套命名并存，且互不知道对方存在。**

### 定位手段：查 release 实际资产，不看代码里的公式

代码里三处公式各说各话，靠读代码分不清谁对谁是权威。直接列 API：

```js
curl -s -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/lobbowen/lob-os/releases/tags/rt-node"
// → assets: node-24.21.0+arm64-v8a+ndk30.0.16248370+api35.tar.gz
```

`rt-node` 是与其余件一致的命名（`base-jq` / `rt-node` / `tool-npm`），
所以它是当前体系，另两个是历史遗留。

### 修法

1. `cache-key.sh` 补 node 的 `VER`（原本落到通用分支 → `nodeps`，丢了版本与 ABI）
2. `node-runtime.yml` 的资产名不再手写公式，改调 `cache-key.sh asset node`
3. 消费侧 job 主用 `cache-key.sh tag node`，旧两个 tag 列为回退

### 这里我连犯两次错，都被回归抓到

**错一：给 `SAFE` 的 `tr -c` 字符集加 `-`。** 想法是保住 ABI 里的连字符。
结果 `+` 和 `-` 在 `tr -c` 里互斥 —— 保了 `-` 就丢了 `.`→`+` 的转换，
**七件资产名同时变错**。逐件与 release 实际名字对照才发现。

**错二：随后又想「让 ABI 在进 `DEPS_STR` 前就替换掉连字符」。**
方向对了（不动 `SAFE`，在源头处理），但第一版把 `$(bash … default)` 的
右括号漏了，`bash -n` 报 `unexpected EOF while looking for matching ')'`。
二分法定位到行 —— `head -n 82 | bash -n` 报括号未闭合。

**教训**：
- 改共享工具（`cache-key.sh`、`tr` 字符集）前，**先把所有调用者的输出存档**，
  改完逐件对照。本次是九件全对才算过。
- 「读代码看着对」和「多行 `$( )` 嵌套是对的」都是错觉，要用 `bash -n` 和实际输出验。

### 判据纪律

1. **发布侧与消费侧必须共用同一入口算名字**，不能各写一份公式。
2. **查权威源（release 实际资产），不要读代码里的公式** —— 公式可能三份都错。
3. **改共享工具要逐件回归**，并把期望值从权威源取，不是从代码推。

---

## 第十五条：一次只推进一层，别把「修好一个」当成「整件好了」

### 起因

三件连续三轮，每轮都是**同一件的新一层**：

| 件 | 轮次 | 报的是什么 |
|---|---|---|
| cmake | 1 | `Cannot find appropriate C compiler`（取证路径错，真因没进日志） |
| cmake | 2 | `Exec format error`（bootstrap 执行交叉产物） |
| cmake | 3 | `The C++ compiler does not support C++11 (std::unique_ptr)`（工具链跑起来了） |
| python3 | 1 | `zstd.h file not found` |
| python3 | 2 | 编译成功、产物找到，`install` 装到 `/usr/local` 撞 Permission denied |
| python3 | 3 | （待下一轮） |
| sysroot | 1 | `usr/lib/aarch64-v8a 不存在` |
| sysroot | 2 | `目标库层=…/aarch64-linux-android/35` 正确，`libclang_rt*.a` 缺 |

**每一层的报错都不一样，而且前一层修好才会露出后一层。**
如果第一次看到 cmake 报「找不到编译器」就去猜「CC 没透传」，就会永远停在错误的方向上。

### 关键转折：日志措辞骗人

cmake 第 2 轮的日志是：

```
-- Check for working C compiler: .../bin/clang - skipped     ← 编译器变成了裸 clang
...
CMake Error at CMakeLists.txt:204 (message):
  The C++ compiler does not support C++11 (e.g.  std::unique_ptr).
```

第一反应是「CMake 忽略了我设的 `CMAKE_C_COMPILER`」。
去查 `Platform/Android-Determine-CXX.cmake`，第一行是
`if(CMAKE_CXX_COMPILER) return()` —— 我明明设了，它应该生效。
再查 `Platform/Android-Determine.cmake`，发现
`include(${CMAKE_ANDROID_NDK}/build/cmake/hooks/pre/Android-Determine.cmake OPTIONAL)`。
去下那个 hook，解码后发现**它是空的**（只有版权头和一行 `echo`）。

**结论：不是 hook 干的。** 于是不再猜，改成让 CMake 自己报：
configure 失败时打印 toolchain 文件全文 + `CMakeCache.txt` 里
`CMAKE_(C|CXX)_COMPILER` 与 `CMAKE_CXX_FLAGS` 的实际值。

**判据要取事实，不要读措辞。** `Check for working C compiler ... - skipped`
与「编译器不支持 C++11」在日志里并列，但两者不是同一件事。

### 同一个错，今天犯了三次

**未定义变量被 `set -u` 杀掉**，而且三次都是在校验阶段才发现：
1. 诊断块里用 `$TC` 而实际变量叫 `$TC_DIR`
2. `python3` 加 `--prefix="$PREFIX"` 时，`PREFIX` 定义在 70 行之后
3. node 报错信息里引用了两个刚被我删掉的变量（`$LEGACY_TAG` / `$LEGACY_TAG2`）

第 3 次尤其该警惕：**报错信息本身触发 `set -u`**，于是「取不到制品」的真实原因
被换成「unbound variable」，日志里再也看不到原来的问题。

现在收工前固定跑一遍扫描：抽出每个 `run:` 块里的 `$VAR`，
与「本块内赋值 + workflow `env:` + 已知 runner 变量」比对，列出疑似未定义项。

### 造反例的价值又一次体现

第 3 次是靠扫变量发现的，但第 2 次（`PREFIX` 顺序）是我**主动造反例**才确认的：

```sh
set -euo pipefail
echo "  即将用 PREFIX=$PREFIX"   # → PREFIX: unbound variable
PREFIX="/w/_inst"
```

只读代码时，`PREFIX=` 出现在 165 行、`--prefix=` 在 95 行，
看起来像「反正脚本里定义了」。`set -u` 不管顺序，只管用的时候有没有。

### node 的资产名：两个候选并列，不强行统一

`cache-key.sh` 的 `SAFE` 用 `tr -c 'A-Za-z0-9._' '+'`，会把 `-` 变成 `+`，
所以它算出 `node-24.21.0+arm64+v8a+…`；而 release 上的**存量资产**是
`node-24.21.0+arm64-v8a+…`。

改 `SAFE` 让 `-` 保留会破坏另外七件（前面已实测）。改发布侧产出 `+` 版则
存量资产取不到、必须重编。**两个都不选**：候选名并列，取到即用 ——
候选 1 是新规范名，候选 2 是存量名。

---

## 第十六条：判据自己报错时，先确认判据错了还是对象错了

### 起因

sysroot 的探针编出了 `.o`，日志是：

```
[ok] clang --sysroot 指到本件后，能编出 .o（内建函数已解决）
::error  Machine:                           AArch64
 clang 编成了宿主架构 —— 交叉配置没生效（toolchain 文件不对？）
```

`Machine: AArch64` 和「编成了宿主架构」**在同一份日志里同时出现**。
判据说的是「不是 AArch64」，而它自己打印出来的就是 `AArch64`。

那行 `Machine:` 来自失败分支里的
`"$LLVM_READELF" -h ... | grep -i machine` —— 也就是说
**同一条命令加 `-i` 抓到了，不加 `-i` 的 `grep -q 'AArch64'` 却返回假**。

### 处置：不追成因，换判据

成因我没查清（NDK 的 `llvm-readelf` 是不是 wrapper、输出走哪个流、
LLVM 21 的机器名确切拼写），文档也没写。**没查清就是没查清，不编理由。**

改成直接读 ELF 头的字节：`e_machine` 在偏移 18、2 字节小端，`0xB7` = AArch64。
不依赖任何工具的输出措辞。反例两个方向都验过（`b700` → AArch64、
`3e00` → x86_64）。

**教训**：判据抓错了东西时，先怀疑判据。上游文档没写清楚的东西，
我不该用「大概是 wrapper」去解释一个已经能绕开的问题。

### 同一轮的 cmake：根因未查明，如实说

NDK toolchain 那处改对了 —— configure 通过、编到 21%，此前的
`std::unique_ptr` 消失（漏 `CMAKE_SYSROOT` 就是那个原因）。
现在卡在 libuv：

```
core.c:1683: error: use of undeclared identifier 'CPU_SETSIZE'
core.c:2065: error: use of undeclared identifier 'cpu_set_t'
core.c:2073: error: call to undeclared function 'sched_getaffinity'
```

查过的（都不是原因，记下来省下重复劳动）：
- Bionic `libc/include/sched.h`：`CPU_SETSIZE` / `cpu_set_t` **无条件定义**，
  `sched_getaffinity` 没有 `__INTRODUCED_IN` → **不是 API 门控**
- Bionic `sys/cdefs.h`：只测 `__USE_GNU` / `__USE_BSD`（`_GNU_SOURCE` / `_BSD_SOURCE`），
  **没有 `__USE_MISC`**（那是 glibc 的）→ 删掉 `-D__ANDROID_API__` 无害
- Bionic 门控宏是 `__ANDROID_MIN_SDK_VERSION__`（`android/versioning.h:58`），
  由 clang `--target=aarch64-linux-android35` 自动内建 → 宏不缺
- libuv `v1.x` 的 `CMakeLists.txt` 里**没有** affinity/sched 检测
  → `UV__CPU_AFFINITY_SUPPORTED` 由 CMake 主工程定义

**没查到的**：`UV__CPU_AFFINITY_SUPPORTED` 在 CMake 4.4.4 主工程里怎么定的、
它当时取了什么值。所以不猜，改为让构建自己报：编译失败时打印
`configure.log` 与 `CMakeCache.txt` 里 `CPU_AFFINITY|HAVE_SCHED|UV__|_GNU_SOURCE`
的实际值，以及真正编译 `core.c` 的那条命令行。

**判据要能在下一次失败时把答案送到眼前**，这比现在猜一个说法有用。

---

## 第十七条：上游宏与目标 libc 不一致时，打补丁改判断条件而不是取消宏

### 诊断定案（靠上一轮加的诊断，不靠猜）

`$CC -dM -E` 的实测输出：

```
#define __ANDROID__ 1
#define __BIONIC__ 1
#define __linux__ 1              ← 关键
#define __ANDROID_MIN_SDK_VERSION__ 35
总宏数：699
```

libuv `src/unix/internal.h:566`：

```c
#if defined(__linux__) || (defined(__FreeBSD__) && __FreeBSD_version >= 1301000)
#define UV__CPU_AFFINITY_SUPPORTED 1
```

Bionic 的 `<sched.h>` 里 `CPU_SETSIZE` / `cpu_set_t` / `sched_getaffinity`
无条件定义、无 `__INTRODUCED_IN`（前一轮已查），所以不是 API 门控问题，
而是**编译器说「这是 Linux」、libc 说「我不是 Linux」**。

三个事实凑齐才定案：
1. libuv 的判断只看编译器宏，不看 CMake 检测（`uv_defines` 只有 `_GNU_SOURCE`）
2. Bionic 不提供那三个符号，且无 API 门控
3. clang 的 Android target 预定义 `__linux__`

### 两条候选改法，选了打补丁

| 改法 | 代价 |
|---|---|
| `-U__linux__` 全局取消 | 一行，但 libcurl / zstd / c-ares 等都可能依赖它 |
| 改 `internal.h` 的判断条件 | 只影响 libuv 一处 |

选后者，**与 Termux 的 `packages/libuv/src-unix-fs.c.patch` 完全同构**
（那里把 `#ifdef __linux__` 改成 `#if defined(__linux__) && !defined(__ANDROID__)`，
理由也写了：Android 上 seccomp 禁掉了 statx）。

补丁放 `patches/` 入库而不是从 Termux 拉 —— Termux 没有这个补丁，
自己维护的东西自己存，版本升级时由配方里的「打不上就判红」兜住。

### 补丁本身要验过，不能只看 `patch` 打印了什么

第一次测补丁时，`patch -p1` 打印 `patching file` 且退出码 0，**但文件没变**。
原因是我复用了已经打过补丁的目录。改用干净目录重测才确认补丁是好的。

四个用例都验过：

| 用例 | 期望 | 实得 |
|---|---|---|
| 干净目录打补丁 | 生效（`__ANDROID__` 出现） | ✔ |
| `patch -d $SRC`（配方里的写法） | 生效 | ✔ |
| 重复打一次 | 非 0 + 明确原因 | ✔ |
| 文件内容不匹配 | 非 0 | ✔ |
| 文件不存在 | 非 0 | ✔ |

**「patch 报成功」不等于补丁生效** —— 这条要单独记，
因为它和 `set -e` 吞诊断是同一类：工具说了不算，要看结果。

### 一处自我更正

`patch -d` 的第一次测试里我用了 `/tmp` 存备份，本机 `/tmp` 不稳定导致
`cp` 失败，进而 `ls` 报「文件不存在」，看起来像补丁把文件删了。
**实际文件一直在** —— 是环境问题被误读成代码问题。
本会话已记录过这条，改用 `~/.cache` 后不再发生。

---

## 第十八条：改一处不够 —— 把「所有会碰这个东西的地方」一次扫完

### 又一次「只修了报第一处错的代码」

CI 实证：上一版补丁修好了 `core.c`（`core.c` 整份文件不再报错），
错误换到 `Utilities/cmlibuv/src/unix/process.c:319/434/437/441`。

也就是说：**内嵌 libuv 里有 4 个文件碰 CPU affinity，我只查了 2 个。**
这是本会话第三次同型错误（前两次：漏 `busybox`、漏 sysroot 探针的顺序）。

### 正确做法：先全扫，再动手

用 GitHub tree API 列出 `Utilities/cmlibuv/src/unix/` 下全部 52 个 `.c`
加 4 个 `.h`，逐个下载后按 `cpu_set_t|cpuset_t|CPU_ZERO|CPU_SET(|CPU_COUNT|
sched_getaffinity|pthread_setaffinity_np|uv__cpu_set_t|UV__CPU_AFFINITY`
grep。结果一次给出 4 个文件：

| 文件 | 守卫形式 | 处数 |
|---|---|---|
| `internal.h:566` | `UV__CPU_AFFINITY_SUPPORTED` 的定义 | 1 |
| `core.c:2064` | 裸 `#ifdef __linux__` | 1 |
| `process.c:73/315/429/1031` | 裸 `#if defined(__linux__) \|\| defined(__FreeBSD__)` | 4 |
| `thread.c:47` | 裸 `#if defined(__linux__)` | 1 |

`thread.c:189/277` 用的已经是 `UV__CPU_AFFINITY_SUPPORTED`（我第一版改的宏管得到），
只需改 47 行的类型定义。

现在 8 个 hunk 一次打完，全在 `patches/cmake-cmlibuv-no-cpumask-on-android.patch`。

### 扫出来的一个佐证

`thread.c:245` 里 libuv **自己**就写了：

```c
#if defined(__ANDROID__) || defined(__OHOS__)
  if (sched_getaffinity(pthread_gettid_np(*tid), sizeof(cpuset), &cpuset))
```

说明 libuv 知道 Android 上 `sched_getaffinity` 的形态不同。
而 `process.c:1035` 的 `#else` 分支已经是 `return UV_ENOTSUP` ——
所以加 `&& !defined(__ANDROID__)` 会走到**上游本来就写好的正确退路**，
不是我臆造的行为。

### 判据纪律

1. **修一处之后，先问「还有几处」，别等下一轮 CI。** 下一轮的成本是一次
   完整的 CI（约 5 分钟 + 排队），而扫一遍源码是本机几秒。
2. **用 API 列出全部文件再 grep，不要凭印象挑几个看。**
   我这次先看了 `core.c`、看了 `thread.c` 的部分行，就是没做全扫。
3. **「改完第一处就提交」在这里是错的节奏。** 前两次丢芝麻（busybox、
   sysroot 探针顺序）和这次是同一个模式的不同表现。

### 补记：修完 8 处后又冒出第四类问题，且根因在上游 CMakeLists

8 处 affinity 全修好后，`thread.c:969: error: call to undeclared function
'pthread_getname_np'`。这一条与 affinity 无关，是**另一个原因**。

Bionic 的声明是有条件的：

```c
#if defined(__USE_GNU) && __BIONIC_AVAILABILITY_GUARD(26)
int pthread_getname_np(pthread_t, char*, size_t) __INTRODUCED_IN(26);
#endif
```

API 35 满足后半条，缺的是 `__USE_GNU`，即 `_GNU_SOURCE`。

而 CMake 4.4.4 内嵌的 `Utilities/cmlibuv/CMakeLists.txt`（343 行）里：

```cmake
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")     ← 严格等于，Android 不匹配
  list(APPEND uv_defines _GNU_SOURCE)
  list(APPEND uv_sources src/unix/linux.c ...)
endif()
```

**没有 Android 分支。** 于是 Android 上这个分支不进：`_GNU_SOURCE` 没加、
`src/unix/linux.c` 没进编译。

对比两份文件就一目了然：

| 文件 | 行数 | Android 分支 |
|---|---|---|
| CMake 4.4.4 内嵌 `Utilities/cmlibuv/CMakeLists.txt` | 343 | **无** |
| libuv 上游 v1.x `CMakeLists.txt` | 920 | 有 |

**我一开始读错了文件** —— 前面查「Android 分支在第 273 行」时读的是
libuv 上游那份，不是 CMake 内嵌的裁剪版。两份同名不同内容，
这是本次的真实陷阱。

现在补丁覆盖 5 个文件 8 个 hunk（4 个源文件 7 处 + CMakeLists 1 处，
新增的 Android 分支照上游 v1.x 的写法，含 `_GNU_SOURCE` 与 `linux.c`/`linux.h`）。

### 补记：`$( )` 内嵌双引号 + 续行 + 中文全角括号，是一组会互相加害的组合

写宿主 tblgen 那段时，卡在同一个语法错误上试了六次：

```
line 198: syntax error near unexpected token `('
```

而 198 行根本不是我改的那行 —— bash 的报错位置在解析错位之后。
真正的原因在 169 行：

```sh
echo "…：$(ls "$NATIVE_DIR" 2>/dev/null | tr '\n' ' ' || echo '（没编成功）')"; \
```

三个因素叠在一起：`$( )` 内有未转义双引号 + 行尾续行 `\` + 中文全角括号 `）`
让「命令替换在哪里闭合」变得难以目测。

**定位方法本身也不可靠**，一并记下：

- `head -N file | bash -n` —— 截断 `{` / `$( )` 会报「unexpected end of file」，
  是假阳性（截断文件当然解析不完）
- 逐行数双引号奇偶 —— 会把 `awk '/... $NF/'` 这类单引号内的 `$NF` 算错
- 手工从某行起 `bash -n` —— 同一类截断问题

最后靠的是**把整段单独抽成文件测**，再在段内做行数二分 —— 段是完整的，
二分才有意义。

修法是先算好再打印，不把命令替换塞进 `echo`：

```sh
TB_LIST=$(ls "$NATIVE_DIR" 2>/dev/null | tr '\n' ' ' || true); \
echo "  宿主 tblgen：${TB_LIST:-（没编成功）}"; \
```

### 又一次自己犯：`set -e` 吞诊断

上面修好语法后立刻验「全缺失时会不会被 `set -e` 杀掉」：

```sh
TB_LIST=$(ls "$NATIVE_DIR" 2>/dev/null | tr '\n' ' ')   # ls 失败 → 返回 1 → 杀掉整块
```

实测：零输出、退出码 1，六行诊断一行都没打出来。
加上 `|| true` 后六行全出、退出码 0。

**这是本文档记过三次的同一件事**（16KB 对齐检查、node 报错信息引用已删变量、
cmake 失败分支里的裸 grep），而我是在自己新写的这一行上犯的。
所以凡是「失败分支里要打印东西」的代码，写完必须单独验一次
「所有输入都缺失时还能不能打出来」。

### 补记：`patches/` 下的 vendored 文件不该被剥注释

`comment-gate` 的 `SKIP_DIRS` 里加了 `patches`：

```js
const SKIP_DIRS = new Set(['node_modules', '.git', '.gradle', 'build',
                           'dist', '.cache', 'docs', 'patches']);
```

理由：`patches/cmake-cmlibarchive-contrib/android_lf.h` 是**从 libarchive
上游原样取来**的（sha256 记在同目录 `SHA256`），注释是上游的一部分，
剥掉就等于篡改了 vendored 内容。门禁的纪律是「我们写的代码不许留解释性注释」，
不适用于「原样引用的第三方文件」。

这类文件靠 `SHA256` 管住完整性 —— 配方里 `sha256sum -c` 校验不过就判红，
所以「原样」这件事有机制保证，不靠注释门禁。

---

## 第十九条：CMake 内嵌的第三方子工程，上游有的东西它不一定带

### 现象与根因

libuv 的四处修好后，错误换到 `cmlibarchive`：

```
Utilities/cmlibarchive/libarchive/archive.h:121:10: fatal error:
  'android_lf.h' file not found
```

`archive.h:120-122`：

```c
#if defined(__LIBARCHIVE_BUILD) && defined(__ANDROID__)
#include "android_lf.h"
#endif
```

而 `Utilities/cmlibarchive/CMakeLists.txt:8-10`（**原样搬自 libarchive 上游**）：

```cmake
PROJECT(libarchive C)          # ← 第 7 行，于是 PROJECT_SOURCE_DIR = Utilities/cmlibarchive
if (ANDROID)
  include_directories(${PROJECT_SOURCE_DIR}/contrib/android/include)
endif()
```

**CMake 只搬了 `cmlibarchive/libarchive/` 这一个子目录，没带 `contrib/`。**
用 GitHub tree API 查 CMake v4.4.4 全树确认：树里没有任何 `contrib/android/*`。

而 `android_lf.h` 在 libarchive 上游是有的：

```
contrib/android/include/android_lf.h     （libarchive/libarchive master）
```

### 修法

把上游那个文件放进 `patches/cmake-cmlibarchive-contrib/`，带 `SHA256`，
配方里 `sha256sum -c` 校验后拷到
`$SRC/Utilities/cmlibarchive/contrib/android/include/`，
那行 `include_directories` 就自然成立 —— **不改 CMakeLists，不打补丁**。

三个分支验过：文件缺（报错退出）、sha256 不符（报错退出）、正常（拷贝并继续）。

### 判据纪律

1. **上游的 `contrib/` / `extra/` 这类附加目录，vendor 时常被漏掉。**
   内嵌子工程的 CMakeLists 仍按上游的路径去找文件，找不到就是硬失败。
2. **先查上游有没有那个文件**（tree API），再决定是补文件还是改判断 ——
   改判断（像 libuv 那样）是最后手段，因为语义可能变。
3. **vendored 文件进 `patches/`，同时把它加进 `SKIP_DIRS`，
   完整性交给 sha256 而不是注释门禁。**

---

## 第二十条：把注释搬进报错消息时，`\${VAR}` 的转义会失效

### 起因（CI 用 38 秒就失败，日志里只有两行）

```
[cmake] libuv 补丁已打（Android 上关掉 CPU affinity）
scripts/build-userland-cmake.sh: line 77: PROJECT_SOURCE_DIR: unbound variable
##[error]Process completed with exit code 1.
```

上一轮我把 `# 注释` 搬进 `note "…"`（注释门禁不允许代码里留解释性注释）。
注释里写的是 `include_directories(${PROJECT_SOURCE_DIR}/…)` —— **在注释里
`${}` 不会被 shell 解释**，所以读起来完全正常；搬进双引号后它就成了真变量。

这是本会话**第六次** `set -u` 未定义变量（诊断块用 `$TC`、node 报错信息引用
已删变量、python3 的 `PREFIX` 顺序、sysroot 探针、cmake 失败分支的裸 grep、
llvmtoolchain 的 `TB_LIST=$(ls …)`、以及这一处）。

**前几次是「写代码时用了没定义的变量」，这一类是「搬动已有文本时丢掉了转义」。**
后者更隐蔽：原文是对的，日志里读起来也对，只有在 `set -u` 下执行才炸。

### 固化：`tools/verify/bare-vars-in-strings.js`

扫 `scripts/*.sh`：双引号里出现**没有默认值**的 `${VAR}`（即不是 `${VAR:-…}`）
且该变量在本文件内从未赋值 —— 这种在 `set -u` 下必然杀掉脚本。

- 45 个脚本，当前 0 处
- 反例验证：把 `${PROJECT_SOURCE_DIR}` 塞进 `build-userland-make.sh` 的
  `note` 里，立刻被抓到 —— 用的正是今天真实发生过的那一个变量名
- 顺带修一处真缺陷：`build-native-capabilities.sh:226/227` 的
  `${GITHUB_WORKSPACE}` 是裸的，Actions 里必然存在所以 CI 没报，
  但本机跑会死 → 改成 `${GITHUB_WORKSPACE:-$ROOT}`

已挂进 `ci.yml`，紧邻注释门禁那一步。

### 写这个扫描器时我又犯了同一个错

`ROOT = path.resolve(__dirname, '..')` —— 从 `tools/verify/` 往上**一层**
是 `tools/`，不是仓库根。扫描器因此报「扫描目录不存在」并退出 2。

这与我早先那次（写完后「什么都没扫却报 PASS」）是同一个错误的两个方向：
上次是**目录不存在时静默通过**，这次是**显式报错但报的路径是错的**。
两者的共同点：**没验证 ROOT 算出来是什么，就相信它算对了**。
现在这类扫描器都会把 `ROOT` 打出来并在目录不存在时硬红。

### 补记：`ld.lld: undefined symbol: cmCPackAppImageGenerator()`

libarchive 补上 `android_lf.h` 之后，编译一路走到 1015 行才失败：

```
ld.lld: error: undefined symbol: cmCPackAppImageGenerator::cmCPackAppImageGenerator()
gmake[2]: *** [Source/CMakeFiles/cpack.dir/build.make:117: bin/cpack] Error 1
```

`cpack` 是打包工具，`userland-verify.json` 对 cmake 的判据只要求
`bin/cmake` 起得来且能真 configure + build 一个项目（`entry: bin/cmake`）。
它失败的原因（构造器定义缺失）不在本仓责任范围内，也不该由我去查 CMake 内部。

**做法：`cmake --build … --target cmake`。**
`cmake --install` 不受 `--target` 限制，`share/cmake/Modules` 照常安装，
判据要求的模块目录不会少（配方第 169-181 行还会单独拷一份进 `dist/share/cmake`）。

试过但**放弃**的路：`-DBUILD_CPACK_GENERATOR=AppImage`。
想关掉 AppImage generator，但 `Source/CPack/CMakeLists.txt` 我用三种方式都取不到
（raw.githubusercontent 404、API 也返回 404，tree API 里只有
`Source/CPack/cmCPackAppImageGenerator.cxx`），**没法确认那个变量名是否存在**。
不凭记忆写一个可能无效的开关 —— 直接不编那个目标更确定。

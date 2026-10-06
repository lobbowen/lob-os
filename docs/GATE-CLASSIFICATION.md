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

等 `scripts/build-native-llvmtoolchain.sh` 真上线（那时它才与商店件
在同一轮里跑），再去掉 `continue-on-error` —— 那才是硬判据的位置。

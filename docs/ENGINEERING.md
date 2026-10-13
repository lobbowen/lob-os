# 工程规范

## 目录结构

```
work/
  lob-os/                 工程仓 —— 只有工程源码与构建文件
    .github/              CI workflow（23 个）
    container/            Android 应用（Kotlin，140 个文件）
    scripts/              构建与件流水线（被 CI 引用）
    patches/              补丁
    build.gradle.kts  settings.gradle.kts  gradle.properties  version.json  .gitignore
  docs/                   文档（不入工程仓）
    release/              RELEASE.md  RELEASE-POLICY.md
    verification/         ACCEPTANCE.md
    audit/                AUDIT-REPORT.md
    architecture/         FILE-MAPPING.md  FUNCTIONAL-POINTS.md
  tools/                  工具（不入工程仓）
    release/              keygen.sh  keygen-android-keystore.sh  gen-component-keys.js
    verify/               meta-field.js  pick.sh  verify-component-build-date.sh
  .lobos-tools/           obs.js（CI 观测）  tok（凭据，600）
```

### 什么该在工程仓

```
源码、构建配置、CI workflow、构建流程要用的脚本
仓内允许三个包级 README：api/  kernel/  services/
  —— 它们在源码目录里，说明该包的分层职责，属于工程文件
```

### 什么该在仓外

```
文档        docs/<类>/            验收、发布规范、审计、架构
工具        tools/<类>/           本地手工用的脚本
凭据        .lobos-tools/tok      600 权限
临时产物    .lobos-tools/ 下       下载缓存、诊断包
```

### 判据

**被 CI 引用的脚本一律留仓内。**
它们不是工具，是构建流程的组成部分。移出去要同时改 23 个 workflow 的路径，
收益为零、风险全在。

## 三条链要分开

```
发布链   .github/workflows/*          改它要过 CI
监测链   tools/obs.js status|steps    只读，不碰
报告链   tools/obs.js diag|aid        只取，不碰
```

之前我把三者耦在一起：改一次下载方式就把查询方式也重写，
同一个坑踩了六遍（302 死循环、TLS 抖动、缺 Authorization 头…）。

`obs.js` 固定在仓外，不再各处重写。

## 传递 APK 的固定做法

```
CI 出 APK  →  build-apk 自动发到 os-debug-<sha7> Release
           →  设备从镜像或直连下（813 KB/s ~ 967 KB/s）
           →  装机
```

**不要从 CI artifact 直下**（`api.github.com` → 302 → Azure blob）：
这台机器 3.9 秒一个请求、76 MB 反复超时，试了四五轮没成。

## 清理的规矩

**删任何东西之前，先查两类引用：**

```
① 有没有人调用它       grep -rl "<文件名>" scripts/ .github/
② 有没有别的脚本 source 它   ← 这一类最容易漏
   pick.sh 名字看着像本地工具，实际是 verify-apk-signing.sh 的依赖
```

**我连续两轮踩同一个错：**

```
第一次  按「不是工具就删」清 .tmpwork/ → 把 token 和工具一起删了
第二次  删 keys/ 与移 pick.sh        → build 挂了
         第 354 行读 keys/release.cert
         第 29 行用 "$HERE/pick.sh"
```

**推论：目录名和文件名不说明它是不是工具。判断依据只有一条 —— 谁在用它。**

**共享工具 vs 本地工具**

```
被构建脚本调用   → 留仓内（pick.sh、fmt.sh 都是这类）
只有人手工跑    → 移出（keygen.sh、gen-component-keys.js 这类）
```

## 门禁

```
check-no-comments.sh        代码里不许有注释（104 个 Kotlin 文件）
check-workflow-shell.sh     workflow 的 run: 必须是合法 shell（196 个块）
```

两条都在本地跑，不需要 CI。
`check-workflow-shell.sh` 抓过两类真问题：双引号奇数、shell 续行的 `\\`。

## 三条反复踩的坑

```
① 拿观察当结论
   字节变大 → 归因 aapt2（其实是自己 gunzip 的）
   usr/lib 空 → 判落位错（件在 opt/，落位是对的）
   诊断说「件全部就位（0 件）」而真相是 8 件已装
   → 观测到异常，先审自己的代码

② 断言「X 可行」之前先量一个字节或找一个来源
   zip 绕了 6 轮（tar.gz → pkg → zip），
   根因是 package-piece-zip.sh 名字叫 zip 打的却是 tar -czf
   → 推断栽的跟策略栽的一样多

③ 改 Kotlin 签名换行时数括号
   改完 treeRootEnv 的括号是 26/27 —— 行尾的 = buildMap { 一起吃掉了
```

## 真·地基债（借 Android 的，内化不了）

```
LD_PRELOAD       4 个垫片必须在进程启动前生效，Android 无别的机制
LD_LIBRARY_PATH  Android 链接器不读 ld.so.cache；
                 linker namespace 试过 —— 符号全在 linker64 内部
                 带 __loader_ 前缀，应用侧拿不到句柄
```

详见 `docs/release/RELEASE-POLICY.md`。

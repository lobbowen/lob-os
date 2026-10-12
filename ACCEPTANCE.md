# 真机验收记录

每一轮真机验收的结果、参数与踩过的坑，都写在这里。
**已验收的轮次标记 ✅，未验收的标记 ⬜。**

---

## 配对规范

### 前置

- 设备开「开发者选项 → 无线调试」
- 我们这台机器与设备在同一网段

### 两个端口，不是一个（这是最容易踩的坑）

Android 无线调试**同时开两个端口**：

| 端口 | 用途 | 谁给 |
|---|---|---|
**配对端口** | 建立信任关系（交换密钥） | **人看屏幕上的「使用配对码配对设备」弹窗** |
**连接端口** | 之后的 adb 通信 | **不给，要自己找** |

配对码只在**配对端口**上用一次。连上之后，走的是**连接端口**。

### 标准流程

```bash
# 1. 设置凭据目录（仓外的那套 JS 客户端）
export LOBOS_ADB_DIR=<某个可写且持久的位置>
NODE=<node 路径>

# 2. 配对（一次性，用配对端口 + 配对码）
node cli.js pair --host <IP> --pair-port <配对端口> --code <配对码> --timeout-ms 60000

# 3. 扫出连接端口（配对端口区间之外，逐个试）
#    Android 无线调试的连接端口通常也在 30000-46000 区间
#    逐个 net.connect 测试，只有一个会 connect 成功

# 4. 用连接端口执行命令
node cli.js shell --host <IP> --connect-port <连接端口> --cmd "<命令>" --timeout-ms 60000
```

### 已验证的设备参数

| 设备 | 系统 | 配对端口 | 连接端口 | 备注 |
|---|---|---|---|---|
**PGU110**（0.0.2 那轮） | Android 16 / SDK 36 / OPPO | 37865 | 39919 | 458 包，干净起点 |
**PGU110**（0.0.3 那轮） | 同上 | （已配对） | **46888** | 同一台，同一天，端口变了 |
PLP120（更早） | Android 17 / SDK 37 | 未记录 | 35271 | 装过 `lobos.app` |

**连接端口每次重启无线调试都会变。** 每次连接前都要重新扫。

### 换了端口就要重新配对（踩过 4 次）

扫描找到的开放端口是**连接端口**，不是配对端口。两者不通：

```
用连接端口去 pair  →  SSL routines:tls_validate_record_header:wrong version number
用连接端口去 shell →  adb 连接超时（8000ms）
```

两种都说明**信任关系没了**，要去重新 pair（看设备屏幕
「使用配对码配对设备」弹窗里的端口 + 配对码）。

**扫描范围要够**：连接端口落在 **30000-50000**。
本轮用 1000 步长扫 37000-46000 完全没扫到 46888，差点判成
「无线调试关了」。**要逐个扫，不要稀疏采样。**

### 用 debug 版验收，不要往程序里加诊断工具

验收一律用 **debug 版 + `run-as`**：

```
run-as lobos.os cat files/var/log/diagnostics.txt
run-as lobos.os ls -la files/usr/lib/
run-as lobos.os cat files/var/log/<任何文件>
```

**不要为了「让外部看得见」往程序里加镜像/导出类的诊断代码。**

曾经加过一个 `Mirror`（把诊断镜像到应用外部目录，理由是「shell 读不到私有目录」）——
那是在还没用 debug 版验收时的权宜之计。有了 `run-as` 之后它**零价值**，
而且是真工程债：

| 代价 | 表现 |
|---|---|
每次写诊断要多一次 IO | 拖慢主流程 |
写入语义容易错 | 曾用 `outputStream()`（truncate）冒充追加，把快照覆盖到只剩最后一行 |
多一个要维护的通道 | 坏了不报错，只是「看起来数据不全」—— 比没有更坏 |
代码里混入「为了被看到」的东西 | 偏离「程序自己知道自己怎么了」 |

**验收工具应该在程序外面（adb + run-as），不该长在程序里。**

### 用 serve，不要用一次性 shell

adb-client 本来就有常驻连接服务（`node cli.js serve`，JSON-RPC over stdio），
设计意图就写在 `cli.js` 顶部：进程内复用一条会话，断了自己退避重连。

**一次性 `cli.js shell` 是错的做法** —— 每个命令新起进程、新建连接、
结束时 `closeAll()`（`cli.js:185`）。

实测后果：推 74 MB 的 APK 推到 68% 就断了。而且断一次有二次伤害 ——
**设备会把 key 从信任列表里踢掉**，之后连不回来，必须重新 pair。

### 扫端口的顺序（别搞反）

| 步骤 | 端口来源 | 用途 |
|---|---|---|
1 | 屏幕「使用配对码配对设备」弹窗 | **配对端口**，跑 `pair` |
2 | 屏幕「无线调试」页面的「IP 地址和端口」 | **连接端口**（权威来源） |
3 | 扫 30000-50000 | 兜底 —— 但页面上有就别扫 |

**页面上的端口是权威的。** 扫端口只是兜底，而且要先确认区间对。

配对成功 ≠ 能连接 —— 配对端口开了，连接端口可能还没开，要等。

### 长传输会拖垮 adb

一次 push 连续 74 分钟必断（见上一节）。要**分段推**：每段 8 MB，
推完重连一次再续。断点续传可行 —— 设备侧 `/data/local/tmp/l.b64`
里的内容还在。

### 踩过的坑

| 现象 | 原因 | 处置 |
|---|---|---|
`ECONNREFUSED 192.168.88.122:40059` | 那不是当前配对端口（端口已变） | 要人重新看屏幕 |
`ping` 通但所有端口 `ECONNREFUSED` | 同上，端口全错 | 扫描整个区间 |
连上后所有 shell 命令「没执行」 | 我自己的封装脚本传参错了（`$1` 未正确转义） | 直接调 `cli.js` 验证 |
`adb-client` 没有 `push`/`sync` | 那套客户端只实现 `pair`/`serve`/`shell` | 改用 base64 分块经 shell 传 |

### 传输大文件（APK 66 MB）

设备自带的 `curl` 连不上 GitHub：

```
curl: (35) TLS connect error: OPENSSL_internal:invalid library (0)
```

设备的 curl 是 `8.12.0-DEV (Android) BoringSSL`，**TLS 握手失败**（能 ping 通，`https://example.com` 返回 200，但 GitHub 不行）。

改用 **base64 分块经 adb shell 传**：

| 项 | 实测 |
|---|---|
块大小 | 48 KB 原始字节 → 64 KB base64 |
速率 | 约 **74 KB/s** |
66 MB 耗时 | **608 秒**（约 10 分钟） |
完整性 | 传完 `sha256` 与源一致 |

---

## 验收轮次

### ⬜ 第 1 轮 · 三层架构重构版（0.0.2）

**日期** 2026-09-11
**设备** PGU110 / Android 16 / SDK 36 / OPPO / arm64-v8a
**版本** `os-release-0.0.2`（versionCode 2）
**sha256** `7b5a8e8d1bab75a1d4a3502e95689b784d1c1eabf73ceb5d22b8d4a1582da360`
**签名** `0f80988f…`（与 0.0.1 同一把，可覆盖安装）
**装机** `pm install -r` → Success

#### 结果

| 检查项 | 结果 | 实测 |
|---|---|---|
安装 | ✅ | `lobos.os 0.0.2 versionCode=2` |
进程 | ✅ | pid 1972 活着 |
`OsHostService` 前台服务 | ✅ | `isForeground=true`，通知 id=1004 |
通知 | ✅ | 2 条 |
崩溃 | ✅ | `logcat -b crash` 无记录 |
**启动入口** | ❌ | `QuickAppLaunchActivity` 启动后立即 `finish()` |

#### 发现的缺陷

**① LAUNCHER 无 `EXTRA_ID` 时静默退出**（`services/app/QuickAppLaunchActivity.kt:12-16`）

```kotlin
val id = intent?.getStringExtra(EXTRA_ID)
if (id.isNullOrBlank()) { finish(); return }
```

UI 清空后 `SetupActivity` 被删、`QuickAppLaunchActivity` 顶替成 LAUNCHER，
但**桌面点图标进来不带 `EXTRA_ID`**（那个 extra 只有 `DesktopIcons.request` 走
`intent(base, id)` 时才塞）。结果：点图标 → id 空 → `finish()` → 什么也不发生。

清 UI 时的漏项 —— LAUNCHER 换人了，没考虑「不带 id 时该怎么办」。

**② 设备上一个 Program 都没有**

控制面板也没装。核实后发现：**「控制面板随 APK 内置」这件事没有实现** ——
它不在 24 项件清单里、没有 `component-meta.json`、不经 `PrefixProvisioner` 铺位。
它现在只是个普通 Program，要走 OTA 才有。

这与「控制面板是预装快应用、随 APK 打包进去」的设计要求对不上。

#### 无法验证的项（观测能力缺口）

| 想验的 | 为什么验不了 |
|---|---|
13 个基础件有没有铺开 | shell 读不到应用私有目录（Android 11+ 关闭） |
`run-as` | release 版不可 debuggable |
`os.provisioning.get` | 需要 Program 先连上桥，而设备上没有 Program |
诊断日志（`diagnostics.txt`） | 同上，读不到私有目录 |
`logcat --uid=<uid>` | Android 16 上按 uid 过滤无输出 |

**这一条是本轮最大的收获：程序在真机上发生了什么，我们基本看不到。**

---

## 待解决

### 可观测性（最高优先）

真机验收的瓶颈不是「装不上」，而是**装上之后看不见**：

- 应用私有目录 shell 读不到
- release 版 `run-as` 不可用
- `logcat --uid` 在新系统上失效
- 程序自己的诊断写在私有目录的 `var/log/diagnostics.txt`，外部无从读

**在解决这个问题之前，任何「真机有没有问题」的判断都只能靠猜。**

可能的出路（待定）：
1. 程序把关键状态暴露到 shell 可读的地方（`/sdcard` 或 content provider）
2. 提供 debug 版专门用于验收（可 `run-as`）
3. 把诊断写到 logcat 而不是文件
4. 提供一条 adb/文件侧能读的状态查询通道

### 待定的设计决策

- LAUNCHER 无 id 时的行为：列程序 / 开控制面板 / 明确提示
- 控制面板怎么进 APK（现在没有实现）
- 13 个基础件的铺位结果如何对外可观测

## 取件链的三条判据（2026-10-11 定）

这一段在「8 个件从零到铺进 APK」上绕了几轮，绕的根子是同一个错：
**拿观察当结论**。留下的三条规矩：

### ① 改数据就要有对照测量

```
件包在 APK 里的字节数 ≠ 源包字节数
  ≠ aapt2 拆包
  可能是取件脚本自己 gunzip 过

Build APK 的 failure-diagnostics 现在固定记两份：
  src-bytes.txt         src/main/assets 下每个文件的字节数
  apk-pieces-bytes.txt  APK 里对应条目的字节数
两份一对，才知道哪些条目被动过。
```

### ② 观测到异常，先审自己的代码

```
字节变大、条目消失、解包失败 —— 第一反应应该是「我改的那步是不是本来就这样」，
不是「框架/工具对我做了什么」。
本轮真因是校验器 unzip 后少写了一层 assets/ 目录，
我却先后怀疑了 aapt2、noCompress、.pkg 后缀、zip、res/raw 五个方向。
```

### ③ 断言「X 可行」之前，先量一个字节或找一个来源

```
不能靠推断。本轮三次断言（noCompress 有效 / .pkg 有效 / aapt2 拆 tar）全错，
三次都没有先量一个字节。

已知事实（来源 elizaOS/eliza commit b5424ec 的注释）：
  aapt2 对 *.tar.gz 会在打包时剥掉 .gz 后缀，noCompress 管不到那一层。
  它的 noCompress 只控制 ZIP 层的压缩。
  —— 这条是真的，但与本轮失败无关（本轮根本没走到那一步）。
```

### 三条链必须分开

```
发布链   .github/workflows/*        —— 不碰
监测链   obs.js status / steps      —— 不碰
报告链   obs.js diag                —— 不碰
之前我把三者耦在一起：改一次下载方式就重写查询与下载，
同一个坑踩了六遍（302 死循环、TLS 抖动、缺 Authorization 头…）。
脚本固定在 .tmpwork/obs.js，三个子命令，重试内建，不再各处重写。
```

## 真机验收的传文件办法（2026-10-11 定）

**不要用分块 base64 把 APK 推给设备。** 66.8 MB 推了两轮都断（E2BIG、
超时），而且慢得没法用。

按可用性排序：

```
① 设备自己从国内镜像下（最快）
   gh 代理：https://ghproxy.net/https://github.com/<owner>/<repo>/releases/download/<tag>/<file>
   设备 curl -sL -o /data/local/tmp/x.apk "<那个地址>"
   下完 sha256sum 对一遍。
   探测镜像是否可用：curl -r 0-1023 -o /dev/null -w '%{http_code}' → 206 就是能用

② 本机起 HTTP，设备直接 wget/curl
   node .tmpwork/http.js <根目录> 8899
   绑 0.0.0.0，用**设备同网段的那个地址**（本机有多张网卡：
   vgate0 是 172.30.x.x，wlan0 才是 192.168.88.239 —— 用错那张设备访问不到）
   同样要 sha256sum 对一遍

③ 从 CI artifact 下（要 token，且本机 TLS 时断时续）
   https://api.github.com/repos/<o>/<r>/actions/artifacts/<id>/zip
   跟随 302 时**必须去掉 Authorization 头**（blob 不认 GitHub 的头）

**每次传完都要 sha256sum 对一遍。** 传了 66 MB 不知道对不对，
后面验的所有东西都不可信。

## 设备执行器

本机（Android 侧）自带一套完整的 adb 客户端：

    NODE=/data/data/lobos.app/files/usr/bin/node
    CLI=/data/data/lobos.app/files/adb-client/cli.js
    LOBOS_ADB_DIR=/data/data/lobos.app/files/adb   ← 必须有这个，否则报「未注入」
    $NODE $CLI shell --host 192.168.88.122 --connect-port <端口> \
      --cmd "<命令>" --timeout-ms 60000

它把 TLS 也做了（Android 11+ 无线调试走 TLS 配对证书，adbkey 走不通）。
封装在 .tmpwork/dev.js 里。

**connect-port 每次重连都变** —— 手机「无线调试」页面上「IP 地址和端口」
那一行的端口才是它。配对端口只用来交换配对码，几秒后关闭。

**不要自己重写 adb 协议** —— 我为了绕开它写了几轮，全错：
banner 结尾要是 NUL 不是 \n、认证要走 TLS 证书、还要处理 shell_v2 分帧。
本机上早就有一套能用的。

## 真机验收：8 个预装件（2026-10-12 定）

### 结论

```
真机 PGU110 / Android 16 / API 36，装 os-release-0.0.4 debug
  ✓ 8 个件全部装上
  ✓ 落位 opt/<件>/<版本>/，层级与版本链完整
  ✓ usr/lib 有 ldconfig 建的 SONAME 软链（libz.so → .so.1 → .so.1.3.2）
  ✓ usr/bin 有 8 条命令软链
  ✓ ld.so.conf 与 ld.so.cache 已写
  ✓ 跨件依赖全部解析：
      libcurl.so → libssl.so → opt/openssl/3.6.3/
                → libcrypto.so → opt/crypto/3.6.3/
                → libz.so.1 → opt/zlib/1.3.2/
  ✓ 命令类件零配置可跑（rg --version / bash -c）
```

### 「装上了」不等于「能工作」

这是本段最重要的一条。分三次才验到位：

```
第一轮  8 个都装上了，但 ls files/usr/lib 是空的 → 我判「落位路径错了」
        真因：件本来就在 opt/，usr/lib 空是对的；我在拿错误的预期当判据

第二轮  手工 export PATH 后 bash 能跑 → 我以为成了
        真因：那是我自己设的环境变量，不是「系统给的」

第三轮  linker64 --list libcurl.so  → 三级依赖全部解析到 opt/ 下的件
        这才是「正常工作」的证据
```

**验证「能工作」问的不是「装上了吗」，是「它在真实场景里能做事吗」。**

### 按 Linux 的分工，缺的是 ldconfig 那一侧

```
dpkg 那一侧      PreinstalledPieces / ProgramInstallPipeline   装件
ldconfig 那一侧  LdConfig                                       链接
```

之前我把两件耦在一起（装件时顺手建软链），所以：
- 没有 cache
- 任何进程都无据可依
- 只能靠给每个程序写环境 —— 那正是「装一个程序要告诉它系统有什么」

现在拆开：`LdConfig.run()` 扫 `opt/<件>/CURRENT` 指向的版本，
读每个 `.so` 的 ELF `DT_SONAME`，在 `usr/lib/` 建 SONAME 软链，
写 `etc/ld.so.conf` 与 `etc/ld.so.cache`。

`usr/lib` 里放软链而不是件本体 —— Linux 就是这样
（`/usr/lib/x86_64-linux-gnu/libz.so.1 → libz.so.1.2.11`），
每件保留自己的版本目录，`usr/lib` 只是「当前可见的那一份」。

### 但 Android 上有一条 Linux 没有的约束

```
$ ls /system/etc/ld.so.conf
  系统里没有
```

**Android 的链接器不读 `ld.so.cache`，只认 `LD_LIBRARY_PATH`。**

所以 `etc/ld.so.conf` 与 `etc/ld.so.cache` 是「按 Linux 形态做的记录」
（对人可读、有诊断价值），**不是链接器的输入**。
可用性靠把 `usr/lib` 放进程序的 `LD_LIBRARY_PATH`。

**这不是照抄 Linux，是「先照抄看清哪里不成立，再按目标平台改」。**

### 还没做完的（下一段）

```
程序的库搜索路径目前靠 LD_LIBRARY_PATH 逐个下发。
用户的要求是「装一个程序不用告诉它这个环境」，那套不成立。

正解是 linker namespace（API 29+，minSdk 已提到 29）：
  在 bionic-compat.so 里加 constructor，进程启动时把 usr/lib
  注册进自己的 namespace，之后 dlopen 自动在那里找。

加完之后 RuntimeEnvironment 那套「给每个程序写 env」应该删掉 ——
机制与补丁并存就是又一层耦合。

（未做。minSdk 29 与 targetSdk 35 已先落地，为它铺路。）
```

### 这段踩的坑（都已修，记下来）

```
① package-piece-zip.sh 名字叫 zip，第 91 行打的是 tar -czf
   —— 「标准件包」从一开始用的就不是 zip。我绕了 6 轮才承认这点。

② Release 上的资产名是 <id>-<deps>+…tar.gz，里面装的是 zip
   → 判据不能看扩展名，要看 magic 字节（scripts/toolchain/fmt.sh）

③ PreinstalledPieces 把 .zip 当目录读 component-meta.json
   —— 以为 assets 里的包已经摊开了

④ infraSourceFile 把件落进 nativeLibraryDir（还把 libName 当目录名）
   —— 8 个件里 6 个落错，只有 libName 为空的 2 个侥幸对了
   —— 且这不是预装件特有的：stateDirOf 对所有件都走它，OTA 件也会落错

⑤ PrefixProvisioner 扫 assets/supply/meta（那个目录早删了）
   → 永远返回空 → 诊断报「件全部就位（0 件）」，而真相是 8 件已装
   —— 这就是「你这边搞好了，我那边还是老样子」

⑥ File.isSymbolicLink 是 Java 17 API，Android 没有
⑦ treeRootEnv 改签名换行时把行尾的 `= buildMap {` 一起吃掉了
   —— 改完要数括号

⑧ installOne 里 7 处 return false 不记原因
   → 真机上只看到「失败=8」，不知道卡在哪一步
   —— 现在每处都有具体原因
```

### 这段的判据演进

判据加了四次，每次都是「只验形式、不验内容」的同一个病：

```
① 只判 Release 存在          → 旧格式包被当新的用
② 只判包里有没有 meta       → entry 错配的包照样命中
③ 只验 entry 那个文件       → 不完整但看起来对的包照样命中
④ 加验「还需要的文件」      → 这才够
⑤ 改成按内容判格式          → 扩展名在骗人时也够
```

**判据被绕了 4 轮才完备，而每次都是真机上炸出来才改的，不是推理出来的。**

## 系统视图与透传（2026-10-12 定）

### 结论

```
真机探针（启动时自动跑，验完已删）：

  [OK] envprobe: 系统视图正常（18 项全过）
        ✓ PATH 含 usr/bin
        ✓ LD_LIBRARY_PATH 含 usr/lib
        ✓ 命令 bash / rg
        ✓ bin/库 6 条
        ✓ 库 9 条（含 zlib 三层版本链 libz.so → .so.1 → .so.1.3.2）
```

配合此前实测：

```
$ env -i PATH=<usr/bin> LD_LIBRARY_PATH=<nativeLibDir>:<usr/lib> \
    linker64 --list opt/curl/8.22.0/lib/libcurl.so
    libssl.so    => opt/openssl/3.6.3/lib/libssl.so      ┐
    libcrypto.so => opt/crypto/3.6.3/lib/libcrypto.so     ├ 跨件依赖
    libz.so.1    => opt/zlib/1.3.2/lib/libz.so.1          ┘ 全解析
```

### 「装一个程序不用告诉它系统有什么环境」

之前我给每个程序写一份 `LD_LIBRARY_PATH` + `PATH`，把这件事当成了设计。
用户的原话：

> 「装一个程序，我还要给这个程序把这些环境变量都要再写一遍，
>   告诉这个程序里面这个系统有什么环境，这怎么可能」

看完代码才明白**那不是「给每个程序塞环境」，而是「没有系统视图」**：

```
ProcessSupervisor.spawn(envMode = ENV_CLEAR)
  → clear() 之后放我们给的 env
  → 我们完全控制程序看到什么 —— 机制一直在

GuestAdapter.programPlan(i, inheritedPath) 的 env 里只有：
  LOBOS_BRIDGE_SOCKET / SESSION_TOKEN / … / 程序自己声明的 env
而 inheritedPath（宿主 PATH）传进来了，从头到尾没被用过
```

**缺的就是「把系统装了什么组装成那份 env」这一步。** 补上之后：

```
① 我们给程序这份 env              ← 补的
② 程序里 bash 跑 curl             ← bash 继承自己的环境（Unix 语义）
③ node 里 spawn 子进程            ← 同上
```

②③ 我们不用做任何事。**这才是容器**：不是逐个给程序塞，是我们有一套系统环境，
程序从我们这里继承它。

### Android 的定位：它是硬件，不是系统

```
Android = 电源、内存、CPU 调度、内核、Zygote、链接器、SELinux 沙箱、binder
Lob OS  = 装在这台硬件上的系统：装什么、跑什么、怎么透传、怎么编排、怎么授权
```

按这个定位逐条推导「借 Android 的能力」：

| | 结论 | 依据 |
|---|---|---|
| 生命周期组件 / Binder IPC / ProcessBuilder | **借得对** | 硬件/内核层，自己造是重复造轮子 |
| `LD_PRELOAD` | **借，但内化不了** | 4 个垫片必须在进程启动前生效，Android 无别的机制 |
| `LD_LIBRARY_PATH` | **借，但内化不了** | linker namespace 试过：符号全在 `linker64` 内部带 `__loader_` 前缀，应用侧拿不到句柄 |
| `nativeLibraryDir` 塞件 | **不该借** | 那是旧实现残留（43 MB），不是「借用」 |
| `getenv("PATH")` 当系统环境 | **不该借** | 程序的 PATH 属于系统视图，宿主 PATH 只是 fallback |

**后两条已改/已清。前两条是已知地基债**，写在 `RELEASE-POLICY.md`。

### 传递 APK 的固定做法（省时间的规矩）

```
CI 出 debug APK → build-apk 自动发到 os-debug-<sha7> 这个 Release
              → 设备从镜像或直连下
              → 装机
```

**为什么**：从 CI artifact 直下（`api.github.com` → 302 → Azure blob）
在这台机器上 3.9 秒一个请求、76 MB 反复超时，试了四五轮没成。

实测设备侧速度（都是 8 MB 采样）：
```
直连 GitHub   813 KB/s
ghfast.top    967 KB/s
ghproxy.net   （0.0.4 那轮一次就成）
```

**设备走 Release 比走 artifact 快且稳。以后统一走 Release。**

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
**PGU110**（本轮） | Android 16 / SDK 36 / OPPO | 37865 | **39919** | 458 包，干净起点 |
PLP120（上一轮） | Android 17 / SDK 37 | 未记录 | 35271 | 装过 `lobos.app` |

**连接端口每次重启无线调试都会变。** 每次连接前都要重新扫。

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

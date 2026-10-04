# 隔离验证清单（照着做，逐步可判成败）

验的是**新代码在真机上成立** —— 此前所有验证都在 CI 或桌面 shell 上，真机装的是老 APK。

隔离包与设备上现有的 `lobos.app` **并存**（不同 `applicationId` + 不同签名 ⇒ Android 当成
两个应用；开发环境零影响）。开发包原样保留，它上面的 6 件工具链与 node 不受影响。

包：`app-debug.apk`（9.3MB，artifact `apk-isolated-lobos.app.verify`）

---

## 0. 前提

- 一台连着 adb 的机器
- 设备上现有 `lobos.app`（开发用）**不要动**
- 网络能连 `hubcdn.zll.ink`（商店供给从那里拉）

## 1. 装

```bash
adb install -r app-debug.apk
```

**成功标志**：`Success`，且**没有** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
（若有，说明传了 `-r` 覆盖装 —— 对新 applicationId 不该发生，去掉 `-r` 重试）。

## 2. 确认两个应用并存

```bash
adb shell pm list packages | grep lobos
```

**成功标志**：两行都有 ——
```
package:lobos.app
package:lobos.app.verify
```

只有一行 ⇒ 没装上，回第 1 步看报错。

## 3. 首次启动，让新代码的供给链真跑一遍

```bash
adb shell monkey -p lobos.app.verify -c android.intent.category.LAUNCHER 1
```

新代码的 `OsApplication.onCreate` 会在后台线程里跑
`CatalogClient.refresh` → `SupplyProvisioner.ensure`。**这一步是本次要验的核心**：
它是那 400 行 `ensure()` 第一次在真机上执行。

装上后**只要启动应用**（任何入口）就会跑 —— 不需要先装程序、不需要 node 已就位。
这一条是特意改的：原先供给挂在 `BootReconciler` 里，而它只被 `InstanceHost`
（程序宿主）调用，于是链成了「程序启动 → 装 node → 但这次启动已因 node 缺失
而失败 → 退避后重试才装上」。现在提到应用启动，首次启动就能装。

**要等多久 / 下多少**（2026-10-04 在设备上实测）：

| 装什么 | 体积 | 按实测 708 KB/s |
|---|---|---|
| 隔离包自身 | 9.3MB | `adb install` 那一步 |
| 商店 6 件（当前清单） | **123MB** | 约 **3 分钟** |
| 加上 node（尚未上架） | 235MB | 约 5.7 分钟 |

各件实测体积：git 52MB、pnpm 45MB、npm 18MB、curl 6.3MB、sqlite3 1.7MB、jq 0.9MB。
（清单里没有 `size` 字段，所以装机前无法从清单知道总量 —— 这些数字是从设备上
已装的同名件量的。）

708 KB/s 是实测 6.6MB 的 curl 件（4.2 秒）得出的，**同一网络下 `raw.githubusercontent.com`
是 1.3 KB/s 且 5 次里 4 次超时** —— 所以分发源只能是七牛，不能是 GitHub。

供给在**后台线程**，UI 先起来是正常的；别因为界面还没东西就判失败。

## 4. 查供给链结果

新代码把每一步都写进 `RuntimeDiagnostics`，落在隔离包自己的私有目录
（开发包那份在 `/data/user/0/lobos.app/files/os/diag.jsonl`，格式是 JSONL，
每行 `{at, stage, level, message, detail}`）。

```bash
adb shell cat /data/user/0/lobos.app.verify/files/os/diag.jsonl | grep supply
```

**成功标志**：

| 看什么 | 在哪查 | 期望 |
|---|---|---|
| `stage=supply` 且 `level=OK` | `diag.jsonl` | 商店供给对账：声明 6 件，全部按真名可用 |
| 同一 stage 的 `level=FAIL` 行 | `diag.jsonl` | **不应有**；有的话 `message`/`detail` 写了缺件原因 |
| `stage=prefix` | `diag.jsonl` | `$PREFIX 能力件全就位` |
| `stage=version` | `diag.jsonl` | `Node 运行时缺失`（**预期** —— node 不在清单里，见第 6 条） |
| `目录已刷新（签名校验通过）` | **`events.jsonl`** | `channel=canary 包=6` |

**注意有两个日志文件，别只查一个**（这是两套机制，不是我写错）：

```bash
# RuntimeDiagnostics：结构性诊断（JSONL，字段 stage/level/message/detail）
adb shell cat /data/user/0/lobos.app.verify/files/os/diag.jsonl | grep -E 'supply|prefix|version'

# Journal：事件流水（JSONL，字段 seq/at/category/detail）
# 目录刷新成功走这里 —— 新代码才有，老 APK 的 journal 里查不到（它那条路不写）
adb shell cat /data/user/0/lobos.app.verify/files/os/journal/events.jsonl | grep catalog
```

对照样本（设备上开发包跑出来的真实一行，格式完全相同，只是文案是旧版的
"C 层供给"；新代码同一处写的是"商店供给"）：

```json
{"at":1791042370975,"stage":"supply","level":"OK",
 "message":"C 层供给对账：声明 6 件，全部按真名可用",
 "detail":"https://hubcdn.zll.ink/userland-canary"}
```

若 `run-as` 不可用，就在应用内的诊断面板看同样的内容。

**为什么不用 `run-as`**：2026-10-04 在那台设备上实测 `run-as lobos.app ...` 报
`Permission denied`（shell 上下文受限）。但那个 shell 的 uid 就是 `u0_a46`，
**与 app 相同**，所以直接 `cat` 绝对路径即可 —— 上面几条命令就是这么写的，
且在设备上实测读出了内容（`run-as` 那次是真失败，别照抄）。

若你的 shell 与 app 不同 uid，才需要回应用内的诊断面板。

想看全部阶段：

```bash
adb shell cat /data/user/0/lobos.app.verify/files/os/diag.jsonl | tail -30
adb shell cat /data/user/0/lobos.app.verify/files/os/journal/events.jsonl | tail -10
```

## 5. 确认 6 件真能用（走商店通道装的，不是手工摆的）

```bash
adb shell run-as lobos.app.verify ls -l files/usr/bin/
```

**成功标志**：`curl` `git` `jq` `npm` `npx` `pnpm` `sqlite3` 都是**符号链接**，
指向上面那行显示的 `files/usr/lib/toolchain/<件名>/bin/...`。
这一步证明「CDN → 校验 → 解包 → 落位 → 建链」整条链在真机成立。

## 6. 关于 node：预期是「缺失」

清单（线上 revision 6）是 6 件，**不含 node** —— node 还没发到 CDN（卡凭据）。
所以隔离包里 `node` 相关诊断应当是「未就位」，程序启动会按退避重试。
这不是缺陷，是当前投递状态的如实反映。

**要让隔离包自动装上 node**，需要先把 node 件发到商店（要 `QINIU_*` + `OTA_PRIVATE_KEY_PEM`）。
在那之前，node 只能像现在这样在开发包上用手工步骤验证 —— 那验的是「件本身可用」，
不是「新代码的供给链能装它」。

## 7. 收尾（可选）

```bash
adb uninstall lobos.app.verify
```

开发包 `lobos.app` 全程未被触碰，卸载隔离包也不影响它。

---

## 判据汇总

| 步骤 | 验的是 | 通过标志 |
|---|---|---|
| 1–2 | 隔离机制本身 | 两个 applicationId 并存 |
| 3–4 | **新代码的 `SupplyProvisioner.ensure` 在真机成立** | 对账平、全部件可用、无缺件 |
| 5 | 商店通道端到端 | 真名全是符号链接，指向 toolchain 下的件 |
| 6 | node 的如实反映 | 正式商店模式下说「未就位」；自签模式下**装出来且能跑** |

**第 3–4 步是这次唯一真正要验的东西。** 之前所有验证都没覆盖它。

## 预期输出（照这个对照判成败）

**自签模式**（`SELF-SIGNED-VERIFY.md` 那套，包指向本机供给）：

```
① supply 对账
   stage=supply  level=OK
   message=商店供给对账：声明 7 件，全部按真名可用
   detail=http://127.0.0.1:8120/userland-canary

② 装出来的件（7 个，含 node）
   curl git jq node npm pnpm sqlite3

③ node 最终判据
   /data/user/0/lobos.app.verify/files/usr/bin/node -v
   → v24.21.0
```

**正式商店模式**（`channel.json` 指向 `hubcdn.zll.ink`）：

```
① supply 对账
   message=商店供给对账：声明 6 件，全部按真名可用
② 装出来的件：curl git jq npm pnpm sqlite3（不含 node —— 清单里没有）
③ node：诊断说「Node 运行时未就位」；usr/bin/node 指向 APK 的 libnode.so
   （正式凭据到位并发布 userland-canary-7 之后，这里会变成 7 件 + node 从商店装）
```

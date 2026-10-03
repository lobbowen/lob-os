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
adb shell am start -n lobos.app.verify/lobos.MainActivity
```

新代码的 `BootReconciler` 会在后台线程里跑
`CatalogClient.refresh` → `SupplyProvisioner.ensure`。**这一步是本次要验的核心**：
它是那 400 行 `ensure()` 第一次在真机上执行。

等 1~2 分钟（6 件要下载）。

## 4. 查供给链结果

新代码把每一步都写进 `RuntimeDiagnostics`，落在隔离包自己的私有目录
（开发包那份在 `/data/user/0/lobos.app/files/os/diag.jsonl`，格式是 JSONL，
每行 `{at, stage, level, message, detail}`）。

```bash
adb shell run-as lobos.app.verify cat files/os/diag.jsonl | grep supply
```

**成功标志**：

| 看什么 | 期望 |
|---|---|
| `stage=supply` 且 `level=OK` | 商店清单验签通过、声明 6 件全部按真名可用（对账平） |
| 同一 stage 的 `level=FAIL` 行 | **不应有**；有的话 `message`/`detail` 里写了缺件原因 |
| `stage=prefix` | `$PREFIX 能力件全就位` |
| `stage=version` | `Node 运行时缺失`（**预期** —— node 不在清单里，见第 6 条） |
| `stage=catalog` | 目录刷新成功，带 `包=6` |

对照样本（设备上开发包跑出来的真实一行，格式完全相同，只是文案是旧版的
"C 层供给"；新代码同一处写的是"商店供给"）：

```json
{"at":1791042370975,"stage":"supply","level":"OK",
 "message":"C 层供给对账：声明 6 件，全部按真名可用",
 "detail":"https://hubcdn.zll.ink/userland-canary"}
```

若 `run-as` 不可用，就在应用内的诊断面板看同样的内容。

想看全部阶段：

```bash
adb shell run-as lobos.app.verify cat files/os/diag.jsonl | tail -30
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
| 3–4 | **新代码的 `SupplyProvisioner.ensure` 在真机成立** | 对账平、6 件可用、无缺件 |
| 5 | 商店通道端到端 | 7 个真名全是符号链接，指向 toolchain 下的件 |
| 6 | node 尚未投递的如实反映 | 诊断说「未就位」而不是含糊过去 |

**第 3–4 步是这次唯一真正要验的东西。** 之前所有验证都没覆盖它。

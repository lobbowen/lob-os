# 自签供给验证：怎么跑

**目的**：在没有正式 CDN 凭据的情况下，让**新代码在真机上自己把 node 装出来** ——
验签、sha256、解包、原子落位、建真名链、对账整条路都跑，含 node。

**它替代不了正式发布**：验的是「除真实签名身份外的一切」。公钥换成了测试那把，
包也只能装在能访问那个 `baseUrl` 的设备上。正式凭据到位后仍要照常发一轮
（见 `PUBLISH.md`）。

---

## 三步

### 1. 让 CI 产出「包 + 已签清单 + 7 个件」

手动触发 `Build Isolated APK`，`self_signed` 填**设备能访问到**的供给基址。
接本机自测就填 `http://127.0.0.1:8120`（设备要能访问到这个地址）。

那一轮会：取 7 个件的 artifact → 生成 ed25519 自签密钥对 → 签一份含 node 的清单
→ 把包里的信任根与 `channel.json.baseUrl` 换成自签那套 → 编出 APK。

产出两个 artifact：

| artifact | 内容 |
|---|---|
| `apk-isolated-<app_id>` | 装到设备上的包 |
| `self-signed-supply-bundle` | `dist/`（7 个件）+ `release/`（清单与签名） |

### 2. 在设备可达的那台机器上供起来

**先改清单文件名**。bundle 里的是 `userland-manifest.json`（发布器的输出名），而
设备的 `channel.json` 声明的是 `manifestName: userland-manifest-2.json` ——
**名字对不上，设备会 404**：

```bash
cd <bundle>/dist
cp userland-manifest.json       userland-manifest-2.json
cp userland-manifest.json.sig   userland-manifest-2.json.sig
```

然后起服务：

```bash
node tools/serve-supply.js <bundle>/dist 8120 --channel canary \
  --presigned <bundle>/dist/userland-manifest-2.json
```

`--presigned` 是关键：直接供那份**已签好的**字节（连 `.sig` 一起）。不带这个参数时
服务会自己造一份**没签**的清单，设备侧必然因验签不过拒装 —— 那时它验的只有
「除验签外的一切」。

启动后核对这几行（**要看内容，别只看 URL 通不通**）：

```
[serve] 用已签好的清单: .../userland-manifest-2.json
[serve]   revision=1 tools=7 baseUrl=http://127.0.0.1:8120
[serve] 监听 http://127.0.0.1:8120
```

清单的 `baseUrl` 必须与 `--presigned` 清单里写的一致 —— 那是设备实际会去拉的地址。

让它活过你的 shell（否则会话一收服务就没了）：

```bash
setsid nohup node tools/serve-supply.js <bundle>/dist 8120 --channel canary \
  --presigned <bundle>/dist/userland-manifest-2.json < /dev/null > /tmp/serve.log 2>&1 &
ps -ef | grep serve-supply        # ppid 应为 1 = 已脱离
```

### 3. 装 + 启动

```bash
adb install -r <artifact 解出的>/app-debug.apk
adb shell monkey -p lobos.app.verify -c android.intent.category.LAUNCHER 1
```

约 88MB（7 件），按实测 708 KB/s 约 2 分钟。供给在后台线程，UI 先起来是正常的。

验什么、怎么看：`VERIFY-ON-DEVICE.md`（第 4 步的命令适用于本模式）。

---

## 判据

| 验到 | 没验到 |
|---|---|
| 设备侧供给链的完整代码路径 | 真实签名身份（公钥是测试那把） |
| Ed25519 验签**算法本身**被真实执行 | 真商店上那 6 件的可达性 |
| 逐件 sha256、解包、原子落位、建真名链、对账 | 产出的包可分发性（它只指向那个 baseUrl） |
| **node 被当普通件装出来**并被程序依赖 | — |

## 排障

**装完诊断里 `supply` 是 FAIL**
- 看 `detail`：清单过期 / 验签不过 / sha256 不符 / 缺入口，四种原因文案不同
- 验签不过 ⇒ 多数是包里信任根与供的清单不是同一把（`--presigned` 指错文件，
  或服务供的是自造清单）
- 清单取不到 ⇒ `baseUrl` 与服务实际监听地址/端口不一致

**诊断读不到**
`run-as` 在受限 shell 上会 Permission denied（实测）。改用直接读 —— shell 与 app 同 uid 时 `cat /data/user/0/<包名>/files/os/diag.jsonl` 即可（见 VERIFY-ON-DEVICE.md）；同 uid 不成立时才需要回应用内的诊断面板。

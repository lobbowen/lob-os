# OTA 序列号按通道+程序隔离

## 现象（真机报错）

```
15:00:35 结果: manifest sequence=32 不高于已提交 32 —— 疑似重放，拒绝
15:00:35 install-bac28697 done 100%
15:00:34 os.appmgr.install → ["ok":true, "accepted":true, "taskId":"install-bac28697"]
15:00:34 调 os.appmgr.install id=com.lobos.second
```

任务被接受、跑到 100%，然后被拒。

## 根因

`OtaPolicy` 对 sequence 的判重是 `lastSequence >= sequence` 即拒。

而 `lastSequence` 原先存在 `filesDir/program-feed-state.json` —— **整个设备只有一份**。

于是：

| 通道 | 清单 sequence | 结果 |
|---|---|---|
| `canary`（`com.lobos.fixture`） | 32 | 装成功，推进 `lastSequence = 32` |
| `second`（`com.lobos.second`） | 31 | `31 <= 32` → 判重放，**拒绝** |

两个通道互相压制：谁先推进，另一个通道的包就再也装不上。哪怕那个包对这个设备是全新的。

## 为什么这不是配置问题

通道只决定"发现新版本时去哪儿找"，**与已装程序解耦**。但序列号判重是跨通道生效的，
所以"切到 second 通道装第二个快应用"这条路在单通道设计下走不通。

单通道本身不是缺陷（一个通道一个程序是合理的发布模型），
**缺陷在于序列号判重没有跟着通道隔离**。

## 修法

`ProgramOtaUpdater` 的状态读写全部加「通道 + 程序」维度：

```
files/program-feed-state-<channel>-<programId>.json   lastSequence / pendingSequence
files/program-feed-install.json                        installId（设备级，不拆）
```

### installId 为什么不拆

`installId` 只用于灰度分桶（`OtaPolicy` 的 `bucketOf(installId, version)`）。
灰度是**设备维度**的：同一台设备在所有通道上必须命中同一分桶，
拆了会导致同一设备在不同通道上被当成不同设备。

### 向后兼容

旧版写的是全局 `program-feed-state.json`。`loadState` 在分维度文件不存在时：

1. 读旧全局文件
2. 把里面的 `lastSequence` / `pendingSequence` 搬进分维度文件
3. 之后不再读旧文件

不这样做的话，设备上已推进到 32 的序列号会被当成 0，等于放过重放。

## 受影响的调用点

| 位置 | 改动 |
|---|---|
| `ProgramOtaUpdater.checkAndUpdateNet` | `loadState` / `saveState` 传 `(cfg, km.programId)` |
| `ProgramOtaUpdater.promotePendingSequence` | 签名加 `(cfg, programId)` |
| `ProgramOtaUpdater.dropPendingSequence` | 签名加 `(cfg, programId)` |
| `InstanceHost.commitPendingKernel` | 调 `loadConfig` 后传 `(cfg, programId)` |
| `InstanceHost.rollbackIfPendingFailed` | 同上 |
| `ProgramOtaSelfCheck` | 读分维度文件；`installId` 改读 `program-feed-install.json` |

## 门禁

`tools/check-ota-sequence-scope.js` 钉死七条：

1. `stateFile` 必须有 `(cfg, programId)` 维度
2. 文件名必须由「通道 + 程序」拼出
3. `loadState` / `saveState` 同样带维度，且无裸调用残留
4. `installId` 固定在 `program-feed-install.json`，不按通道拆
5. 旧全局文件有回落读取
6. 健康提交/回滚带维度，调用方传 `(cfg, programId)`
7. 自检读分维度文件

验证方式：把文件名改回共享的 `"shared"`，门禁 FAIL（报 1 处）；
改回正确写法后 PASS。不是只看门禁跑得过。

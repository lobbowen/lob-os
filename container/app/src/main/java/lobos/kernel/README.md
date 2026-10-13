# kernel —— 内核

把 Android 给的机制，翻译成快应用可以依赖的抽象。

内核**只回答「能不能」与「是什么」**，不回答「要不要」与「用哪个」。

## 内核里不许出现的词汇

`program` `catalog` `ota` `piece` `permission` `manifest` `quickapp` `install`

这些是业务词。内核不认识它们，也不该认识。

内核只提供**形状**——比如「可执行文件住在 `usr/lib/<id>/<版本>/bin/`，
全局入口软链到 `usr/bin/`」。至于「有哪些件、什么版本、要不要更新」，
那是 services 的事。

## 这条规则的适用边界

指的是**内核的类型名、字段名、接口名**不带业务味。

**不指磁盘上的契约文件名。** 那些由打包方定义，是跨层接口，不受此限。

例：`program-manifest.json` 是件包内的清单文件名，
内核读它、services 写它、api 也读它——它是跨层契约，
改名要重打所有已发布的包，收益只是让一行 grep 好看。

判据是「这一层要不要回答这个问题」，不是「这个词出现在哪个目录」。
systemd 也是如此：内核有 module，userspace 有 package manager。

## 分层

| 目录 | 回答 |
|---|---|
| `proc/` `ipc/` `fs/` `elf/` `mm/` `security/` `time/` | 内核机制 |
| `compat/` | 内核的一部分（垫片），不是件 |
| `layout/` | 包与链接的形状（dpkg + ldconfig 的翻译） |
| `crypto/` | 加密原语与包签名校验 |

不在内核的：

- **设备授权与能力** → `device/` 层
- **电源策略与保活** → `services/power/`
- **决策（装不装、跑不跑、用哪个）** → `services/`

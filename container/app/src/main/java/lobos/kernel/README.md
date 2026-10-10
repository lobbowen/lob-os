# kernel —— 内核

把 Android 给的机制，翻译成快应用可以依赖的抽象。

内核**只回答「能不能」与「是什么」**，不回答「要不要」与「用哪个」。

## 内核里不许出现的词汇

`program` `catalog` `ota` `piece` `permission` `manifest` `quickapp` `install` `package` `tier`

这些是业务词。内核不认识它们，也不该认识。

内核只提供**形状**——比如「可执行文件住在 `usr/lib/<id>/<版本>/bin/`，
全局入口软链到 `usr/bin/`」。至于「有哪些件、什么版本、要不要更新」，
是 `services/` 的事。

## 目录

| 目录 | 对应 Linux | 职责 |
|---|---|---|
`fs/` | `fs/` | 命名空间（`$PREFIX`）、原子写、符号链接、临时空间 |
`proc/` | `sched/` `signal/` | 进程身份、进程树、创建、终止、**进程组** |
`mm/` | `mm/` | 库搜索路径、依赖可满足性、`LD_PRELOAD` 注入点 |
`ipc/` | `ipc/` | unix socket、PTY、帧协议、stdin/stdout |
`power/` | `power/` | 前台保活、Doze 兜底、冻结判定 |
`security/` | `security/` | 路径完整性、能力边界 |
`time/` | `timer/` | 心跳、超时判定 |
`crypto/` | `crypto/` | 验签、摘要 |
`elf/` | *（Linux 无）* | ELF 读取、执行位、依赖闭包 —— Android 场景特有 |
`device/` | *（Linux 无）* | 授权实测、随机数、时钟、空设备 |
`layout/` | *（Linux 无）* | 落位形状的唯一出处 |

`elf/` `device/` `layout/` 三个 Linux 没有，但 Android 场景确实需要：
ELF 装载是 Android 的现实、设备授权必须问 Android、落位形状是我们自定的约定。
思路对齐 Linux 的 `arch/`——处理本场景特有的差异。

## 依赖方向

```
api/  →  services/  →  kernel/
```

**只能向下。** `kernel/` 不 import `services/` 或 `api/`。

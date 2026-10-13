# device —— 设备授权与能力

系统与设备之间的授权边界。

不是内核机制（内核不问「能不能读屏」），不是业务（不是某个程序的能力），
也不是策略（「要不要读屏」是决策，属于 services）。

Linux 上对应的东西：capabilities、LSM 策略、设备节点的属主与 mode。
我们把它显式化成了「一张能力清单 + 一组授权条目」。

## 这一层回答三件事

1. **能做什么** —— 设备能力清单，可枚举
2. **允许什么** —— 授权状态：谁给了、什么时候、能否撤销
3. **拿不到会怎样** —— 明确的失败原因。「未授权」≠「失败」≠「设备不支持」

## 边界规则

**这一层不许出现**

- 业务词：`program` `piece` `quickapp` `catalog` `manifest`
- 决策：「要不要」「该不该」—— 那是 services
- 对外能力名：`ui.screenshot` 这类 api 名 —— 那在 api/
- 存储约定：截图存哪个目录 —— 谁用谁定

**这一层必须有**

- 每个能力：能不能、怎么用、失败原因
- 每项授权：从哪来、状态、能否撤销

**判断一个东西该不该进这一层**

它回答的是「这个设备允许我们做什么」吗？
是 → device。不是 → kernel（机制）/ services（决策）/ api（对外）

## 现有内容

| 文件 | 回答 |
|---|---|
| `PermissionCenter.kt` | 权限清单与就绪探测 |
| `DeviceOwnerProbe.kt` | 设备身份（属主？可安装？） |
| `ScreenCaptureController.kt` | 读屏：能不能、怎么读一帧 |
| `ScreenCaptureService.kt` | 读屏授权的弹框流程 |
| `OsAccessibilityService.kt` | 无障碍事件接入 |
| `AccessibilityServiceState.kt` | 无障碍是否连着 |
| `OsNotificationListenerService.kt` | 通知接入 |
| `NotificationStore.kt` | 通知缓存 |

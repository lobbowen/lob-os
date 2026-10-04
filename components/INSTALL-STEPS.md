# 装隔离包并启动（3 步，全用手指操作）

包已经在设备上：`/sdcard/Download/lobos-isolated-verify.apk`（9,748,039 字节）
它的应用名是 **「Lobos 验证」**（与开发包「Lobos」一眼可分）。

## 1. 装

设备上「文件管理」→ Download → 点 `lobos-isolated-verify.apk` → 系统安装界面 → 「安装」

（可能提示「允许安装未知应用」—— 允许「文件管理」即可。这与当初装开发包是同一件事。）

## 2. 启

**点桌面上新出现的「Lobos 验证」图标。**

不要用 `am start` / `monkey` —— 在这台设备上它们会被拒或静默失败
（实测：`am start` 报 `SecurityException: package=com.android.shell does not
belong to uid=10046`；`monkey` 无输出）。那是 shell 身份的限制，
**手指点图标走的是 Launcher 的正常路径，没有这个问题**。

## 3. 验

装完点开、等 1~2 分钟（88MB 走 loopback，瓶颈是解包落位），然后跑：

```bash
node /data/user/0/lobos.app/files/work/lob-os/components/verify-baseline.js
```

## 判据（6 条，在 components/VERIFY-PREFLIGHT.txt）

| # | 看什么 | 期望 |
|---|---|---|
| 1 | `pm list packages \| grep lobos` | 两行：`lobos.app` 与 `lobos.app.verify` |
| 2 | 诊断 supply 那条 | `level=OK`，「商店供给对账：声明 7 件，全部按真名可用」 |
| 3 | toolchain 下的目录 | 7 个（含 `node`） |
| 4 | `usr/bin/node -v` | `v24.21.0` |
| 5 | **`usr/bin/node` 链指向** | `…/lobos.app.verify/files/usr/lib/toolchain/node/bin/node` |
| 6 | 能力探针 7 条 | 全 `[ok]` |

**第 5 条最关键**：若它指向 `/data/app/.../lib/arm64/libnode.so`，说明 node 是从
APK 兜底来的 —— node 照样能跑（那是设计的三档兜底），但**不是这次要验的那件事**。

## 装不上 / 起不来时

- **装不上**：可能没给「文件管理」安装未知应用的权限
- **起来了但一件没装**：看第 2 条的 detail —— 清单取不到 / 验签不过 / sha 不符 /
  缺入口，四种文案不同，照着文案对
- **node 能跑但链指错地方**：即第 5 条，说明供给链没装成 node

# 供给链本地演练

CDN 投放要七牛凭据，但「设备能不能把件装起来」这件事不必等投放就能验。
这两个工具把商店通道的**除验签外每一环**在本地跑一遍：

```
tools/serve-supply.js   把 dist/ 的件按通道形态供出去（清单 + 件）
tools/rehearse-supply.js 取清单 → 逐件 sha256 → 解包 → 落位 → 建真名链 → 复验
```

## 用法

```bash
# 1. 起供给（需要一个装了 userland-*.zip 的 dist/）
node tools/serve-supply.js dist 8099 --channel canary

# 2. 另一个 shell 里演练安装
node tools/rehearse-supply.js \
  http://127.0.0.1:8099/userland-canary/userland-manifest-2.json \
  "$PWD/rehearse/lib/toolchain"
```

落点要用 `<prefix>/lib/toolchain` 形态 —— 真名入口是它的兄弟 `<prefix>/bin`，
与设备侧 `PrefixProvisioner` 的关系一致。

演练完可以直接验件真的能用：

```bash
rehearse/bin/jq -c '.a' <<< '{"a":42}'    # → 42
```

## 验到了什么，没验什么

**验到**（都实测过）：

| 环节 | 怎么验的 |
|---|---|
| 清单形状 | 件名/版本/入口/别名从 `scripts/userland-verify.json` 投影，缺声明即拒供 |
| 内容寻址自洽 | 件名里的 sha12 与实际 sha256 前 12 位不符即拒供 |
| 逐件校验 | sha256 不符不落位，点名到件 |
| 解包 | 目录穿越（`..` 越出落点）直接抛 |
| 落位 | 暂存 → 原子 rename；`.name.ok` 记件包 sha |
| 建真名链 | `$prefix/bin/<name>` 指向件内入口；别名各自建链 |
| 幂等 | 第二次跑命中 `.ok`、复验农场与真名，不重下 |
| 落位被改动 | 记 `.name.entry.sha256`，复验时对不上即判红并说清怎么修 |
| 对账 | 只认「声明数 == 可用数」，逐件点名缺因 |

**没验**（验不了，别当端到端）：

- **ed25519 验签** —— 演练器不签清单（没有私钥），设备侧会因验签不过拒装。
  验签本身由两处覆盖：CI 的 `publish-userland-manifest.js` 用 APK 焊死的公钥自检，
  以及 `components/userland/PUBLISH.md` 里那条不依赖私钥的自检命令。
- **Android 的 `Os.symlink`** —— 演练用 `fs.symlinkSync`，语义相同但走的不是系统调用。
- **Android 的 `ContentResolver` / `assets`** —— 演练器不读 APK 资产，信任根那一步被跳过。

所以：**演练绿 = 件本身可装**，不等于「装到真机上一定成功」。真机那一步仍要
装 APK 后实测。

## 与设备侧实现的关系

演练器重演的是 `SupplyProvisioner.ensure` 的判据（不是调用它 —— 那需要
`Context` 与 APK 资产，本机拉不起 App 进程）。有一处它比设备侧**更严**：

设备侧 `ensure` 命中 `.ok` marker 后只复验「农场可解析 + 真名链存在」，
不校验落位文件的内容 —— 件被改坏不会触发重装。演练器额外记
`.name.entry.sha256` 并复验，为的是**发布前**就能发现这类问题。

这不是设备侧的缺陷（marker 命中即跳过的取舍在 dsh-mobile 就有，理由是
「件按 sha 命中就永不重解，重建要靠删 `.name.ok`」），但发布前的检查器
应该比生产严一档。

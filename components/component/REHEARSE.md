# 供给链本地演练

CDN 投放要七牛凭据，但「设备能不能把件装起来」这件事不必等投放就能验。
这两个工具把商店通道的**除验签外每一环**在本地跑一遍：

```
tools/serve-supply.js   把 dist/ 的件按通道形态供出去（清单 + 件）
tools/rehearse-supply.js 取清单 → 逐件 sha256 → 解包 → 落位 → 建真名链 → 复验
```

## 用法

```bash
# 1. 起供给（需要一个装了 component-*.zip 的 dist/）
node tools/serve-supply.js dist 8099 --channel canary

# 2. 另一个 shell 里演练安装
node tools/rehearse-supply.js \
  http://127.0.0.1:8099/component-canary/component-manifest-2.json \
  "$PWD/rehearse/lib/toolchain"
```

落点要用 `<prefix>/lib/toolchain` 形态 —— 真名入口是它的兄弟 `<prefix>/bin`，
与设备侧 `PrefixProvisioner` 的关系一致。

演练完可以直接验件真的能用：

```bash
rehearse/bin/jq -c '.a' <<< '{"a":42}'    # → 42
```

## 升级路径的验证（2026-10-04）

`SupplyProvisioner.ensure` 原本是「先删旧件再落新件」（`root.deleteRecursively()`
之后 `staging.renameTo(root)`）。**若 rename 失败，旧件已删、新件没落成 → 件消失**，
与「可回滚」直接冲突。这段是从 dsh-mobile 原样搬来的（那边也有）。

改成**先挪走旧的、落新的、成功才删旧的**：

```
root → .<name>.retired        挪不开就中止，保留原样
staging → root                失败则 retired → root 回退；回退也失败才记「不可用」
retired 删除
```

演练器同步改了（否则测不到真问题）。三条路径实测：

| 场景 | 结果 |
|---|---|
| 压缩流损坏（改了 zip 里的字节） | 判红「落位失败」，**旧件完好**：入口 sha 与 marker 均未变，`.retired` 已清 |
| 件里缺声明的入口 | 判红「缺入口 bin/jq」，**旧件完好**（marker 仍是旧 sha） |
| 版本号变但内容相同 | 命中 marker 跳过 —— **这是内容寻址的正确形态**，不是缺陷 |

**关于第三条**：marker 存的是清单里的 `sha256`，版本号不参与判定。所以
「8.22.0 → 8.22.1 但字节没变」不会重装。这不是漏判而是设计：内容寻址下
同内容即同身份，重装没有意义。版本号与内容是两个维度，`CatalogClient.list`
同时给出 `installedSha` / `installedVersion` / `upgradable`，UI 两个都能看到。

顺带一条实测结论：**构建可复现**。两次不同 CI run 产出的
`component-curl-8.22.0-*.zip` sha 完全相同（`7aa794caebc5`）—— 这正是
`component-sources.json` 钉 `buildTimeEpoch` 与 `ndkVersion` 的目的
（openssl 会把构建时刻写进件字节；墙钟进字节则同版本每次重建 sha 全变，
内容寻址的键会堆积、设备全体重下）。

## 验到了什么，没验什么

**验到**（都实测过）：

| 环节 | 怎么验的 |
|---|---|
| 清单形状 | 件名/版本/入口/别名从 `scripts/component-verify.json` 投影，缺声明即拒供 |
| 内容寻址自洽 | 件名里的 sha12 与实际 sha256 前 12 位不符即拒供 |
| 逐件校验 | sha256 不符不落位，点名到件 |
| 解包 | 目录穿越（`..` 越出落点）直接抛 |
| 落位 | 暂存 → 原子 rename；`.name.ok` 记件包 sha |
| 建真名链 | `$prefix/bin/<name>` 指向件内入口；别名各自建链 |
| 幂等 | 第二次跑命中 `.ok`、复验农场与真名，不重下 |
| 落位被改动 | 记 `.name.entry.sha256`，复验时对不上即判红并说清怎么修 |
| 对账 | 只认「声明数 == 可用数」，逐件点名缺因 |
| 清单期限 | 缺 `expiresEpochMs` 即拒；已过期即拒（两条都实测：造过期清单 → 拒装 exit 1；置 0 → 拒 exit 1） |

**没验**（验不了，别当端到端）：

- **ed25519 验签** —— 演练器不签清单（没有私钥），设备侧会因验签不过拒装。
  验签本身由两处覆盖：CI 的 `publish-component-manifest.js` 用 APK 焊死的公钥自检，
  以及 `components/component/PUBLISH.md` 里那条不依赖私钥的自检命令。
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

## 2026-10-04 全量演练结果（7 件）

用 CI run `31bbe42d` 产出的 7 个件包（artifact 逐个下回本地）跑通：

```
curl 装成：6612408 字节入口=bin/curl sha=c422e0ee944f
git 装成：3886584 字节入口=bin/git sha=0b07fc35d21e
jq 装成：952104 字节入口=bin/jq sha=d36e002528ae
node 装成：116846080 字节入口=bin/node sha=e94c5669bc91
npm 装成：54 字节入口=bin/npm-cli.js sha=8e5f6f3429f8
pnpm 装成：47033992 字节入口=bin/pnpm sha=ce0b5e064552
sqlite3 装成：1802680 字节入口=bin/sqlite3 sha=fbf811b22003
对账：声明 7 件，可用 7 件 —— 平
```

**node 那一行是关键**：116,846,080 字节、sha `e94c5669bc91…`，与 dsh-mobile
Release 里的 `libnode.so` 逐字节相同 —— 同一批编译产物，从 CI 到落位全程可追溯。

装完按裸名真跑（不是只看文件在）：

| 件 | 判据 | 结果 |
|---|---|---|
| node | `node -v` | v24.21.0 |
| jq | `jq --version` | 1.8.2 |
| curl | `curl --version` | 8.22.0 (aarch64-android, OpenSSL 3.6.3, zlib 1.3.2) |
| sqlite3 | `sqlite3 :memory: 'select 1+1;'` | 2 |
| git | init → add → commit → log | cd681e2 first |
| npm | `npm --version`（靠 PATH 上的 node） | 11.19.0 |
| npx | 同上（npm 件的别名链） | 11.19.0 |
| pnpm | `pnpm --version` | 12.7.0 |

**这回答了「node 能不能作为商店件」**：能。装在应用私有目录、按裸名调得到、
真跑出功能，且升级只换件不动 APK。

## 2026-10-04 真机验证：node 作为商店件在设备上可用

演练器是在桌面 shell 上重演判据；这一次是**在真机的应用私有目录里按
SupplyProvisioner.ensure 的步骤真装**（校验 sha → 暂存 → 原子 rename → 写
`.node.ok` → 建 `usr/bin/node` 链）：

```
件包 component-node-24.21.0-75695f75a08d-android-arm64.zip
  实际 sha256 75695f75a08d298d6fa1c82d045f08af7ec5c136de364cbc9ad3deee073ee0b8（与包名一致）
落位 usr/lib/toolchain/node/bin/node  116,846,080 字节
marker .node.ok
真名链 usr/bin/node -> usr/lib/toolchain/node/bin/node
```

跑出来的（正是 component-verify.json 里 node 那条判据）：

| 判据 | 结果 |
|---|---|
| `node -v` | v24.21.0 |
| 真跑一段 JS（6*7） | 42 |
| 起 http 服务 bind 随机端口 | port 41615 |
| 自算自身 sha256 | e94c5669bc91… |

最后一条最硬：它读自己的字节算出的哈希，与 dsh-mobile Release 里的
`libnode.so` **逐字节相同** —— 同一批编译产物，从 CI 到真机全程可追溯。

装完 7 件经 `usr/bin/<名>` 全通：

```
node      v24.21.0
curl      curl 8.22.0 (aarch64-unknown-linux-android) libcurl/8.22.0
jq        jq-1.8.2
git       git version 2.55.0
npm       11.19.0
pnpm       12.7.0
sqlite3   3.53.4
```

顺带证实了三件事：

1. **真机上的通道布局与演练器造的逐字一致** —— 6 件在
   `usr/lib/toolchain/<name>/`、各带 `.<name>.ok`（64 字节 = 件包 sha256）、
   `usr/bin/<名>` 是指向件内入口的符号链接。演练器的模型是对的。
2. **marker 存的是件包 sha256** —— 设备上 `.curl.ok` 的内容
   `acbbb26198e9…` 与清单里 curl 条目的 sha256 逐字一致，
   证实 `marker.readText().trim() == want` 的语义正确。
3. **node 未上架时行为不变** —— 改链前 `usr/bin/node` 指向 APK 的
   `libnode.so`（第三档兜底），商店件装好后改指商店件（第二档），
   两种状态下 `node -v` 都是 v24.21.0。

## 2026-10-04 端到端：商店件 node 启动了真机上已装的程序

真机上装着一个真程序 `console`（5 个版本，`CURRENT` 指向
`0.1.0-android.53-launcher`），它的清单声明：

```
"name": "console",
"abi": "node24-arm64-android35",
"engines": { "node": ">=24 <25" },
"entry": "bin/panel"
```

用**商店件那份 node**（`usr/lib/toolchain/node/bin/node`，不是 APK 里那份）
跑它的入口：

```
[launcher] 启动页监听失败: EADDRINUSE listen EADDRINUSE: address already in use 127.0.0.1:36360
```

报端口被占用，说明它真的起来了、真的去绑端口 —— 而那个端口上已经有一个实例
在跑（真机上的 App 正在服务）。于是这条链完整走了一遍：

```
商店件 node（37MB zip → 校验 sha → 落位 → 建链）
  → node -v = v24.21.0（engines 要求 >=24 <25 ✓）
  → spawn 第三方程序 console
  → 程序执行、尝试 listen、拿到真实 errno
```

**这是「node 作为商店件」的最终判据**：不只是 `node -v` 打出版本号，而是
它真能当别人的运行时用。之前那些验证（CI 产出件、演练器装件）验的是
「件能装能跑」；这一条验的是「装完的件能被程序依赖」。

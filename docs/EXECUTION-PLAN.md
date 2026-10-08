# 执行方案：收拢分发逻辑（2026-08-08）

依据：`docs/BASE-ENV-COMPLETE.md`（基础环境件清单，当前口径唯一依据）
本文只写「动什么、按什么顺序、每步的判据」，不重复定义件是什么。

**已确立的架构（不再讨论）**

1. **只有一条分发路径：OTA**。清单在控制面板侧，不在 APK 里。
2. **基础环境件**与程序、运行时、工具走同一套逻辑 —— 登记在同一张注册表，
   随 APK 首发内置，可 OTA 更新。**不存在「APK 原生件」这个独立类别。**
3. **三筐**：`base` 基础环境（服务全局的数据与能力）· `rt` 运行时 ·
   `tool` 工具（含编译工具链）。分类唯一真相是 `scripts/cache-key.sh` 的 `bucket_for`。
4. **无版本号的件不进 OTA 更新逻辑**，只随 APK 打包。这不是巧合（现在被
   `publish-native-manifest.js:117` 恰好滤掉），要写成明文规则。
5. **每件钉两个版本**：`LST`（长期稳定维护版，随 APK 首发）·
   `latest`（上游最新版，系统更新时用户可选）。`latest` 需先有文件才能填 sha256。

---

## 阶段 0：解除阻塞（APK 现在构建不出来）

| # | 问题 | 状态 |
|---|---|---|
| 0.1 | `build-base-libs.sh` 的 openssl：`$ORIGIN` 被 perl 吃掉，实测 RUNPATH=`RIGIN` | 已在 `check_lib` 加 `od -c` 字节级取证，**等 CI 日志给出 Configure 的真实输入** |

**阶段 0 不完成，后面全部无法验证**（第 1 层的验收要能出 APK）。

---

## 阶段 1：清理（删掉确认无用的，零风险）

| # | 删什么 | 依据 |
|---|---|---|
| 1.1 | `container/app/src/main/assets/supply/seed.json` | **无任何读者**；内容与共识矛盾（写着「运行时与工具一律经商店安装」） |
| 1.2 | `program-feed.json` 与其三处读者里的旧格式分支 | `ProgramOtaUpdater.parseConfig():71-77` 已优先读 `channel.json` 的 `manifests.program`，`program-feed.json` 只是回退。**但状态文件名要迁移**：`program-feed-state-*` → `ota-state-*`（先读旧名回退，不丢设备上的已装状态） |
| 1.3 | `NativeAssetRegistry.kt` 第 20 行的 `node` 条目 | 它自己的 note 写「APK 内不该有这份（build-apk.yml 有断言）」。node 的正路是 `ProgramIndex`（`build-node.yml` 已独立） |
| 1.4 | `gen-native-assets.js` 第 98 行的 `EXTRA_CAPS` | `node-pty`（`liblobospty.so`）硬编码在 JS 生成器里，既不在 Kotlin 注册表也不在任何产出清单中。应改为从注册表读 |

**门禁**：APK assets 只允许存在一份锚点；无读者的文件不许进仓。

---

## 阶段 2：版本号补齐（决定哪些件能进 OTA）

| # | 件 | 缺什么 | 补法 |
|---|---|---|---|
| 2.1 | `crypto` | `NativeAssetRegistry.kt:106` 无 `version` | = `openssl` 3.6.3（同一次编译产物）。补上则**立即可 OTA 更新** |
| 2.2 | `ripgrep` | 钉值表无 `ripgrep`/`rg` 项；`:43` 无 `version` | **已查清：版本 14.1.1**（`build-native-capabilities.sh:155`，`cargo install --version 14.1.1 ripgrep`）。⚠️ **它的获取方式与其他 15 件都不同** —— cargo 从 crates.io 装，**不走钉值表、不做 sha256 校验**。要么纳入钉值表管理，要么在文档里明确「cargo 装 = 例外」并说清代价 |
| 2.3 | `libcxx` | `:9` 无 `version` | = NDK 30.0.16248370 |
| 2.4 | 钉值表形状 | 只有 `version`，无 LST/latest 两版概念 | 改成 `{ lst: {version, sha256, urls}, latest: {...} }`，**只填 lst**；latest 留待有文件时补 |

**规则写进代码**（不是靠碰巧）：无 `version` 的件**明确标注 `bundled-only`**，
不进 OTA 清单 —— 现在这个行为是过滤器碰巧造成的，要变成显式声明。

**判据**：`node tools/check-base-versions.js` 判红 —— 每个基础环境件要么有
version（可 OTA），要么显式声明 bundled-only。

---

## 阶段 3：注册表落地（一张表，登记系统里所有可安装的东西）

**这一步不新建通道，是把已有的四份收敛成一份。**

现状四份口径（普查结果，只 6 件重合）：

| 口径 | 件数 | 问题 |
|---|---|---|
| ① `bucket_for` base 筐 | 6 | 与③只重合 6 件 |
| ② `NativeAssetRegistry.kt` | 13 | 含 node（rt 筐，不该在这） |
| ③ `native-capabilities.txt` | 12 | 从②生成，②错了跟着错 |
| ④ `supply/seed.json` | 7 | 已删（阶段 1.1） |

**注册表结构**：

```
schema
件清单: [ { id, kind, entry, landing, bundled, updatable, version, deps } ]
```

| 字段 | 判据 |
|---|---|
| `kind` | 程序 / 运行时 / 工具 / 库 |
| `bundled` | true = 随 APK 首发内置（**不是「另一类」**） |
| `updatable` | 能否 OTA 换版本（= 有 version） |
| `landing` | 落位规则 |

**门禁**：
- 每件只能登记一次（重份判红）
- 每件必须有 `entry` 或显式声明「无入口」
- 代码里不得再有硬编码能力清单：`BIN_IDS`/`LIB_IDS`（`NativeAssetRegistry.kt:124,129`）、
  `BUSYBOX_APPLETS`（`PrefixProvisioner.kt:14-17`，18 个软链名）必须清空
- `bundled: true` 的件必须在打包产物里真实存在

---

## 阶段 4：接上「首次安装」这段断路

`SupplyProvisioner` 11 个能力中，**只有 2 个真死**（阶段 1 已核实：
`versionDir`/`unzipInto` 实际有调用者，我此前判断有误）：

| 能力 | 状态 |
|---|---|
| `selectVersion` | ❌ 无调用者 |
| `selectedVersion` | ❌ 无调用者 |

这两个是「记录件当前选哪个版本」的读写。它与 `NativeAssetUpdater` 现在
「从软链路径段数反推版本」的做法重复 —— 后者是**推断**，前者是**记录**。

**动法**：统一到 `toolchain-selected.json`，`NativeAssetUpdater` 改读它。
不新增装件逻辑（`ProgramInstaller` 已有完整实现）。

---

## 阶段 5：拆「APK 原生件」这个类别

1. `NativeAssetRegistry.kt` 13 条按注册表重建
2. `PrefixProvisioner.provision()` 的「从 jniLibs 拷」改为按注册表的 `bundled` + `landing`，
   **首次内置仍从 APK 取源**（这是 `bundled: true` 的实现，不是新类别）
3. 门禁：代码里不再以 `nativeLibraryDir` 作为「能力来源」判断

**⚠ 这一步依赖阶段 2 与 3 都完成。** 先做会变成两套并存 —— 那正是当前混乱的成因。

---

## 依赖关系

```
阶段0（解除阻塞）→ 阶段1（清理）→ 阶段2（版本号）→ 阶段3（注册表）→ 阶段4（接线）→ 阶段5（拆类别）
```

**阶段 1 与 2 可并行**（一删一补，互不依赖）。
**阶段 5 必须最后做**，且 3、4 未完成前不许动。

---

## 当前阻塞与待确认

| # | 项 | 说明 |
|---|---|---|
| A | **APK 构建不出来** | 阶段 0。等 CI 日志给 openssl 的字节级取证 |
| B | ~~`ripgrep` 编的是哪个版本~~ **已查清** | 14.1.1，但走 `cargo install`（crates.io），**不走钉值表、不做 sha256 校验** —— 与其余 15 件的获取方式都不同。要么纳入钉值表，要么明确它是例外并写清代价 |
| C | **`jq` 与 `sysroot` 不在 `NativeAssetRegistry`** | 两件都是基础环境件，但走的是另外两套机制（jq 走 zip 独立链、sysroot 走 `linkSysrootInclude`）。阶段 3 要把它们收进注册表 |
| D | **`DISTRIBUTION-PLAN.md` 方向性错误** | 它第 4 层要「拆掉 APK 原生件类别」，与「基础环境件随 APK 首发内置」相反；它「消费方全改为读注册表」踩了 `BASE-ENV-DESIGN.md:139-149` 已否掉的方向（让安装器按清单办事 / 运行时探测）。**那篇要按本文重写或废弃** |

---

## 本方案不做的事

- 不新增分发通道
- 不在 APK 里放任何清单本体
- 不为「最小改动」妥协 —— 阶段 3 不落地，4、5 都建在流沙上
- 不删任何有读者的东西（每删一项都要先证明无读者）
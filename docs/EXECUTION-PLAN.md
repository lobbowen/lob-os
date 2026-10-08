# 执行方案：收拢分发逻辑

依据：`docs/BASE-ENV-COMPLETE.md`（基础环境件清单，当前口径唯一依据）
本文只写「动什么、按什么顺序、每步的判据」，不重复定义件是什么。

---

## 架构（已确立，不再讨论）

1. **只有一条分发路径：OTA**。清单在控制面板侧，不在 APK 里。
2. **基础环境件**与程序、运行时、工具走同一套逻辑 —— 登记在同一张注册表，
   随 APK 首发内置，可 OTA 更新。**不存在「APK 原生件」这个独立类别。**
3. **三筐**：`base`（服务全局的数据与能力）· `rt`（运行时）· `tool`（工具，含编译工具链）。
   分类唯一真相是 `scripts/cache-key.sh` 的 `bucket_for`。
4. **无版本号的件不进 OTA 更新逻辑**，只随 APK 打包。这要写成明文规则
   —— 现在是被 `publish-native-manifest.js:117` 碰巧滤掉的。
5. **每件钉两个版本**：`LST`（长期稳定维护版，随 APK 首发）·
   `latest`（上游最新版，系统更新时用户可选）。
   `latest` 需先有文件才能填 sha256（sha256 是逐字节校验的硬约束）。

---

## 已核实的事实（本方案的依据）

### 事实 1：组件清单**有**设备端消费者（此前判断有误）

`CatalogClient.kt:107` 读 `manifests.component`，经 `ProgramIndex` + `ProgramManager`
落位。`ProgramInstaller.kt:109` 用 `SupplyProvisioner.unzipInto` 解包。

**结论**：13 件（sysroot/make/cmake/pkg-config/jq/curl…）的安装路径是通的，
不是「发了没人读」。

### 事实 2：`SupplyProvisioner` 只有 2 个死函数（此前判断有误）

`versionDir` 被 `NativeAssetUpdater` 用、`unzipInto` 被 `ProgramInstaller` 用。
真死的是 `selectVersion` / `selectedVersion`（版本记录的读写）。

### 事实 3：11 条链的 Release 步有 tar glob bug

```bash
tar czf "$PW/$ASSET" -C dist --exclude '*.tar.gz' component-<件>-*.zip
```

`-C dist` 让 tar 切进 `dist/`，但 glob 是 **shell 在原目录展开**的 ——
展开出的是 `component-make-4.4.1-xxx.zip`（无 `dist/` 前缀），
tar 切目录后就找不到。实测复现：

```
ls dist/component-make-*.zip                              → 成功（走 if 分支）
tar czf ... -C dist --exclude '*.tar.gz' component-make-*.zip
                                    → tar: component-make-*.zip: Cannot stat
```

影响 11 条链：`cmake curl git jq make npm pkg-config pnpm python3 sqlite3 sysroot`
（`llvmtoolchain` 用另一写法，未中）。

**这是 `make`/`cmake`/`pkg-config` 三条链上一轮失败的直接原因** ——
它们因改筐后 tag 变了（`base-*`→`tool-*`）而重编，走到 Release 步就撞上这个 bug。

### 事实 4：并发缓存冲突

12 条链同时跑，缓存 key 相同时只有一条能存，其余报
`Unable to reserve cache ... another job may be creating this cache`。
这一条是 warning，不致命，但会让「存回编译中间产物」失效。

### 事实 5：`ripgrep` 走 cargo，不走钉值表

`build-native-capabilities.sh:155`：`cargo install --version 14.1.1 ripgrep`。
**不走 `component-sources.json`、不做 sha256 校验** —— 与其余 15 件都不同。

### 事实 6：四份口径仍并存

| 口径 | 件数 | 问题 |
|---|---|---|
| ① `bucket_for` base 筐 | 6 | 与③只重合 6 件 |
| ② `NativeAssetRegistry.kt` | 13 | 含 node（rt 筐，不该在这） |
| ③ `native-capabilities.txt` | 12 | 从②生成，②错了跟着错 |
| ④ `supply/seed.json` | 7 | 无任何读者，内容与共识矛盾 |

---

## 阶段 1：清理（删掉确认无用的）

| # | 删/改什么 | 依据 |
|---|---|---|
| 1.1 | `assets/supply/seed.json` | 无任何读者；内容写着「运行时与工具一律经商店安装」，与共识矛盾 |
| 1.2 | `program-feed.json` 与其旧格式分支 | `ProgramOtaUpdater.parseConfig():71-77` 已优先读 `channel.json` 的 `manifests.program`，它只是回退。**状态文件名要迁移**：`program-feed-state-*` → `ota-state-*`（先读旧名回退，不丢设备上已装状态） |
| 1.3 | `NativeAssetRegistry.kt:20` 的 `node` 条目 | 它的 note 自述「APK 内不该有这份（build-apk.yml 有断言）」。node 的正路是 `ProgramIndex` |
| 1.4 | `gen-native-assets.js:98` 的 `EXTRA_CAPS` | `node-pty` 硬编码在 JS 生成器里，既不在 Kotlin 注册表也不在产出清单中 |
| 1.5 | **修 11 条链的 tar glob** | 事实 3。改法：去掉 `-C dist` + glob 的组合（与 `llvmtoolchain` 一致用 `-C dist --exclude '*.tar.gz' .`），或让 glob 在 `-C` 之前就解析成实际文件名 |

**门禁**：① APK assets 只允许一份锚点 ② 无读者的文件不许进仓
③ **tar 的 glob 与 `-C` 不得同时出现**（用上面那个反例做判据）

---

## 阶段 2：版本号补齐（决定哪些件能进 OTA）

| # | 件 | 缺什么 | 补法 |
|---|---|---|---|
| 2.1 | `crypto` | `NativeAssetRegistry.kt:106` 无 `version` | = `openssl` **3.6.3**（同一次编译产物）。补上则**立即可 OTA 更新** |
| 2.2 | `ripgrep` | 钉值表无此项；`:43` 无 `version` | 版本 **14.1.1**。⚠️ 走 cargo 不走钉值表（事实 5）—— 要么纳入钉值表，要么明确为例外并写清代价 |
| 2.3 | `libcxx` | `:9` 无 `version` | = NDK **30.0.16248370** |
| 2.4 | 钉值表形状 | 只有 `version`，无 LST/latest 两版概念 | 改成 `{ lst: {version, sha256, urls}, latest: {...} }`，**只填 lst**；latest 留待有文件时补 |

**规则写进代码**（不是靠碰巧）：无 `version` 的件明确标注 `bundled-only`，
不进 OTA 清单。

**判据**：新门禁 `tools/check-base-versions.js` —— 每个基础环境件要么有 version
（可 OTA），要么显式声明 bundled-only。

---

## 阶段 3：注册表落地（把四份收敛成一份）

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

**jq 与 sysroot 要收进注册表**（事实 1）—— 它们是基础环境件，
但走的是另外两套机制（jq 走 zip 独立链、sysroot 走 `linkSysrootInclude`）。

**门禁**：
- 每件只能登记一次（重份判红）
- 每件必须有 `entry` 或显式声明「无入口」
- 代码里不得再有硬编码能力清单：`BIN_IDS`/`LIB_IDS`
  （`NativeAssetRegistry.kt:124,129`）、`BUSYBOX_APPLETS`
  （`PrefixProvisioner.kt:14-17`，18 个软链名）必须清空
- `bundled: true` 的件必须在打包产物里真实存在

---

## 阶段 4：版本记录统一

`selectVersion` / `selectedVersion` 是仅剩的两个死函数（事实 2）。
它们与 `NativeAssetUpdater` 现在「从软链路径段数反推版本」的做法重复 ——
后者是**推断**，前者是**记录**。

**动法**：统一到 `toolchain-selected.json`，`NativeAssetUpdater` 改读它。
**不新增装件逻辑**（`ProgramInstaller` 已有完整实现）。

---

## 阶段 5：拆「APK 原生件」这个类别

1. `NativeAssetRegistry.kt` 13 条按注册表重建
2. `PrefixProvisioner.provision()` 的「从 jniLibs 拷」改为按注册表的 `bundled` + `landing`，
   **首次内置仍从 APK 取源**（这是 `bundled: true` 的实现，不是新类别）
3. 门禁：代码里不再以 `nativeLibraryDir` 作为「能力来源」判断

---

## 依赖关系

```
阶段1（清理）──┐
               ├─→ 阶段3（注册表）─→ 阶段4（版本记录）─→ 阶段5（拆类别）
阶段2（版本号）─┘
```

**阶段 5 必须最后做**，3、4 未完成前不许动 —— 先做会变成两套并存，
那正是当前混乱的成因。

---

## 待确认

| # | 项 |
|---|---|
| A | **APK 构建不出来**（openssl 的 `$ORIGIN` 被 perl 吃掉，实测 RUNPATH=`RIGIN`）。已在 `check_lib` 加 `od -c` 字节级取证。**这是阶段 1.5 与所有验证的前提** |
| B | **`ripgrep` 要不要纳入钉值表？** 它走 cargo、无 sha256 校验，与其余 15 件不同。纳入则要定「crates.io 的 sha256 怎么取」 |
| C | **`DISTRIBUTION-PLAN.md` 待废弃** —— 它第 4 层要「拆掉 APK 原生件类别」，与「随 APK 首发内置」相反；「消费方全改为读注册表」踩了 `BASE-ENV-DESIGN.md:139-149` 已否掉的方向 |

---

## 本方案不做的事

- 不新增分发通道
- 不在 APK 里放任何清单本体
- 不为「最小改动」妥协 —— 阶段 3 不落地，4、5 都建在流沙上
- 不删任何有读者的东西（每删一项都要先证明无读者）
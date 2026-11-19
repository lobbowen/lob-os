# 运行时版本管理架构

本文定义 Agent OS 的运行时版本管理。这是系统底层结构，不是安装流程的补丁。

产品定位决定：runtime 类产品（python/node/go）必然多版本并存；
应用声明版本约束时系统默认选已装的最高兼容版本；
用户可为特定应用覆盖成指定版本；
这套机制在"装应用"时就生效，而不是事后切换。

标准 Linux 没有"以应用身份为作用域"的版本选择（update-alternatives 是全局的、
pyenv 是目录/会话级的），这是本系统有别于 Linux 的地方，要做扎实。

---

## 1 照标准抄的部分

| 标准模式 | 抄什么 | 出处 |
|---|---|---|
| pyenv / asdf | 版本分槽（opt/<id>/<版本>/）+ 按调用方路由（PATH/LD_LIBRARY_PATH 注入），选择链有明确优先级 | pyenv PYENV_VERSION > .python-version > global；asdf .tool-versions > env |
| update-alternatives | 自动态选最高优先级 / 手动固定 / 回自动，三种状态清晰 | update-alternatives auto/manual/--auto |
| nix | 把解析结果作为快照写进应用记录，保证可复现，不被全局状态带偏 | flake.lock 思路 |

不抄的：pyenv 的目录/会话作用域（我们要应用身份）、Nix 的构建闭包锁定（太重，
我们锁"已装版本里的选择"而非整条依赖闭包）。

---

## 2 我们新增的部分（Linux 没有的）

一个 **Resolver（版本解析器）**：
- 作用域是**应用**（appId），不是目录、不是会话、不是全局
- 时机是**安装期**（应用安装/升级时解析），不是启动 shell 时
- 输入：appId + 版本约束列表
- 输出：每个 runtime 的确定版本，作为快照持久化到应用记录
- 用户覆盖单独存，与解析快照分离

全局 CURRENT 指针降级为"系统默认版本"，不再承担每应用的版本选择。
这样多个应用可同时用不同版本的同一 runtime，互不干扰。

---

## 3 四层结构

### 3.1 版本注册表

记录系统装了哪些版本。不新建重复真相——从现有事实派生：
- `ProgramDir.installedVersions()`：已装版本目录
- 各版本目录的 `component-meta.json`：entry / capabilities / sha256
- 目录清单的 `versions[]`：可装的远程版本

派生出一个只读视图 `versions/<id>/<ver>` 记录
path / binDir / libDir / entry / capabilities / default。
`ProgramIndex` 的单 version 保留为系统默认（OTA / 启动用），不当解析结果。

### 3.2 解析规则

`resolve(appId, requires) -> Map<runtimeId, binding>`：

1. 用户覆盖：若该应用对该 runtime 有显式覆盖版本，用它（用户强制优先）
2. 已装版本里满足 range 的最高版：用 `VersionRange.satisfies` 做谓词，
   用版本比较（三段数字）排序取最高
3. 若已装都不满足，从目录 `versions[]` 选最高兼容并自动安装
   （复用 runtimeGate 的自动安装，但按版本而非按名字）
4. 都不满足 → 安装失败，报"已装 N 个版本均不满足 <range>"

关键修正：现在的 `runtimeGate`（PackageInstaller.kt:209）只检查系统当前版本，
不满足就直接失败，不会在其它已装版本里找——这是结构性缺口，必须改成 resolve。

### 3.3 执行路由

按解析结果让应用进程用对应版本的 runtime。路由点是
`RuntimeEnvironment.treeRootEnv`（每进程构造 PATH / LD_LIBRARY_PATH）：

- 用 `LOBOS_PROGRAM_ID`（已在 RESERVED_ENV 里，天然的应用身份载体）查该应用的绑定
- 把选中 runtime 的 bin 放到 prefixBin **之前**（覆盖全局默认）
- LD_LIBRARY_PATH 只放选中版本的 lib 目录，不再把所有版本全塞进去
  （现在 pieceLibDirs 全量加入，跨版本会串味，这是必须修的）
- 全局 `usr/bin` 软链（LdConfig）降级为系统默认 / fallback，不做每应用切换，
  避免多版本并行时全局软链互相竞争

`ExecEntry.argv()` 保持"给定绝对入口构造 argv"的职责；
若 shebang 解释器需要按绑定重写，加一个薄层，不改 ExecEntry 核心。
native exec-path.c 目前无版本逻辑，内核级按应用路由是更大改动，不在本次范围。

### 3.4 应用级持久化

两份数据，职责分离：

1. **解析快照** `UnitEntry.runtimeBindings`（应用记录里）：
   `{ runtimeId: { constraint, resolved, source } }`
   安装/升级时重算并落盘，运行期只读快照。
   作用：可复现，不被全局 CURRENT 改动带偏。

2. **用户覆盖** `libvar/runtime-overrides.json`：
   `{ apps: { appId: { runtimeId: version } } }`
   控制面板写，与解析快照分离。用户清空覆盖后回到自动态。

卸载/清理时，被绑定或被覆盖的版本视为 pinned（类似 FLOOR），受保护不被回收。

---

## 4 数据模型

目录 versions[] 项（CatalogClient 已识别，扩展 provides/capabilities）：
```json
{"name":"python","versions":[
  {"version":"3.12.10","url":"...","sha256":"...","entry":"bin/python3",
   "default":false,"provides":"python","capabilities":["python3","stdlib"]}
]}
```

应用 manifest 依赖（结构化，兼容旧字符串形式）：
```json
{"id":"myapp","version":"1.0",
 "requires":[{"name":"python","range":">=3.12 <3.14"}]}
```

ProgramIndex 条目（新增 runtimeBindings）：
```json
{"id":"myapp","version":"1.0","requires":["python>=3.12"],
 "runtimeBindings":{"python":{"constraint":">=3.12 <3.14",
                              "resolved":"3.12.10","source":"resolved"}}}
```

用户覆盖：
```json
{"schema":1,"apps":{"myapp":{"python":"3.11.13"}}}
```

---

## 5 先决修复（不做这些，上面全是空中楼阁）

1. **ProgramIndex.kt:151 env 编解码不对称**：encode 写成
   `JSONObject(...).toString()` 字符串，decode 用 `optJSONObject("env")` 读，
   重载后 env 恒为空。必须先改成 `put("env", JSONObject(e.env.toMap()))`，
   否则任何基于 env / binding 的路由在重载后失效。

2. **runtimeGate 改为调 resolve**：从"只查当前版本"改为"在已装版本里找最高兼容"。

3. **PackageInstaller.install 按版本取 url/sha256**：现在只读顶层字段，
   目录 versions[] 里选中的版本下载不到。

4. **RuntimeEnvironment 按绑定收敛 LD_LIBRARY_PATH**：只注入选中版本的 lib，
   不再全版本串味。

5. **SystemInstallActivity 的"未安装版本"按钮**：改为带版本安装
   （现在 install(name, null) 不重选下载地址）。

---

## 6 与现有机制的关系

保留并复用：
- ProgramDir 版本槽 + installedVersions + CURRENT（CURRENT 降为系统默认）
- ProgramOtaStateStore 的原子写与 pinned 保护
- VersionRange.satisfies
- PackageInstaller.activate（系统级默认版本切换仍可用）
- ExecEntry.argv（统一 argv 构造点）
- CatalogClient.versions[]（目录已支持多版本）
- SystemInstallActivity 版本 UI（扩展为应用级绑定视图）

不推翻：PYTHON-MULTIVERSION-PLAN.md 定的"一个 name + versions[] + 分槽 + CURRENT"
继续有效；本文在它之上加"应用作用域的解析与绑定"。

---

## 7 分阶段实施（按依赖顺序）

1. 先决修复（§5）：env 编解码、install 按版本取地址、LD_LIBRARY_PATH 收敛
2. 版本注册表派生视图（§3.1）：从现有事实派生，不新建真相
3. Resolver（§3.2）：resolve(appId, requires) + 自动安装兜底
4. 持久化（§3.4）：runtimeBindings + overrides + pinned 保护
5. 执行路由（§3.3）：RuntimeEnvironment 按绑定注入
6. UI：应用详情显示 runtime 绑定、用户覆盖入口、系统默认版本切换
7. 设备验证：多应用用不同 python 版本并行、默认取最高、覆盖生效、装应用时解析

第 2 步（3.12/3.11 构建）属于版本注册表的"可装来源"，与上述结构并行推进，
但不依赖结构完成即可产出件包（件包是输入，不是结构本身）。

---

## 8 报缺（本次设计未核实）

- Android 原生是否有类似 application-specific runtime 选择机制：未单独取证
- native exec-path.c 之外是否有其它按应用版本逻辑：仓内零命中，不排除外部 patch
- 三版本同时安装时 LdConfig 对同名 SONAME 的歧义判定：需实测


---

## 9 实施中查实的三块地基（必须先修，否则上面全是空中楼阁）

调查（linux-architect）实读代码发现三处与本文假设冲突的地基问题，逐条记录并定处置：

### 9.1 「运行时」角色今天读不出来

`ProgramIndex.declaresRole()`（ProgramIndex.kt:242-250）读的是 component-meta.json 的
`roles` 数组，但该数组在 commit 1ea91856 被删除（理由：自报字段不可信）。
实读真件 component-meta.json 只有单值 `role`（如 "program"），无 `roles`。
结论：`programRuntime()` 今天恒返空，"运行时"角色无法被机械识别。

处置：`declaresRole` 改为读单值 `role`，并新增一个"运行时"声明判据。
不复活 roles 数组（它是被有意删掉的自报字段）。判别形式：
"这一件是不是运行时"必须落在 component-meta.json 的一个机器可读字段上。
具体形态（role 扩多值 vs 另起 kind:runtime）由规范裁决，本文先记判别形式。

### 9.2 构建侧今天是"三个独立件名"，与本文"一件名多版本"冲突

当前构建把 3.14/3.12/3.11 做成三个钉值键（python / python-3.12 / python-3.11），
tag 分别是 rt-python / rt-python-3.12 / rt-python-3.11 —— 即三个件名，
落位在 opt/python/、opt/python-3.12/、opt/python-3.11/。
而本文的 Resolver / activate / requires 版本区间全部假设"一件名 python + opt/python/<ver>/"。

处置（按用户已定的产品语义"默认最高版 + 应用可选 + requires 版本区间"）：
统一到"一件名 python + 多版本目录 opt/python/<ver>/"。
构建侧改：三个版本共用件名 python，版本差异进 versions[] 与目录名，
不进件名。这样 requires 的"名 + 版本区间"、Resolver、activate 才自洽。
"承认三个件名 + 扩展 activate 认后缀"那条路不采用——它会让 VersionRange 语义失效。

### 9.3 一份声明里的回退链

照 asdf（版本有序列表，首选 + 回退），应用声明可带有序版本列表；
解析层先满足首选，不满足按声明里的回退项，最后才报缺。
该形态进 §4 数据模型的 requires 结构（range 扩为有序候选）。

### 9.4 调查保留的范围问题（本文的处置）

- 一件能否同时装多版本：数据结构允许（opt/<id>/ 下多版本目录），
  本文按"允许"设计，并用设备实测确认。
- Android 原生是否有 application-specific runtime 选择机制：未取证，
  不影响本文（我们要新做这一层）。
- native exec-path.c 之外是否有按应用版本逻辑：仓内零命中，不混入本文。

---

## 10 构建槽位与发布件的分离（ tenth 节，实施中定）

问题：3.14/3.12/3.11 是同一个"发布件 python"的三个"构建槽位"，
但我把槽位名（python-3.12/3.11）登记成独立件（bucket=rt），导致 fetch
按件名找 build-11.yml（不存在）而失败。

### 判别式

凡一格要答"这是个什么"，必须分清两个问题：
1. **构建槽位**：为了产出不同版本而存在的构建维度（python-3.12 是一个槽位，
   对应 build-python3.yml 的一次 matrix run，产出 rt-python-3.12 tag 的 asset）
2. **发布件**：清单里用户可见的可安装单元（python 是一件，有三个版本）

**槽位不是件**。槽位不进 bucket_for / 不作为独立件被 fetch / 不在清单里单独出现。
槽位通过"件名 + 版本"与发布件关联：python 件的三个版本分别由三个槽位产出。

### 链路修正（一次性，不逐点补丁）

1. cache-key.sh：python-3.12/3.11 从 bucket_for 移除（它们不是独立发布件）。
   但 tag/asset/key 计算仍要支持槽位（构建 workflow 用），所以 deps_for/SRC_FILES
   保留槽位登记，只从 bucket_for 移除。
2. fetch-component-artifacts.sh：对 python 件，从 build-python3.yml 的最近成功
   runs 取**所有版本**的 asset（不只一个 tag），聚合成该件的多个版本条目。
   具体：件名 python → workflow build-python3.yml → 该 workflow 最近 N 个成功 run
   → 每个 run 的 asset → 按件内 id 都是 python → 多版本。
3. publish-component-manifest.js：已按件内 id 聚合 versions[]（保留）。
4. 清单/CDN：已展开 versions[].url（保留）。

这样：槽位只在构建期存在（产出不同 tag 的 asset），发布期只有一个件 python，
fetch 从该件 workflow 的多个 run 取多个版本，清单聚合成 versions[]。

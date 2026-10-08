# 我们的系统该抄什么 —— 重新定位

> 前一版（`REGISTRY-COMPARISON.md`）查了 dpkg 与 systemd。
> 用户指出**查偏了**：那两个都不是「装进系统的每一样东西的统一登记」。
> 本文重新定位，并给出真正对口的机制。

---

## 一、我上一版查偏在哪

| | 它解决的问题 | 我们的问题 |
|---|---|---|
| **dpkg** | 一个**软件包**的版本、依赖、装了哪些文件、状态 | |
| **systemd** | 一个**进程服务**怎么起、什么顺序、挂了怎么办 | |
| **我们要的** | **装进系统的每一样东西**（命令/库/运行时/工具/应用）的统一登记 | ✅ |

前两者都是「某一类东西」的登记表，不是「系统里所有东西」的登记表。

---

## 二、对口的机制是什么：`ld.so` / `ldconfig`

来源（2026-10-08 实查）：
- `ld.so.conf(5)` https://man7.org/linux/man-pages/man5/ld.so.conf.5.html
- `ldconfig(8)` https://man7.org/linux/man-pages/man8/ldconfig.8.html

### 2.1 Linux 怎么让「装进去的东西」全局可用

```
/etc/ld.so.conf   列目录（/lib、/usr/lib、include /etc/ld.so.conf.d/*.conf）
      ↓ ldconfig 扫描、建软链、生成缓存
/etc/ld.so.cache  缓存：有序的库清单 + tunables
      ↓ 运行时链接器 ld.so 查缓存
任何进程 exec/dlopen 都能找到库
```

**关键在官方文档的两句话：**

> ldconfig creates the necessary links and cache to the **most recent** shared libraries
> found in the directories specified …

> ldconfig checks the **header and filenames** of the libraries it encounters when
> determining which versions should have their links updated.

**它的机制是「扫目录 + 按命名规则建软链」，不是「登记每件东西的元数据」。**

### 2.2 命名规则（官方明写）

```
libfoo.so  →  libfoo.so.1  →  libfoo.so.1.12
    ↑           ↑ SONAME      ↑ 真身
```

官方说：**不遵守这个模式，升级后会有兼容性问题。**
而 `lib*.so*` 之外的**文件一律忽略**。

### 2.3 版本怎么体现

**版本在文件名里**（`libfoo.so.1.12`），不另设字段。
`ldconfig` 扫到新版本就改软链 —— **「装了什么版本」由文件系统的样子决定，不需要登记表**。

### 2.4 能不能登记「这个文件是谁带来的」

**`ldconfig` 不登记这个。** 它只管「哪个是最新的、软链该指向谁」。

「哪个包带来了这个文件」是 **dpkg** 的活（`db-fsys:Files`）——
**两件事，两套机制，分工明确。**

---

## 三、映射到我们：真正该抄什么

### 3.1 对口的部分：**统一搜索路径 + 命名规则**

我们已有对应的：
- `RuntimeEnvironment.libSearchPath(ctx)` ← 对应 `ld.so.conf` + `ld.so.cache`
- `$PREFIX/lib/<name>.so` + `RUNPATH=$ORIGIN` ← 对应 SONAME 软链模式

**这些不用改。** 它们已经是对的。

### 3.2 我们的真问题（核实过）

**三条安装路径，三套登记方式：**

| 路径 | 谁装 | 登记了吗 |
|---|---|---|
| zip 件（node/git/curl…） | `ProgramInstallPipeline` | ✅ `upsert` |
| APK 内置原生件（bash/openssl…） | `PrefixProvisioner` | ❌ **不 upsert** |
| 快应用 | `QuickAppRegistry` | ✅ `mutate`（只写 ui 字段） |

**根因**：内置原生件从来不走安装流程，所以内核只能硬编码它们的名字 ——
这就是那 9 处硬编码的来源（`bashBin()` `BUSYBOX_APPLETS` `SYSROOT_ID` 等）。

### 3.3 落位规则已经是代码里的一个判据

`ProgramManager.relStateDir`（第 90-91 行）：

```kotlin
if (kind == "INFRA") "" else PROGRAMS_DIR + "/" + id
```

**`INFRA` 不占 `stateDir`** —— 落位规则已经存在，只是没被用成统一判别。

---

## 四、最稳的方案（我的建议，含理由）

### 方案：**统一登记，但不登记 Linux 不登记的东西**

Linux 的分工很清楚：

```
ldconfig   管「哪个是最新、软链指向谁」   ← 靠文件系统，不登记
dpkg       管「谁带来了这个文件」         ← 登记
systemd    管「进程怎么起、什么顺序」     ← 登记
```

**我们的对应：**

| 我们该做什么 | 靠什么 | 登记吗 |
|---|---|---|
| 能力全局可用 | `$PREFIX/bin` + `$PREFIX/lib` + 统一搜索路径 | **不登记**（已经是这样） |
| 装了什么、什么版本 | 落位的软链指向哪 + 问件自己 | 现在混在 `desired` 里 |
| 谁带来了这个文件 | 注册表 | **没有 —— 该补** |
| 进程怎么托管 | 注册表 | ✅ 已有（`resident`/`restart`…） |

### 具体三步（按稳定性排序）

**第 1 步：让内置原生件也登记（最该做，收益最大）**

```
PrefixProvisioner.provision() 铺完之后
  → 对每个铺好的件 upsert 一条
  → 字段与 zip 件完全一样
```

**为什么最稳**：
- 不新增任何字段、不改任何数据结构
- 内置件与 zip 件从此在表里长得一样 → 内核不用再判「这个是不是特殊的」
- 那 9 处硬编码自然可以删（问表就行）
- 出问题能立刻退回（去掉 upsert 调用即可，行为回到今天）

**第 2 步：状态拆三段（dpkg 的做法）**

```
want:  install | remove          ← 用户/系统想要
state: unpacked | installed | …  ← 实际到哪一步
eflag: reinst-required           ← 有没有问题
```

**为什么稳**：`desired` 现在一个字段糊三件事，拆开是纯替换。
而 `desired` 的三个值（RUNNING/STOPPED/FROZEN）已被 `ProgramStateMachine` 使用，
拆的时候要**保留它们的现有语义**。

**第 3 步：登记「铺了哪些路径」（dpkg 的 `db-fsys:Files`）**

装完记录这一批字节铺了哪些路径。

**为什么稳**：纯新增字段，不改现有逻辑。
收益是删件时能精确删除，以及能回答「`$PREFIX/bin/curl` 是哪个版本铺的」。

### 我不建议做的

| | 为什么 |
|---|---|
| 把 `ui*`/`http*` 归子对象 | 改动 13 个文件的读写形状，收益只是「表好看」 |
| 抄 systemd 的 drop-in / unit 模板 | 我们没有多方配置同一件的需求 |
| 抄 dpkg 的 9 种依赖 | 我们不做可选依赖分级 |
| 引入「文件清单」之外的更细粒度登记 | 过度设计 |

---

## 五、需要你定的

**第 1 步做不做？**

它是三步里唯一**能立刻消掉 9 处硬编码**的，而且是纯新增（不动任何现有字段）。

不做的话，内核里就得一直硬编码「哪个件是 bash、哪个是 openssl」——
而那正是「东一套西一套」的来源。

另外我这一版**没有再查新东西**了 —— 因为核实完发现：
- 能力下放机制（`ld.so`）**我们已经有了且是对的**
- 真问题不在「注册表该有什么字段」，在「**不是所有东西都走登记这条路**」

这是我上一版查偏的原因：我在找「标准注册表长什么样」，
而真问题是「**什么东西没走登记**」。
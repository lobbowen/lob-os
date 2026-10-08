# 落位：把整件事串起来的那条线

> 用户提示：要从更底下看 —— **东西落位是怎么落的，把落位与「它是什么」连起来**。
> 本文把三条落位路径摊开，找出之前几轮在字段层面打转时看不见的问题。
> 核实日期 2026-10-08，每条都能追到 `文件:行号`。

---

## 一、三条落位路径（代码追出来的实际形态）

```
files/usr/                 ← $PREFIX（PrefixProvisioner.kt:27）
│
├── bin/                            ← 全局 PATH 里的目录
│   ├── bash                        真文件（内置件「铺在原地」）
│   ├── rg  busybox  ptysession     真文件
│   ├── tar gzip grep sed …         软链 → busybox（18 个 applet）
│   ├── jq  make  cmake             真文件？—— 见 §2.2 的问题
│   └── node  npm  python3  git …   软链 → ../lib/toolchain/<id>/<版本>/bin/<name>
│
├── lib/                            ← 全局库搜索路径里的目录
│   ├── libc++_shared.so            真文件（内置）
│   ├── libssl.so libcrypto.so      真文件（内置）
│   ├── libz.so libcurl.so          真文件（内置）
│   ├── ca-bundle.pem
│   └── toolchain/<id>/<版本>/        ← 商店件落在这里（SupplyProvisioner.kt:24）
│
├── include/                       → 软链到 sysroot 的 include
└── share/                         ← 文档说有 zoneinfo，实际没有
```

三条路径的代码出处：

| 路径 | 谁定的 | 出处 |
|---|---|---|
| 内置件铺 `$PREFIX/bin`·`$PREFIX/lib` | `PrefixProvisioner.provision()` | `PrefixProvisioner.kt:37` |
| 商店件落 `$PREFIX/lib/toolchain/<id>/<版本>` + `bin` 建软链 | `SupplyProvisioner` | `:24` `versionDir` · `:29` `entryLink` |
| 程序落 `files/programs/<id>` | `ProgramManager.relStateDir` | `:90-91`（`INFRA` 返回空串，不占 stateDir） |

---

## 二、三个真问题（字段层面看不见的）

### 问题 1：**内置件与商店件的形态不同，但入口目录相同**

| | 落位 | `bin/` 里的形态 |
|---|---|---|
| **内置件** | `lib/libssl.so` | `bin/bash` 是**真文件** |
| **商店件** | `lib/toolchain/<id>/<版本>/` | `bin/node` 是**软链** |

**`bin/` 里混着两种形态。** Linux 上这两者是一样的 ——
`ldconfig` 建的就是软链（`libfoo.so → libfoo.so.1 → libfoo.so.1.12`），
我们却让内置件是「真文件铺在原地」。

**后果一**：`NativeAssetUpdater.pointEntryAt()` 换软链那套逻辑，
**对内置件根本不适用** —— `libssl.so` 没有「版本目录」可指向。
所以现在：**内置件永远无法通过 OTA 更新**（不是没做，是形态上做不到）。

**后果二**：想知道「`$PREFIX/bin/bash` 是哪来的」，
得先判断它是真文件还是软链 —— 而判断方法又是硬编码的（见问题 3）。

### 问题 2：`jq` `make` `cmake` 到底落在哪？

| 分类 | 落位 |
|---|---|
| bash / rg / busybox | 内置件 → `$PREFIX/bin` |
| jq / make / cmake | **也是基础环境筐，但它们是 zip 独立链 + 有七牛上传** |

按「商店分发」它们落 `lib/toolchain/<id>/<版本>/` + `bin` 软链；
按「基础环境」它们该像 bash 一样内置。
核实：build-jq/make/cmake.yml 各有 2 处 upload-qiniu → 它们确实走商店分发那条路。

**现在落位由它们恰好走哪条链决定，不由任何声明决定。**

### 问题 3：「形态」是硬编码的，不是由落位决定的

`PrefixProvisioner.provision()` 的 plan：

```kotlin
Triple(BINS, binDir(ctx), true),   // BINS → bin
Triple(DEPS, libDir(ctx), false),  // DEPS → lib
```

看似「按落位不问名字」，但：
`BINS` 来自 `NativeAssetRegistry.BINS` = `CAPABILITY.filter { it.id in BIN_IDS }`，
`BIN_IDS = setOf("bash", "ripgrep", "ptysession", "busybox")`。

**「形态」最终由两个硬编码的 id 集合决定**（`NativeAssetRegistry.kt:117` `BIN_IDS`、`:112` `LIB_IDS`）。

Linux 那边：`ldconfig` 只看文件名模式（`lib*.so*`），**不需要任何人维护一个 id 清单**。
我们这边：加一件新的可执行件，要改 `NativeAssetRegistry.kt:117` 那行 Kotlin。

---

## 三、Linux 是怎么把落位与「是什么」连起来的

来源：`ldconfig(8)` https://man7.org/linux/man-pages/man8/ldconfig.8.html

```
① 命名规则：libfoo.so → libfoo.so.1 → libfoo.so.1.12
   官方：“ldconfig expects a certain pattern to how the symbolic links are set up”
        “Failure to follow this pattern may result in compatibility issues”
② 落位：/etc/ld.so.conf 列目录 → ldconfig 扫 → 建软链 → 写 ld.so.cache
   官方：“ldconfig will look only at files that are named lib*.so* …
         Other files will be ignored.”
③ 能力可用：任何进程链接时查缓存
```

**三件事都是「一件东西」自动获得的**：
文件名符合模式 → 落进目录 → ldconfig 建软链 → 全局可用。

**没有任何一步需要声明「我是什么」。**

**Linux 把「版本」编码进文件名**（`.so.1.12`），
所以「多个版本共存 + 切换」是靠**文件系统的样子**解决的，不是靠字段。

---

## 四、落位要连起来看，三件事必须先定

### 定 1：内置件要不要也变成「版本目录 + 软链」

| | 形态 | 能 OTA 更新吗 | 像 Linux 吗 |
|---|---|---|---|
| 现在 | `lib/libssl.so` 真文件 | ❌ **做不到** | ❌ |
| 改成 | `lib/toolchain/openssl/<版本>/lib/libssl.so` + `lib/libssl.so` 软链 | ✅ | ✅ |

**这是问题 1 的根**。不改，内置件永远进不了 OTA；
改了，`bin/` 与 `lib/` 里形态统一，Linux 那套机制直接能用。

### 定 2：`jq` `make` `cmake` 归哪边

它们是 zip 独立链（有七牛上传、有版本号），但分类是基础环境筐。
**落位该由「它是什么」决定，还是由「它恰好走哪条链」决定？**

### 定 3：「形态」由谁定

| | 现状 | Linux |
|---|---|---|
| 判据 | 两个硬编码 id 集合（`BIN_IDS`/`LIB_IDS`） | 文件名模式 |

**若改成 Linux 式**：`bin/` 里的软链 → 命令，`lib/` 里的 `.so` → 库，
**不需要任何 id 清单**。加一件新东西，放对目录就生效。

---

## 五、我看到的最稳的一条主线

把三件事连起来看，最稳的顺序是：

**① 先统一形态**（定 1）
内置件也落 `toolchain/<id>/<版本>/`，`bin/`·`lib/` 里全部是软链。

**② 落位变成唯一判据**（定 3）
「在 `bin/` 里 = 命令，在 `lib/` 里 = 库」——
内核不用问「这个 id 是什么」，看它在哪。

**③ 登记变成副产品**（定 2 之后自然成立）
落位的那一瞬间，「装了什么、什么版本」已经由文件系统表达了；
注册表只需补「谁带来了这些文件」—— 那是 dpkg `db-fsys:Files` 的活。

**这条主线的好处**：① 和 ② 都是「改落位规则」，不改数据结构；
③ 是纯新增。出问题都能退回。

---

## 六、需要你定的（按顺序）

1. **内置件要不要变成「版本目录 + 软链」？** —— 这是问题 1 的根，也是「内置件能不能 OTA」的前提
2. **`jq`/`make`/`cmake` 归内置还是归商店件？**
3. **形态由落位决定（Linux 式）还是继续由 id 清单决定？**

第 1 条不定，后两条都定不了 —— 因为它们都在描述「落位长什么样」。
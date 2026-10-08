# 真正的注册表：Windows 与 Linux 怎么做

> 来源（2026-10-08 实查官方文档）：
> - **Windows**「About the Registry」/「Structure of the Registry」/「Registry value types」
>   https://learn.microsoft.com/en-us/windows/win32/sysinfo/about-the-registry
>   https://learn.microsoft.com/en-us/windows/win32/sysinfo/structure-of-the-registry
>   https://learn.microsoft.com/en-us/windows/win32/sysinfo/registry-value-types
> - **Linux** `ld.so.conf(5)` / `ldconfig(8)`
>   https://man7.org/linux/man-pages/man5/ld.so.conf.5.html
>   https://man7.org/linux/man-pages/man8/ldconfig.8.html
>
> 本文只写**官方文档明写的事实**，以及它推翻了我们什么。

---

## 一、Windows 注册表：它只有三样东西

官方原文（Structure of the Registry）：

> The registry is a **hierarchical database** … structured in a **tree format**.
> Each node in the tree is called a **_key_**.
> Each key can contain both **_subkeys_** and data entries called **_values_**.

```
注册表 = 树
  key    （节点）    → 可含 subkey 与 value
  value  （数据项）  → 名字 + 数据 + **类型**
```

**就这三样。没有「字段」这个概念。**

### 1.1 「不同东西有不同标记」的出处：value 的类型

官方 value 类型表（`winnt.h` 定义）：

| 类型 | 是什么 |
|---|---|
| `REG_SZ` | 字符串 |
| `REG_EXPAND_SZ` | 含未展开的环境变量的字符串（如 `%PATH%`） |
| `REG_MULTI_SZ` | 字符串序列（`\0` 分隔） |
| `REG_DWORD` | 32 位数 |
| `REG_QWORD` | 64 位数 |
| `REG_BINARY` | 二进制 |
| `REG_LINK` | 符号链接目标路径 |
| `REG_NONE` | 无类型 |

**「这个件是什么」不是由某个字段声明的，而是由「它以什么形态落进树里」决定的。**
`REG_DWORD` 就是数、`REG_SZ` 就是字符串、`REG_MULTI_SZ` 就是列表 —— 形态自带类型。

### 1.2 树的关键性质（官方原文）

| 性质 | 原文 |
|---|---|
| **子键名字在其父键内唯一** | “The name of each subkey is **unique with respect to the key that is immediately above it** in the hierarchy.” |
| **键名不区分大小写** | “Key names are **not case sensitive**.” |
| **键名不含反斜杠** | “Key names **cannot include the backslash character**” |
| **树深上限 512 层** | “A registry tree can be 512 levels deep.” |
| **键名不本地化，但值可以** | “Key names are **not localized** into other languages, although **values may be**.” |
| **有时「键存在」本身就是全部数据** | “Sometimes, **the presence of a key is all the data** that an application requires” |

### 1.3 预定义的顶层键

`HKEY_LOCAL_MACHINE` 下：`HARDWARE` `SAM` `SECURITY` `SOFTWARE` `SYSTEM`

**注册表本身没有「这一条是什么类别」的字段** —— 类别由**它在树的哪个位置**决定。

---

## 二、Linux：更简单 —— 它几乎不需要注册表

### 2.1 能力下放的机制

```
/etc/ld.so.conf     列目录（/lib、/usr/lib、include /etc/ld.so.conf.d/*.conf）
      ↓ ldconfig
/etc/ld.so.cache    缓存：有序的库清单 + tunables
      ↓ 运行时链接器 ld.so 查缓存
任何进程链接时都找得到
```

### 2.2 版本在文件名里

官方明写的命名规则（`ldconfig(8)`）：

```
libfoo.so  →  libfoo.so.1  →  libfoo.so.1.12
    ↑           ↑ SONAME      ↑ 真身
```

| 官方原文 | 含义 |
|---|---|
| “ldconfig will look only at files that are named `lib*.so*` … **Other files will be ignored**.” | 它只认这个模式 |
| “ldconfig expects a certain **pattern** to how the symbolic links are set up” | 软链必须按这个模式 |
| “**Failure to follow this pattern may result in compatibility issues after an upgrade**.” | 不遵守会出事 |
| “ldconfig checks the **header and filenames** … when determining which versions should have their links updated” | 它看文件名与文件头 |
| 描述 ld.so.cache：contains “an **ordered list of libraries** found in the directories specified in /etc/ld.so.conf” | 缓存就是一个有序清单 |

### 2.3 关键：Linux **不登记「谁带来了这个文件」**

`ldconfig` 只管「哪个是最新、软链指向谁」。
「哪个包装了哪些文件」是 **dpkg** 的 `db-fsys:Files` 的事。

**两件事，两个机制。Linux 没有「一个统一的注册表」。**

---

## 三、对照我们：我们的结构错在哪

### 3.1 我们把「类别」做成了字段

| 我们 | Windows | Linux |
|---|---|---|
| `level: INFRA / CAPABILITY / APPLICATION` | **没有这种字段** —— 类别由**在树的哪个位置**决定 | **没有** —— 形态由文件名决定 |
| `category` | 没有 | 没有 |
| `tier: self-c / upstream / soft` | 没有 | 没有 |
| `form` 之类 | 没有 | 没有 |

**我们有三个字段在回答「这是什么」，而两个真系统都不回答这个问题 ——
它们让「是什么」由「落在哪、长什么样」自然决定。**

### 3.2 我们把「键」与「值」混在一张平表里

Windows 的结构：

```
key（节点，可含 subkey）
  └── value（数据项：名字 + 数据 + 类型）
```

**我们的 `IndexEntry` 是 28 个平铺字段 —— 既不是 key 也不是 value，是二者搅在一起。**

### 3.3 我们的「装机登记」不是真系统的做法

| | 真系统 | 我们 |
|---|---|---|
| 装个库 | `ldconfig` 扫目录建软链，**不登记** | —— |
| 装个包 | dpkg 登记「包装了哪些文件」 | `ProgramIndex` 登记 28 个字段 |
| 知道「这个文件是谁带来的」 | dpkg `db-fsys:Files` | **没有** |
| 知道「哪个是最新」 | 文件名 + 软链 | `version` 字段（抄来的） |

---

## 四、结论：两个真系统的共同点

| 共同点 | Windows | Linux |
|---|---|---|
| **形态自带类型** | `REG_DWORD`/`REG_SZ`/`REG_MULTI_SZ` | `lib*.so*` / `ld-*.so*`，其余忽略 |
| **类别由位置/形态决定，不由字段声明** | 键在树的哪一层 | 文件名模式 |
| **「有」本身就是信息** | “the presence of a key is all the data” | 软链存在即已链接 |
| **键名唯一（同父内）、不区分大小写** | 官方明写 | 路径唯一 |
| **不预先枚举类型** | 加新值不需要改表结构 | 新库放进去 `ldconfig` 就认 |

**最重要的一条：两个系统都不问「这是什么」。**
它们只问「它落在哪、是什么形状」——
**然后机制按形状处理，不按名字。**

---

## 五、这套认识对我们意味着什么

我们的 `IndexEntry` 现在要求每件都填满 28 个字段：
不填 `ui*` 的内核件、要填 `http*` 的命令、要填 `restart` 的库……
**这正是「补丁」的来源** —— 为了让一张平表装下所有形态，就必然要有空字段与条件逻辑。

真系统的做法：**按形态分开**。
一个 `libz.so` 只需要「文件名 + 字节在哪」，不需要 `uiName`。

但这里我**不急着提方案** —— 因为：

1. 上一版我已经「不急着提方案」过一次，然后提了个错的（让内置件也填满 28 个字段）
2. 真正的设计问题还没定：**我们的树长什么样？**
   按什么分层？（按「谁提供能力」？按「装在哪」？按「谁依赖谁」？）
   Windows 按 `HKLM\SOFTWARE\<厂商>\<产品>` 分层，我们按什么？

**这两个问题定不下来，注册表的结构定不下来。**
而你问的「这个件是不是原生件」—— 真系统的答案是：**它不需要知道**。
它在 `$PREFIX/lib` 就是库，在 `$PREFIX/bin` 就是命令，在 `stateDir` 就是应用。
**形态决定一切，名字不参与判断。**

### 需要你定的第一件事

**我们的「树」按什么分层？** 三个候选：

| | 分层依据 | 后果 |
|---|---|---|
| **A** | 按**落位**（`$PREFIX/lib` · `$PREFIX/bin` · `stateDir`） | 内核不用判「是什么件」，看它在哪就行 —— 最贴近 Linux |
| **B** | 按**筐**（base / rt / tool / app） | 与构建期分类一致，但那是构建期概念，运行期未必需要 |
| **C** | 按**提供者**（系统自带 / 发行版装的 / 用户装的） | 像 Windows 的 `HKLM\SOFTWARE\<厂商>` |

我倾向 **A** —— 因为它让「内核不认具体件」这条自然成立：
内核只问「哪些东西在 `$PREFIX/bin`」，不关心它们是 bash 还是 jq。
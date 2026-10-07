# 开发环境：Linux 怎么做，我们怎么做

已定：开发环境要存在。这份文档回答「默认装还是后补」，
依据是 Debian/Ubuntu 的实际做法，不是印象。

---

## 一、Linux 的实际做法

### 1.1 编译器不在最小系统里

Debian 的 `build-essential` 是个**独立的元包**（meta package），
官方描述原文：

> **If you do not plan to build Debian packages, you don't need this package.**

它依赖五件：

| 依赖 | 是什么 |
|---|---|
| `gcc` | C 编译器 |
| `g++` | C++ 编译器 |
| `libc6-dev` | C 库**头文件 + 静态库**（sysroot 的核心） |
| `make` | 构建调度 |
| `dpkg-dev` | 打包工具 |

**结论一：编译器不是底座自带，是按需装的一组包。**

### 1.2 但它在标准安装档里

`build-essential` 的元数据标签：

```
task::  →  Software Development: Packaging
           User Interface: Command Line
           role:: program, scope:: utility
default
```

`default` 标签的意思是：**桌面版/标准安装档会装它**。
而 `debootstrap --variant=minbase`（最小系统）**不带**。

**结论二：它既不是"永远自带"，也不是"要用户自己找"——
是「标准安装带、精简安装不带」的一档。**

### 1.3 桌面的 GNOME/KDE 更是明确依赖它

桌面环境要用编译辅助工具、文档生成、扩展编译，
所以 `gnome-shell` / `plasma-desktop` 这类元包把 `build-essential`
列为依赖 —— 也就是说**桌面用户开箱即有开发环境**。

**结论三：发行版把「用户会不会开发」当成档位选择，不是例外。**

---

## 二、三种档位的划分

| 档 | Linux 对应 | 含编译器 | 谁在用 |
|---|---|---|---|
| **最小** | `minbase` / Alpine | ❌ | 容器、嵌入式、极简设备 |
| **标准** | Debian/Ubuntu 桌面安装 | ✅ | 绝大多数用户 |
| **完整** | `build-essential` + 开发工具 | ✅ + 更多 | 开发者 |

Alpine 更极端：默认连 glibc 都没有（用 musl），包很小，
开发环境要 `apk add build-base` 手动装。

---

## 三、我们的对应

LobOS 是**手机形态的完整系统**，不是服务器、不是容器。
用户画像更接近「桌面 Linux 用户」而不是「最小化部署」。

### 建议：分两档，但默认档带开发环境

| 档 | 内容 | 触发 |
|---|---|---|
| **标准档（默认）** | 运行时底座 + **开发环境** | 出厂即有 |
| 精简档 | 只有运行时底座 | 显式选择（`build.prop` 之类的一个开关） |

### 为什么默认带

| 理由 | 说明 |
|---|---|
| **对齐 Linux 桌面档** | 我们的用户是"在设备上开发/运行程序"，不是部署服务器 |
| **我们的程序需要现场编译** | 将来程序带 C/C++ 扩展时，需要 `libc6-dev` 那套 sysroot |
| **node 生态依赖编译** | 大量 npm 包要 node-gyp 现场编译（`npm` 已在底座） |
| **存储不是问题，但 APK 体积是设计前提** | 手机存储几百 MB 起步，装下没问题；**但当前 APK 27.5MB，且 components/native 的定位就是「小体积原生能力件」** —— 加编译器会突破这个前提，需要重新决定它属于哪一类 |

### 精简档什么时候有意义

- 存储紧张的设备
- 明确不开发、只运行预编译程序的用户

**但要放在"设置"里可选，而不是没有。** Linux 也没把 `minbase`
做成"卸载编译器"，而是做成"当初别装那个档"。

---

## 四、开发环境具体包含什么

对照 Debian `build-essential`，去掉与我们无关的（打包 dpkg 那部分）：

| 件 | 说明 | 必需 |
|---|---|---|
| `clang` / `gcc` | C / C++ 编译器 | ✅ |
| `lld` / `ld` | 链接器 | ✅ |
| `binutils` | `as` `objdump` `readelf` `ar` `nm` `strip` | ✅ |
| `make` | 构建调度 | ✅ |
| `cmake` | 另一套构建调度 | 建议 |
| **sysroot** | C 库头文件 + 静态库 | ✅ |
| `libc++` 头文件 | C++ 标准库头文件（不只是运行库） | ✅ |
| `python3` | 构建脚本常用 | ✅ |
| `pkg-config` | 找库 | 建议 |
| `gdb` | 调试器 | 可选 |
| `strace` / `ltrace` | 系统调用跟踪 | 可选 |

**注意两个容易漏的**：

1. **sysroot = 头文件 + 静态库**，不只是编译器。没有它编译出来的 `.so` 链接不上。
2. **`libc++` 的头文件**和 `libc++_shared.so` 是两回事 ——
   前者在 sysroot 里，后者在底座。

### 与已有底座的关系

```
usr/lib/toolchain/clang/…        编译器本体
usr/lib/toolchain/sysroot/…      头文件 + 静态库（编译时 -I / -L 指这里）
usr/include/ → sysroot 的软链     兼容 /usr/include 约定
usr/lib/libc++_shared.so          C++ 运行库（已有）
```

---

## 五、三个要你定的点

| # | 问题 | 我的倾向 |
|---|---|---|
| 1 | **默认带还是可选装** | 默认带 + 提供精简档开关（对齐 Linux 桌面档） |
| 2 | 用 **clang** 还是 `gcc` | clang。NDK 自带、许可证友好、默认 PIC，且我们已有 NDK 工具链 |
| 3 | sysroot **自带精简版**还是**完整 glibc** | 精简版（NDK 那套，Bionic ABI）。完整 glibc 太大且与 Android 不兼容 |

第 3 条理由同 C++ 运行库那条：**Bionic ABI 不兼容，带 glibc sysroot 会编出 Android 上跑不了的 `.so`。**

---

## 六、连带影响

加了开发环境后，这几件事要跟着做：

| # | 事 | 为什么 |
|---|---|---|
| 1 | `buildTier` 机制要支持「不进 APK」 | 现在的三档（`self-c` / `upstream` / `soft`）都是"编进 APK"，编译器这类是"随 APK 但体积大"，要一档说明 |
| 2 | **APK 体积会突破现有前提** | 当前 APK 27.5MB，且 components/native 的定位是"小体积原生能力件"。编译器 + sysroot 是它的十倍量级 |
| 2b | **两类原生件要分开** | native 类目 = 宿主零件（必须小、必须随 APK）；开发环境 = 工具链（可以大、可选装）。不能放进同一个类目 |
| 3 | 精简档要能真正不装 | APK 里带了就是带了，精简档只能"不装到 `usr/`"而不能瘦身 APK —— 这点要说清 |
| 4 | npm 现场编译要能跑通 | `npm` 已在底座，`node-gyp` 需要编译器 + python3，验证一次 |

第 3 条是个**要提前讲清的限制**：分档如果指的是"APK 体积不同"，
那就得做多个 APK 或用 split APK；如果只是"装不装到 `usr/`"，
一个 APK 就够（只是用户选择不装）。后者简单得多。
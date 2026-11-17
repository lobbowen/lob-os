# Agent OS 基础环境建设规划（照 Linux 标准）

本文定义 Agent OS 三层的落地规划。方向已定：bionic 一套 ABI（实现仓 78df5dca）。
库机制完全照 Linux 标准，依据 `01-linux-architect/LINUX-LIB-MECHANISM-RESEARCH.md` 的 12 问结论，
不自造机制。

---

## 1 产品定位

Agent OS = 为 agent 打造、生存在 Android 上的生态系统。不是桌面 Linux 移植，运行在电脑上不是目标。

三层结构：

| 层 | 定位 | 对应 Linux 概念 |
|---|---|---|
| 运行库 | 系统最底层核心，共享、按需、可热更新 | `/usr/lib` 的共享库 + SONAME 机制 |
| 功能组件 | 运行时（node/python/go）与工具（bash/git/curl），带库进来但库共享 | Debian/RPM 的包，依赖声明指向库包 |
| 应用程序 | 真正的客侧，开发者做的 Agent OS APP | 用户安装的应用程序包 |

核心机制：功能组件依赖库 -> 库落到系统共享区 -> 所有组件共享同一份 -> 库升级即系统级热更新。

---

## 1.5 现状盘点（设备实测，2026-11-18）：共享库底座是坏的

照 Linux 标准前，先看清现状。设备 `files/usr/lib` 盘点发现 SONAME 软链大量断裂：

| SONAME 链接 | 指向 | 状态 |
|---|---|---|
| libcurl.so / libcurl.so.4 | 互指对方 | 断（循环） |
| libssl.so / libssl.so.3 | 互指对方 | 断（循环） |
| libz.so.1 | 指向自己 | 断（自指） |
| libcrypto.so | 指向件内 libcrypto-lobos.so | 正常 |

而组件的 DT_NEEDED 明确要 libz.so.1、libssl.so.3、libcrypto.so 等：
- git 需要 libz.so.1
- openssl 需要 libssl-lobos.so / libcrypto-lobos.so
- node 需要 libc++_shared.so

**结论：运行库这一层当前是坏的 —— SONAME 软链没按 Linux 标准建（应指向实体库，
不是互指/自指）。这正是"先把基础环境搭好"要修的第一件事。**

Linux 标准（本规划第 2 节）：libfoo.so -> libfoo.so.N -> libfoo.so.N.X.Y（实体），
由 ldconfig 按 SONAME 自动建链和缓存。我们的 link-interpose/LdConfig 建链逻辑要照此修正。

---

## 2 照抄的 Linux 标准（不是我们发明的）

来自 LINUX-LIB-MECHANISM-RESEARCH.md 的 12 问结论：

1. 库放哪：系统共享库进 `/usr/lib`（我们的 `files/usr/lib`），启动/基础库进 `/lib`
2. 动态链接器查找顺序：DT_RPATH -> LD_LIBRARY_PATH -> DT_RUNPATH -> ld.so.cache -> 默认目录
3. `$ORIGIN`：只用于"可接受的自带库"场景，不作为系统共享库的主路径
4. SONAME：库身份由 SONAME 定（libfoo.so.1），三种文件名分工（libfoo.so / libfoo.so.1 / libfoo.so.1.2.3）
5. 符号版本化：ABI 兼容性的底层保障
6. 同名不同版本共存：SONAME 不同即可共存
7. 依赖声明：包声明依赖哪些库（Debian Depends / RPM Requires），可由 shlibs/symbols 自动派生
8. 库独立成包 vs vendored：系统库独立成包，私有库才随组件带（官方立场）
9. 安装顺序与原子性、回滚
10. 升级时旧库被删、在跑进程的处理
11. 两个包提供同一 SONAME 的裁决
12. multiarch 路径隔离（我们单架构 aarch64，暂不需要）

---

## 3 三层落地

### 3.1 运行库层（先做，是底座）

把当前散落在各组件里的系统库，抽成独立的"库包"，落到 `files/usr/lib`：

首批系统库（从当前设备 `files/usr/lib` 与件 lib 目录盘点）：
- C 运行库相关：libc++_shared.so（node 用）
- 加密：libssl.so.3 / libcrypto.so.3（SONAME 标准名）
- 压缩：libz.so.1
- 传输：libcurl.so.4
- 系统垫片：liblobosposix.so / librivospty.so（宿主能力，随 APK）
- 待补（agent 能力缺口）：libffi（python ctypes）、libsqlite3、libbz2、liblzma、libzstd（python 扩展模块依赖）

每个库包：
- 有 SONAME（标准命名 libfoo.so.N）
- 三个文件名齐（开发链接用 .so、SONAME 链接 .so.N、实体 .so.N.X.Y）
- 独立版本，可单独升级
- 升级走 ldconfig 重建软链 + 缓存（对应 Linux ldconfig 机制）

### 3.2 功能组件层

按类别梳理预装组件：

| 类别 | 组件 |
|---|---|
| shell/工具 | bash、busybox、git、curl、jq、make、cmake、pkgconf |
| 运行时 | node、python、go（待补） |
| 编译器链 | llvm、clang（能力件） |

每个组件：
- 声明依赖哪些系统库（用 SONAME，如 libz.so.1、libssl.so.3）
- 不再自带实体库副本（件内 lib/ 只放私有/vendored 库）
- 系统共享区的库通过 ldconfig 软链供给

### 3.3 应用程序层（最后）

Agent OS 上的 APP 由开发者完成，消费上面两层。当前系统管家 UI 不是这一层，是宿主管理界面。
这一层本次不展开，先把 1、2 层底座做扎实。

---

## 4 关键机制：库共享与热更新

照 Linux：
- 组件安装时：声明依赖 -> 库包就位到 `/usr/lib` -> ldconfig 建 SONAME 软链与缓存
- 组件运行：动态链接器按标准顺序找库 -> 命中 `/usr/lib` 共享库
- 库升级：新库以新实体版本落位 -> ldconfig 重建软链指向新版本 -> 所有组件下次启动用新库
- 回滚：保留旧版本实体 + 旧 SONAME 指针可切回

我们已有的雏形：`LdConfig.kt`（建软链）、`PrefixProvisioner.libDir`（共享目录）、件版本目录 + CURRENT 指针。
要在其上补齐：库独立成包、SONAME 三文件名、依赖声明用 SONAME、升级/回滚的 ldconfig 语义。

---

## 5 实施步骤（按依赖顺序）

1. 盘点与定清单：把当前系统库、组件依赖梳理成"库清单 + 组件依赖表"（只读盘点，产清单）
2. 库独立成包：把首批系统库做成独立库包，落 `/usr/lib`，SONAME 三文件名齐
3. 组件去重复库：组件件内不再带实体系统库，改为依赖声明
4. 补 agent 能力缺口库：libffi/libsqlite3/bz2/lzma/zstd（消除 python 7 模块缺失）
5. 升级/回滚机制：库版本可单独升级，ldconfig 重建软链，保留回滚基线
6. 判据补齐：件判据增加"依赖库就位 + 能力模块可用"检查，不许由"解释器起得来"顶替

---

## 6 边界

- ABI：bionic 一套，不做 ABI 切换
- 架构：单 aarch64，multiarch 路径隔离暂不做
- 应用程序层：本次不展开
- 不发明机制：每一条都能在 Linux research 的 12 问里找到对应标准

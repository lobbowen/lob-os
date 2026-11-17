# 剩余问题澄清

本文把此前笼统称为"剩余边界"的三条逐条查证，讲清各自到底是什么状态。
基于 2026-11-18 设备实测。此前说法有误的，一并纠正。

---

## 问题一：git 件不完整（此前低估为"边界"，实际是缺陷）

### 事实（设备实测）

- git 的 `--exec-path` 返回 `/home/runner/work/lob-os/lob-os/work/musl-git/out/libexec/git-core`
  —— 这是 CI 构建机路径，设备上不存在。
- git 的 template 目录同样是 `/home/runner/.../share/git-core/templates`，init 时报
  `warning: templates not found`。
- 设备上真实的子命令在 `files/opt/git/2.55.0/libexec/git-core/`（8 个）。
- 实际跑完整工作流（init/add/commit/log）：commit 阶段出现 `Bus error`。
- 用 `GIT_EXEC_PATH=<设备真实路径>` 覆盖后，`git --exec-path` 指向正确位置。

### 定性

这不是"边界限制"，是 git 件构建时把构建机绝对路径烘焙进了二进制，导致子命令与模板
查找失败。此前我说"git 主功能正常"是不准确的，在此纠正。

### 正确解法（未做）

件声明 env：运行时把 `GIT_EXEC_PATH` 与 template 目录指向件在设备上的真实位置
（由 RuntimeEnvironment 按件安装路径动态注入，不烘焙进二进制）。
这条符合 PIECE-SPEC 条款四（禁构建机绝对路径）与条款二（位置无关）。

---

## 问题二：shell 直连失败（此前报告，现已不存在，撤销）

此前说"从 adb shell 直连 musl 件因无 LD_LIBRARY_PATH 会失败"。
2026-11-18 复测：PT_INTERP 烘焙成设备真实路径后，从 adb shell 直接跑
`files/opt/bash/5.2.15/bin/bash -c "echo hi"` 成功，带不带 LD_LIBRARY_PATH 都是 `hi`。

原因：解释器已烘焙为 `/data/data/lobos.os/files/usr/lib/ld-musl-aarch64.so.1`，
内核直接按这个路径找到加载器，不再依赖调用方传环境。

此条撤销，不再是问题。此前报告基于烘焙解释器之前的旧状态，是过时的。

---

## 问题三：8 个能力件仍是 bionic（真实存在，但不是缺陷）

### 事实（设备实测，逐件读 INTERP 段）

以下件仍是 `/system/bin/linker64`（bionic）：
cmake、llvm、make、node、pkgconf、pnpm、python、sqlite

它们是"能力件"（开发工具/运行时），不在 9 件 musl 核心清单里。
9 件核心件（bash busybox crypto curl git jq openssl ripgrep zlib）已经是 musl 或静态。

### 定性

这是**范围问题，不是缺陷**：当前 ABI 迁移的范围定义为 9 件核心件，能力件按原计划
保持 bionic。这些能力件本身能正常工作（用系统 bionic linker）。

是否要把能力件也 musl 化，是一个独立的、范围更大的工作（Rust/LLVM/Node 等各自有
独立的构建链），不属于本次"核心件 ABI 迁移"的交付范围。此前把它们笼统说成
"混装"，容易误解为系统坏了 —— 实际是核心件已统一、能力件尚未纳入迁移范围。

---

## 汇总

| 项 | 此前说法 | 查证后真相 |
|---|---|---|
| git exec-path | "边界限制，主功能正常" | 实际是缺陷：构建机路径烘焙，commit 会 Bus error，需 env 注入修复 |
| shell 直连 | "会失败，是红线必然" | 已不存在：解释器烘焙后直接执行成功，撤销此说法 |
| 能力件 bionic | "混装持续" | 范围问题：8 个能力件未纳入本次迁移，本身工作正常，核心件已全 musl |

真正还需要动手的只有问题一（git env 注入）。问题二已消失，问题三是明确的范围边界。

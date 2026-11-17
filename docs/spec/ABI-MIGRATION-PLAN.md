# ABI 迁移实施规范

本文定义如何把系统从 bionic 件迁移到我们自带 musl 体系。基于标准系统逻辑推导，不发明机制。

---

## 1 现状（设备实测，2026-11-17）

```
bash / git / jq 的解释器 = /system/bin/linker64   （bionic）
我们编的 14 个 musl 件 = 躺在 work/，系统从不用
```

三套并行体系：

| 体系 | 构建 | 存储 | 使用方 |
|---|---|---|---|
| bionic 件 | 9 个 CI workflow（locate-ndk） | Release tag `base-<tool>` → APK assets/pieces | 系统预装（在用） |
| musl 件 | 本地 build-musl-piece.sh | work/ | 无 |
| musl 底座 | musl-runtime-assets.sh | APK assets/musl | 还没被件用 |

---

## 2 标准逻辑推导（为什么这么做）

1. **一套系统只能一套 ABI** —— bionic/musl 不能混用（ELF 解释器与 C 库不兼容）。
2. **ABI 切换是发行版级操作** —— 标准做法是用**新版本号/新发布通道**整体切换，
   旧 ABI 作为回滚基线保留，不搞逐件混装。
3. **包数据库是唯一真相源** —— 装了什么由注册表决定，不是文件系统目录（见 STATE-MODEL）。
4. **构建标识（tag/asset）必须区分 ABI** —— 同名同版本换字节会被闸门判红（我们踩过），
   所以不同 ABI 必须不同 tag。

---

## 3 目标形态

- 系统预装的件 = musl 件（自带加载器，不依赖 /system/bin/linker64）
- musl 加载器与 libc 随 APK 分发（第一步已完成：assets/musl/）
- bionic 件仍保留为回滚基线（独立 tag `base-*`），不删除
- 切换原子：要么全 musl，要么全 bionic，不混装
- 可回滚：出问题切回 bionic tag

---

## 4 实施步骤（按依赖顺序）

### 步骤 1（已完成）：musl 底座随 APK 分发
`scripts/toolchain/musl-runtime-assets.sh` → assets/musl/
CI 验证通过（run 44052647023，加载器 723480 字节）。

### 步骤 2：musl 件有独立的构建标识

给 cache-key.sh 加 ABI 维度（环境变量 `LOBOS_ABI=musl`）：
- tag：`base-<tool>` → `musl-<tool>`（不同 Release，物理隔离，不触发"同名换字节"闸门）
- asset：带 `-musl` 标识（与 bionic asset 区分）
- 复用逻辑：各自 tag 内复用，不串

### 步骤 3：musl 件进 CI 构建

9 个必备/能力件的 workflow 支持 musl 模式：
- 复用现有 workflow 结构，构建脚本从 bionic 改为 `build-musl-piece.sh`
- 6 个必备件（bash/busybox/crypto/curl/openssl/zlib）已编好，直接固化
- git/jq 已编好，可直接固化
- ripgrep（Rust）待打通 musl target，最后一个补
- 固化到 `musl-<tool>` tag 的 Release

### 步骤 4：build-apk 切换到 musl 件

- `fetch-prebuilt-pieces.sh` 支持 `--abi=musl`：从 `musl-<tool>` tag 取件
- build-apk.yml 用它取 musl 件，替换 assets/pieces/
- 同时装配器把 assets/musl/ 的加载器与 libc 装到 files/usr/lib/

### 步骤 5：件的解释器指向我们的加载器

当前 musl 件的解释器是 `/lib/ld-musl-aarch64.so.1`（设备上不存在）。
标准做法：构建时用 `--dynamic-linker` 烘焙成 app 可写路径
`<filesDir>/usr/lib/ld-musl-aarch64.so.1`，件直接可执行，不靠环境变量。
这一步要求重编件（在步骤 3 里一并做）。

### 步骤 6：设备验证

- 抽查件解释器不再是 /system/bin/linker64
- OsPhase=RUNNING、UnitState 全正常
- 启停、终端、装件能力可用
- 确认无回归后，bionic 件保留为回滚基线

---

## 5 风险与对策

| 风险 | 对策 |
|---|---|
| 同名同版本换字节闸门误判 | musl/bionic 不同 tag（步骤 2） |
| 批量构建慢且偶发 x86（busybox 竞态） | 串行编译 + 架构断言；CI 里逐个验证 |
| ripgrep Rust 工具链下载慢 | 单独后台下，最后补；不阻塞前 8 件 |
| 切换后系统起不来 | bionic 保留为 FLOOR，可回滚；先在测试设备验证 |
| 混装 | 切换是整体动作，build-apk 只取一个 ABI 的件 |

---

## 6 当前进度

- [x] 步骤 1：musl 底座随 APK 分发（CI 验证通过）
- [ ] 步骤 2：构建标识加 ABI 维度
- [ ] 步骤 3：musl 件进 CI 构建（8 件已编，ripgrep 待补）
- [ ] 步骤 4：build-apk 切 musl 件
- [ ] 步骤 5：解释器烘焙
- [ ] 步骤 6：设备验证

---

## 7 结论

这不是"再修一个界面"或"再补一个件"，而是**构建体系的整体迁移**：
20 个 workflow、4 个核心脚本、build-apk 取件链路。
按上面 6 步走，每步可独立验证，失败可回滚到 bionic。

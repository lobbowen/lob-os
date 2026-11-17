# 当前架构结论与下一步

本文回答一个问题：**状态梳理完之后，下一步到底干什么。**
结论基于设备实测与代码事实，不基于愿望。

---

## 1 一个必须先面对的事实：两条线没有接起来

设备实测（2026-11-17）：

```
bash / git / jq 的解释器 = /system/bin/linker64   （bionic，系统自带）
```

而我们编了 14 个 musl 件，全在 `work/component-*-musl-*.zip`，**系统从未使用**。

也就是说，当前有两条互不相连的工作线：

| 线 | 内容 | 谁在用 |
|---|---|---|
| **线 A（在跑的系统）** | bionic 件，从 GitHub Release 下载 → 装进 APK `assets/pieces/` → 预装 | 真正在设备上运行 |
| **线 B（编了没用）** | 14 个 musl 件，自带加载器与 sysroot | 躺在 work/，系统不知道 |

**状态、界面、启动逻辑这一轮修的所有东西，服务的都是线 A。线 B 编了等于没编。**

所以"做完"的定义不是"再修状态"，而是：**让系统真正运行在我们自己编的 musl 体系上**。在那之前，这整套产品实际上还是 bionic 的，选 B（自带 libc/加载器）只是纸面决定。

---

## 2 为什么不能简单"把 musl 件塞进 assets/pieces 替换"

硬约束（标准 ELF 常识，不是我们发明的）：

- bionic 件：解释器 `/system/bin/linker64`，C 库 `/system/lib64/libc.so` —— **系统自带**
- musl 件：解释器 `ld-musl-aarch64.so.1`，C 库 `libc.musl-aarch64.so.1` —— **必须随系统分发**

现有 APK 里的 `assets/sysroot` 是 **Android NDK 的 bionic sysroot**（编译期材料），不是 musl 的。

因此 ABI 切换不是"换几个 zip"，而是同时满足：

1. musl 加载器与 sysroot 随 APK 分发，并在件之前就位
2. musl 件的解释器指向我们自己的加载器（烘焙绝对路径，或由加载器前置启动）
3. 切换按依赖顺序进行（库先于消费者），且失败可回滚（bionic 件作为 FLOOR 保留）
4. 切换后所有件能被系统识别、状态正常、能力可用

---

## 3 现有可复用的机制（不要重造）

我们已经有、且经过设备验证的机制：

| 机制 | 位置 | 可复用方式 |
|---|---|---|
| 件版本目录 + CURRENT 指针 | `opt/<id>/<版本>/` + CURRENT | 同一件多版本共存，切 CURRENT 即切换 |
| 预装器 | `PreinstalledPieces.installAll/installOneById` | 从 assets 取件 zip 并安装登记 |
| 对账器 | `BootReconciler.run` | 开机时校正状态、清旧版本 |
| 状态判定 | `ProgramStatusHub` / `UnitState` / `OsPhase` | 切换后用真实状态验证成功 |
| libstdc++/musl 工具链 | `scripts/toolchain/musl-*` | 编件与未来编加载器 |
| 启动能力 | `OsHostService` 的 START/STOP_RUNTIME | 切换后验证系统仍可启停 |

**不需要造新的安装机制**。需要的是一条"musl 件随 APK 分发 + 开机切换到 musl"的规则。

---

## 4 下一步（按依赖顺序）

### 第一步：让 musl 加载器与 sysroot 随 APK 分发

- 把 musl 的 `ld-musl-aarch64.so.1` 与最小 sysroot（lib + include）作为**编译期材料**放进 APK assets
  （与现有 bionic sysroot 并列，专门命名如 `assets/musl-sysroot/`）
- 这是"系统底座"类产出，随 APK 内置，不是件（见 ARTIFACT-CLASSIFICATION）

### 第二步：把 9 个必备 musl 件纳入 APK 分发

`PIECES` 数组对应的 9 件（bash/busybox/crypto/curl/git/jq/openssl/ripgrep/zlib）：
- 当前 8 个已编好（busybox 刚补上，串行编译修了竞态）
- ripgrep 是 Rust 件，需单独打通构建（最后补）
- 让 build-apk 使用这些 musl 件，而不是从 Release 取 bionic 件

### 第三步：设计并实现 ABI 切换规则

- 新装 musl 件与旧 bionic 件共存于不同版本目录
- 通过后继启动把 CURRENT 指向 musl 版本
- bionic 版本保留为 FLOOR（回滚基线），不立即删
- 切换后验证所有件的加载器/依赖解析正确

### 第四步：设备验证

- 抽查 bash/git/jq 的解释器不再是 `/system/bin/linker64`
- 系统状态（OsPhase/UnitState）正常
- 启停、终端、装件能力可用
- 确认无回归后再清理旧 bionic 件

---

## 5 结论

**下一步不是继续打磨界面，也不是继续零散补件，而是执行上面四步——把"编出来的 musl 体系"变成"系统实际运行的体系"。**

这是选 B 的兑现，也是整个产品从"一堆工具"变成"一套独立系统"的关键一步。
在这之前，状态、界面、启动逻辑都只是让线 A（bionic）更好看，没有触及线 B（musl）。

---

## 6 已知缺口（不影响方向，但要记）

- ripgrep（Rust 件）的 musl 构建还没打通，是 9 件里唯一缺的
- busybox 构建曾有 `-j` 并行竞态（时编成 x86），已改串行;批量编件时要盯架构断言
- ABI 切换的"回滚基线"策略（保留几代 bionic）需要在第三步定细则

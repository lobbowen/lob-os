# LobOS 基础能力缺口清单

目标：Android 上全量级类 Linux 系统，比 Linux 更轻、更稳、性能更好，
具备 Linux 全部基础能力。本清单记录实证发现的能力缺口，持续更新。

## 调查方法
以真实命令在真机终端 UI 运行 → 抓全报错 → 逐层定位（应用层/系统层/内核层）。

---

## 2026-11-24：`dsh web` 启动失败

### 现象（真机终端 UI 实证）
`dsh web` → `dsh: fatal uncaught exception: host preparation failed:
No usable native binding found for node-addon-require-builtin-android-arm64 (auto)`

### 完整报错的三条加载路径全失败
1. optional-package：`node-addon-require-builtin-android-arm64` 不存在
2. local-build：`build/nodeabi/node-v137-android-arm64/require_builtin.node` 不存在
3. local-build：`build/napi/napi-v9-android-arm64/require_builtin.node` 不存在

### 分层定性
- **这是应用层兼容性问题，不是 Linux 系统基础能力缺失。**
  NARB（node-addon-require-builtin）是 Dsh 私有模块，用于 require Node 内部模块，
  标准 Linux 上没有这个东西。它的原生二进制官方只发 darwin/win32/linux glibc|musl，
  没发 android-arm64；发布版 npm 包故意不带源码、fail closed（README 明说）。
- Dsh 官方 Android kernel 项目（dsh-mob/dsh-android-kernel）对此有专门适配层：
  `src/guard/native/require-builtin-shim.js`（JS 垫片，spawn 前由 guard 幂等投放），
  以及 flock-shim（对应真系统能力 flock(2)，走 APK 的 libdshflock.so）。
- LobOS 当前是**裸跑 Dsh**（终端直接 `dsh web`），绕过了官方 Android kernel guard，
  所以没有这些适配，原生模块加载失败。

### 系统侧已具备的能力
- `liblobosflock.so`（真 flock(2) 的 native bridge）已在 APK + `files/usr/lib`，
  但 `DSH_FLOCK_NATIVE` 环境变量没注入——**能力在，没暴露给应用**。
- clang/sysroot/node-gyp 编译能力已具备（前序工作），但 NARB 上游不给源码，
  无法在设备上编译 —— 这不是我们环境的问题。

### 结论与方向（待决策）
两条路：
A. 走 Dsh 官方 Android kernel guard/supervisor 启动（它负责投放 shim + 注入契约）——
   代价是引入一个 Node 常驻 supervisor，与 LobOS 自身进程管理可能重叠。
B. LobOS 侧在启动 Dsh 时提供"容器契约"（runtime.json + DSH_* 环境变量 + shim 自愈）——
   等价实现 guard 的职责，但不引入完整 supervisor。

这与用户"不打补丁、要架构级"的要求有张力：NARB 的 JS 垫片本质是官方的兼容性适配，
不是我们发明的补丁。需要用户裁定方向后再实施。

---

## 追加实证（2026-11-24 第二轮）：打通 NARB 的完整条件

对 `dsh web` 的 NARB 缺口做了逐层实验，确认完整启动链需要的两项适配：

### 实验 1：投放官方 NARB shim
把 `dsh-mob/dsh-android-kernel/src/guard/native/require-builtin-shim.js`
用其 `ensureShim(root)` 投放到设备的 Dsh 安装树（status: applied）。
报错从"无原生绑定"前进到下一层，证明 shim 生效。

### 实验 2：shim 需要 --expose-internals
投放 shim 后报：`narb-js-shim: node started without --expose-internals,
cannot load internal module internal/modules/esm/loader`。
在 NODE_OPTIONS 里加 `--expose-internals` 后，`dsh web --port 9110`
运行 15 秒无错误输出、被 timeout 以 SIGTERM(EXIT=9) 终止 —— 说明 host preparation
阶段已通过，不再有原生模块/内部模块加载失败。

### 结论：NARB 缺口的完整补齐条件（均为 Dsh 官方 Android 契约）
1. NARB JS shim 投放（官方 guard 在 spawn 前幂等执行）
2. node 启动带 --expose-internals（NODE_OPTIONS 或启动参数）

这两项都不是补丁式临时处理，而是 dsh-mob 容器引擎 boot.js 明确注入的
"容器契约"的一部分（见 container-engine/src/boot.js、runtime-contract.js）。

### 仍未验证 / 后续
- dsh web 常驻 HTTP 服务未验证到返回响应（timeout 杀进程后端口未留）。
  可能还需要 profile 配置（dsh web 默认 profile 在设备上不存在）。
  这属于 Dsh 应用层配置，不是系统基础能力。
- DSH_FLOCK_NATIVE 环境变量仍未注入——liblobosflock.so 在设备上，
  但没暴露给应用，是明确的系统能力暴露缺口。

---

## 2026-11-24 第三轮：Dsh 依赖树原生模块全景盘点（只读实证）

目标是"知道环境缺什么"。把 Dsh 依赖树里全部原生模块盘一遍，按缺口性质分三类。

### 全景数据（设备上 find/grep 实测）
已产出 .node 的节点：
- koffi：build/koffi/android_arm64/koffi.node（我们设备编译的，可用）
- node-pty：build/Release/pty.node（有效 aarch64 ET_DYN ELF，我们编译的）
- node-pty：prebuilds 有 darwin/win32/linux-arm64/linux-x64，**无 android**
- frame-forwarding（libreoffice-kit）：prebuilds 只有 darwin/win32，**无 android/linux**

带源码可编译的（binding.gyp / src/*.c）：
- node-pty（binding.gyp）、sharp（binding.gyp）
- @deepseek-ai/node-addon-system：src/flock.c + src/main.c（真系统能力：flock/landlock）

无源码、fail-closed 的家族：
- node-addon-require-builtin（NARB）+ node-addon-native-custom-loader
- 官方只发 darwin/win32/linux glibc|musl，发布版不带源码

### 三类缺口定性
1. **有源码、可设备编译**：node-pty/sharp/node-addon-system。
   这类是方案A的直接对象——clang/sysroot/node-gyp 已具备，能产出 android-arm64 件。
   node-addon-system README 明确区分 glibc/musl 平台包，印证 ABI 差异真实存在。
2. **有预编译但无 android**：frame-forwarding（libreoffice-kit）。
   只能靠上游发 android 件，或它本身是可选功能（libreoffice 在 Android 上大概率不适用）。
3. **无源码 fail-closed**：NARB。上游设计就是 JS shim 兜底，
   属于"应用 Android 适配"，不是系统能力缺失。

### 系统侧明确缺口（能力在但没暴露）
- liblobosflock.so 已在 APK + files/usr/lib（12KB，真 flock(2) bridge），
  但 DSH_FLOCK_NATIVE 等环境变量未注入，node-addon-system 不知道去哪找。
- 这与 NARB 不同：flock 是真 POSIX 基础能力，补齐它属于系统职责。

### 结论
"dsh web 起不来"是三类缺口的叠加，其中：
- 真系统能力缺口：DSH_FLOCK_NATIVE 未注入（1 项，明确、可补）
- 可编译供给缺口：几个原生模块需设备编译（能力具备，缺的是系统化供给）
- 应用适配缺口：NARB（官方 shim，非系统职责）

兼容层方案A的覆盖面由此确定：优先解决"有源码可编译"这一类，
它们既有真实系统能力（flock）又有现成编译链路，投入产出比最高。

---

## 2026-11-24 第四轮：DSH_FLOCK_NATIVE 缺口的补齐方案（只读实证）

### 决定性发现：能力完全兼容，只差一个环境变量
把 Dsh 官方 `native/flock/flock.c` 与 LobOS `container/native/d2/flock.c` 逐行对照：
- 两者同源（差异仅 20 行 provenance/注释）
- 都导出 N-API 标准入口 `napi_register_module_v1`
- 都 `napi_set_named_property(exports, "tryLock", ...)`
- LobOS 编出的 liblobosflock.so 实测含 `tryLock`/`try_lock` 字符串

Dsh 官方 flock-shim.js 的加载逻辑（第 39-51 行）：
```
p = process.env.DSH_FLOCK_NATIVE
nativeState = createRequire(import.meta.url)(p)
if (nativeState.tryLock is function) → 走真 flock(2)
```
即：它 dlopen 环境变量指向的 .so，期待导出 tryLock —— 与我们的库逐字匹配。

### 结论
flock 系统能力缺口的补齐 = **注入一个环境变量**：
```
DSH_FLOCK_NATIVE=/data/user/0/lobos.os/files/usr/lib/liblobosflock.so
```
不需要重编任何 native 库、不需要改 Dsh、不需要补丁。
这是"能力已在、暴露缺失"的最典型案例，也是兼容契约层最该做的第一件事：
把系统已有的能力，用应用期待的环境变量契约递过去。

### 对兼容层设计的意义
这一个实例验证了方案 A 的核心假设：
"系统能力供给"这件事，在架构上可以收敛成一份"应用→环境变量/原生件"的契约注入。
flock 这条契约零成本（已有库+一个变量），是第一步落地的最佳样例。

---

## 2026-11-24 第五轮：DSH_FLOCK_NATIVE 补齐方案终极实测（修正一处推断）

### 修正
上一轮说"用 createRequire 加载 .so"是推断，不完整。实测发现：
- `require('/.../liblobosflock.so')` → Invalid or unexpected token
  （node 的 require 按扩展名判断，.so 被当 JS 解析）
- 但 Dsh 官方 flock-shim.js:39-51 对非 .js 路径用的是 **`process.dlopen(mod, p)`**，
  它不依赖扩展名约定

### 终极实测（设备真机）
```
process.dlopen({exports:{}}, '/data/user/0/lobos.os/files/usr/lib/liblobosflock.so')
→ DLOPEN_OK tryLock function
→ 调用 tryLock(fd, cb) → TRYLOCK_CB ERR=0   (errno 0 = 加锁成功)
```
即：我们的 liblobosflock.so 用官方 shim 的加载路径能真正完成 flock(2) 排他锁。

### 结论（升级为实证）
flock 系统能力缺口补齐 = 注入 `DSH_FLOCK_NATIVE` 指向我们的 .so（路径原样，
无需改成 .node），官方 shim 用 process.dlopen 加载、tryLock 实测可用。
这是整个排查里第一个"零改动、纯环境注入"即可补齐的系统能力缺口，
是兼容契约层第一步落地的最佳最小样例。

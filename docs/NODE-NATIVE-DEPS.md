# node 原生依赖：为什么裸环境启动必然失败

## 现象

商店供给装好后，任何用 `ProcessBuilder` 起 node 而不设 `LD_LIBRARY_PATH` 的地方，
都会在 **linker 阶段**失败，node 根本没启动：

```
F linker: CANNOT LINK EXECUTABLE "/data/user/0/lobos.os/files/usr/bin/node":
cannot locate symbol "_ZTVNSt6__ndk119basic_ostringstreamIcNS_11char_traitsIcENS_9allocatorIcEEEE"
referenced by ".../files/usr/lib/toolchain/node/bin/node"
```

这个失败发生在动态链接期，**node 的 stderr 抓不到任何东西**（诊断里表现为
「node 标准错误(完整)」为空），所以现场看起来像「进程莫名退出了」。

## 根因（实测 ELF 动态段得出，非推断）

设备上的 node 二进制动态段：

```
RUNPATH = "$ORIGIN"
NEEDED  = libm.so, libdl.so, liblog.so, libc++_shared.so, libc.so
```

`$ORIGIN` = node 二进制**自身所在目录**。而商店供给把 node 放在：

```
files/usr/lib/toolchain/node/bin/node
```

`$ORIGIN` 就是 `toolchain/node/bin/`。而 `libc++_shared.so` 在：

| 位置 | 来源 |
|---|---|
| `files/usr/bin/libc++_shared.so` | `PrefixProvisioner` 从 `nativeLibraryDir` 拷来 |
| `<APK>/lib/arm64/libc++_shared.so` | APK 原生库目录，`NativePreparer.libSearchPath()` 指向这里 |

**两处都不是 `toolchain/node/bin/`。** `$ORIGIN` RUNPATH 找不到，node 起不来。

## 设备上实测的对照

| 命令 | 结果 |
|---|---|
| `node -e '...'` 裸跑 | `CANNOT LINK EXECUTABLE` |
| `LD_LIBRARY_PATH=<nativeLibraryDir>` | 正常 |
| `LD_LIBRARY_PATH=files/usr/bin` | 正常 |
| 两者都给 | 正常 |
| 两个目录里 node 都可读 `.so` | 是（`head -c 4` 验证） |

说明 `LD_LIBRARY_PATH` 本身是有效开关，但**它只在进程环境里生效**，
任何忘了传 env 的 spawn 点都会挂。

## 系统原本的设计假设（不成立）

`NativePreparer` 的诊断文案写着：

```
依赖解析方式=二进制自带 $ORIGIN RUNPATH；探针裸环境跑，不设 LD_LIBRARY_PATH
```

这个假设对 `bash` / `rg` 成立（它们自带能生效的 `$ORIGIN`），
**对 node 不成立**。所以问题不会在 bash/rg 上暴露，只在 node 上暴露。

## 修法

**主修**：`PrefixProvisioner.placeNodeDeps()` 把 `libc++_shared.so` 复制到
node 二进制所在目录（`$ORIGIN`），让 `$ORIGIN` RUNPATH 真的成立。
这样不依赖 `LD_LIBRARY_PATH` 是否被传进子进程 —— 从根上消除这类失败。

**辅修**：`NodeRuntime.version()` 的 `ProcessBuilder` 补 `LD_LIBRARY_PATH`。
没有它，取版本号必然拿到空串，诊断里显示为「Node 运行时版本=」空白。

## 取证手段

`runtime.json` 升到 **schema 3**，新增 `env` 字段，落地进程实际拿到的
环境变量（含 `LD_LIBRARY_PATH`）。出问题时直接读这个文件，
不用再靠对照实验反推 env 有没有传进去。

契约同步在 `container/engine/src/runtime-json.js`（`SCHEMA = 3`）。

## 门禁

`tools/check-node-native-deps.js` 钉死四条：

1. `placeNodeDeps` 必须在，`NODE_DEPS_NAME` 常量必须在
2. `expected()` 必须把 `NODE_DEPS_NAME` 算作应有件（否则永远判缺件）
3. `NodeRuntime.version()` 必须设 `LD_LIBRARY_PATH`
4. `runtime-json.js` 的 `SCHEMA` 必须是 3（否则无从取证 env）

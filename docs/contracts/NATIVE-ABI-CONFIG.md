# Native ABI 契约：Kotlin ↔ C 环境变量

这是 LobOS 系统层（Kotlin）与 native 垫片（liblobosposix.so / loader）之间的
隐式接口。两端通过环境变量传递配置——它不是类型化的 API，所以必须用本文档
钉死，并由 `scripts/verify/verify-native-config.sh` 机械校验三处一致。

## 契约变量（12 个，Kotlin 写 → C 读）

| 变量 | 生产者(Kotlin) | 消费者(C) | 用途 | 缺失行为 |
|---|---|---|---|---|
| `LOBOS_VROOT` | RuntimeEnvironment | vfs-root.c | 虚拟根（系统盘）路径 | 路径翻译停用 |
| `LOBOS_SYSENV` | SysEnv | exec-path.c | lobos-sysenv 键清单文件 | 用默认路径 |
| `LOBOS_HOOK_SO` | RuntimeEnvironment | exec-path.c | bionic LD_PRELOAD hook so | 不注入 hook |
| `LOBOS_PTY_SHELL` | RuntimeEnvironment | exec-path.c | hooked bash 路径 | 不设 SHELL |
| `LOBOS_GLIBC_LOADER` | RuntimeEnvironment | exec-path.c | glibc guest loader | 走默认 |
| `LOBOS_GLIBC_LIBDIR` | RuntimeEnvironment | exec-path.c | glibc 库目录 | 走默认 |
| `LOBOS_GUEST_SIGSYS` | RuntimeEnvironment | exec-path.c | SIGSYS handler so | 不注入 |
| `LOBOS_MUSL_LOADER` | RuntimeEnvironment | exec-path.c | musl loader | 走默认 |
| `LOBOS_MUSL_LIBDIR` | RuntimeEnvironment | exec-path.c | musl 库目录 | 走默认 |
| `LOBOS_COMPAT_LOG` | RuntimeEnvironment | compat-report.h | 降级日志路径 | 不记日志 |
| `LOBOS_OWN_SESSION` | Kotlin 会话层 | link-interpose.c | 本会话标识 | link 降级为 copy |

另：`MUSL_ON_TERMUX=1`（patched musl loader 的 DNS 行为开关），由 Kotlin 写、musl loader 读。

## 规则

1. **契约变量必须三处一致**：本表 + Kotlin 写入点 + C `getenv` 点。
2. 新增/删除/改名必须同时更新三处，并跑 `verify-native-config.sh`。
3. 消费者必须容忍缺失（静默降级），不得因单个 env 缺失而崩溃。
4. 纯 Kotlin 内部变量（LOBOS_PROGRAM_ID/SESSION_TOKEN/PLATFORM 等）不进本契约。

## 已知问题（如实记录）

- 这些变量无类型、无 schema，靠约定；`verify-native-config.sh` 只能校验键名一致，
  不能校验值语义。
- `LOBOS_OWN_SESSION` 用于 link-interpose 的行为切换，是控制位而非路径，
  与其他键性质不同。

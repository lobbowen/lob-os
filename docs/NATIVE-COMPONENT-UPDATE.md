# 底座件：原生打包 + 可 OTA 更新

已确认的方向：
- 底座件**原生打进 APK**，不在发布侧商店呈现
- 但底座件**有版本信息**，可通过 **OTA 更新**，走**统一安装管理器**
- 原件保留作回退基线，更新落到别处

---

## 一、要解决什么

现状核实（`NativeExecutable` 的真实字段）：

```
class NativeExecutable(
    id, libName, humanName, probeArgs, probeExpect,
    requiredDeps, required, note, buildTier
)
```

**没有 `version`，没有 `sha256`。**

而 `PrefixProvisioner.expected()` 只返回**名字列表**：

```kotlin
fun expected(ctx: Context): List<String> =
    BINS.map { it.second } + LIBS.map { it.second } + CA_BUNDLE_NAME + …
```

`NativePreparer.prepare()` 只做**校验**（在不在、依赖齐不齐），**没有更新路径**。

所以三件事都不成立：

| 你要的 | 现状 |
|---|---|
| 底座件有版本信息 | ❌ 只有名字 |
| 底座件走 OTA 更新 | ❌ 只校验不更新 |
| 走统一安装管理器 | ❌ 不经 `ProgramInstallPipeline` |

---

## 二、更新分流

| 更新类型 | 载体 | 走哪条路 | APK 要不要动 |
|---|---|---|---|
| 系统件补丁（`bash` `libssl` `busybox` 换版本） | **OTA** | 安装管理器（`kind=native`） | **不动** |
| 架构级调整（协议、布局、新能力） | **APK** | 用户装新包 | 动 |

**不能每次改系统都重发 APK。** 这条是你定的。

---

## 三、落位规则（原件保留，更新到别处）

```
APK 内：  lib/arm64/libbash.so         ← 原件，保留，回退基线
usr/lib/toolchain/bash/<version>/     ← OTA 更新落这里
usr/bin/bash → 指向 toolchain 的那个  ← 全局入口，优先指向新版本
```

三者的关系，对应 Linux 的"包管理器管的包 vs 发行版自带的包分离"：

- 原件在 APK 里，**永远不动** → 可回滚
- OTA 更新落到 `usr/lib/toolchain/` → 不污染 APK
- `usr/bin/<name>` 软链**优先指向已更新的版本** → 装完即生效

回滚 = 软链指回 APK 原件。不需要重新下载。

---

## 四、要建的三件事

### 4.1 `NativeExecutable` 加版本与哈希

```
class NativeExecutable(
    …
    val version: String = "",        // 如 "5.2.21"；空表示"随 APK、不单独更新"
    val sha256: String = "",         // 原件哈希，用于判断 APK 里这份是不是被换过
)
```

版本号由构建时钉死（同 `component` 的 `buildTimeEpoch` 做法），
不进 APK 的就留空——留空表示"不参与 OTA，只用 APK 那份"。

### 4.2 原生件清单走 OTA

新增一份 `native-manifest.json`，与商店目录**并列但分流**：

```json
{
  "schema": 1,
  "components": [
    { "id": "bash",        "version": "5.2.21",  "url": "…", "sha256": "…", "entry": "bash" },
    { "id": "libssl",      "version": "3.6.3",   "url": "…", "sha256": "…", "entry": "lib/libssl.so" },
    { "id": "busybox",     "version": "1.37.0",  "url": "…", "sha256": "…", "entry": "busybox" }
  ]
}
```

**与商店目录的区别**：商店目录的件落到 `programs/<id>/<version>/`；
原生件落到 `usr/lib/toolchain/<id>/<version>/`，且 `usr/bin` 建全局软链。

启动时比对 `NativeAssetRegistry` 声明的版本 vs 清单版本，有新版就更新。
（具体触发时机跟程序 OTA 一样，看 `ProgramOtaUpdater` 的 `autoCheck`。）

### 4.3 安装管理器支持 `kind=native`

`ProgramInstallPipeline` 现在只处理商店件。原生件落位规则不同：

| | 商店件 | 原生件（底座） |
|---|---|---|
| 落位 | `programs/<id>/<version>/` | `usr/lib/toolchain/<id>/<version>/` |
| 入口 | `usr/bin/<name>` 软链 | 同 |
| APK 原件 | 无 | **保留不动**，作回退基线 |
| 回滚 | `ProgramDir` 指针 | 软链指回 APK 原件 |
| 升级语义 | 普通程序升级 | **不升 APK**，只换文件 |

**同一套安装器，两种落位策略。** 这正是"两条路一个安装点"要表达的。

---

## 五、校验判据

每件底座件要有可执行的判据（现在 `NativeExecutable` 已有 `probeArgs` / `probeExpect`，
沿用，补上版本比对的输出）：

```
bash     → bash -c 'exit 0'                       （已在用）
rg       → rg --version                            （已在用）
busybox  → busybox | head -1                      （新增）
jq       → jq --version                            （新增）
libc++   → 库文件存在 + sha256 匹配                （已有 verifyInternal）
libssl   → 同上
```

`NativePreparer` 的输出要加一行「版本 X ≠ 清单版本 Y，建议更新」。

---

## 六、实施顺序

| 序 | 内容 | 依赖 |
|---|---|---|
| 1 | `NativeExecutable` 加 `version` / `sha256` | 无 |
| 2 | 底座清单纳入 jq/busybox/libssl/libz/libpcre2-8/libiconv/libcurl 的声明 | 1 |
| 3 | `native-manifest.json` 结构 + 发布侧生成 | 1、2 |
| 4 | `ProgramInstallPipeline` 加 `kind=native` 落位分支 | 3 |
| 5 | 启动时版本比对 + 更新触发 | 4 |
| 6 | 回滚（软链指回 APK 原件） | 4 |

**1-2 是纯声明，先做；3-6 需要发布侧配合。**

---

## 七、与其他两条路的关系

```
                    ┌─────────────────────────┐
商店目录（在线）     │  商店程序件             │──┐
native-manifest     │  底座件（系统件补丁）    │──┤
（OTA，在线）        └─────────────────────────┘  │
                                                  ├→ ProgramInstallPipeline（唯一安装点）
                    ┌─────────────────────────┐  │
APK assets（离线）   │  程序自身的静态资源      │──┘
                    └─────────────────────────┘
```

三条来源，**一个安装点**，落位策略按 `kind` 分流。

详见 `docs/INSTALL-CHANNEL.md`（安装通道）与 `docs/BASE-ENV-DESIGN.md`（底座构成）。
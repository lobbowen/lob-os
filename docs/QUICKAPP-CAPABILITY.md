# 能力边界与后端联动

> 配套：`QUICKAPP-INSTALL.md`（包结构）、`QUICKAPP-ICON.md`（桌面入口）、`QUICKAPP-FRAMEWORK.md`（共识）。
> 本文记阶段 B 与 C 的落地形态与**查证过的边界**。

---

## 1. 后端联动：端口怎么从系统流到两端

```
安装器落定 → bindQuickApp()
   ├─ PortBroker.claim(id)            段 41000-50999，已领过返回原端口（升级不变）
   ├─ 写 frontend/config.json          {"hostManaged":true,"backend":{"endpoint":"http://127.0.0.1:<port>"}}
   └─ QuickAppRegistry.register(id)    写注册表 uiPackage/uiName/uiIcon/httpPort
                                          ↓
进程启动 → InstanceHost:282
   resolveHttpPort(id, spec.http.port) → claimed>0 则用它，否则 claim
   ↓
GuestAdapter:68
   put(envName, port)                  清单 http.env（快应用固定写 "PORT"）
```

**两端拿到同一个端口，且升级时端口不变** —— 因为注册表与 `config.json` 都写着它。

### 1.0 两处查证出来的真缺陷

**其一：段名不一致让端口注入静默失效。**

`ProgramRegistry` 原先读的是 `json.optJSONObject("ports")?.optJSONObject("http")`，
而 `ManifestSchema` 校验的是 `http` 段、我们的测试包写的也是 `http`。

**两处段名不一致 ⇒ 声明 `http.env` 的程序永远拿不到环境变量注入。**
已统一到 `ManifestSchema.SC_HTTP`，并把 `http.env` 补成受校验的规范字段
（校验环境变量名合法性）。

这类缺陷门禁查不出来（结构都对），**只有把「清单写的」和「代码做的」放一起看才会暴露**。

**其二：后端根本没被落位。**

安装器原先只把 `<version>/` 整目录改名过去（内含 `backend/` 与 `frontend/`），
再把 `frontend/` 摘走。**`backend/` 原地不动。**

而清单写的 `entry: "server.js"` 指向版本目录**根部**，
实际文件却在 `<version>/backend/server.js` —— **后端永远找不到入口，装完也起不来。**

已修：`flattenBackend` 把 `backend/` 的内容平铺到版本目录根部，
并新增判据 `postcheck-entry-not-at-root`（平铺后入口必须真实存在于根部）。
平铺在 staging 阶段做，失败即整包丢弃，不留半成品。
用测试包模拟验证：平铺前入口判据 `false`，平铺后 `true`。

| 落位 | 去向 |
|---|---|
| `backend/` 内容 | 平铺到 `programs/<id>/<version>/`（后端本体，与清单 `entry` 对齐） |
| `frontend/` 整目录 | 搬到 `programs/<id>/quickapp/`（dimina 直接用） |

---

## 2. 注册表：`ui.*` 之前根本没有落盘

`IndexEntry` 早就有 `uiPackage` / `uiUrl` / `onUiClosed` 三个字段，
但全仓**只有编解码在用，没有任何写入者** —— 索引里永远是空串。

这轮：

| 字段 | 来源 | 状态 |
|---|---|---|
| `uiPackage` | `ui.package` | ✅ 写入 |
| `uiName` | `ui.name` | ✅ 新增字段并写入 |
| `uiIcon` | `ui.icon` | ✅ 新增字段并写入 |
| `httpPort` | `PortBroker.claim` | ✅ 写入 |
| `httpHealth` | `http.health` | ✅ 写入 |
| ~~`uiUrl`~~ | — | **删除**：无任何写入者，是死字段 |

`ui.url` 仍留在**清单侧**（阶段 E 的远端 UI），但不进索引 —— 索引是「已装成什么样」的事实，
不是「清单许愿什么」。

---

## 3. 能力面（C1）：走同进程调用，不另起一套

### 3.1 为什么不能复用 socket 那条路

`CapabilityBroker` 的正规入口是 `LocalSocket` + **一次性会话令牌**，
令牌由 `GuestAdapter` 注入给**后端进程**的 `LOBOS_SESSION_TOKEN`。

**快应用前端跑在宿主进程的 dimina 里，不是独立进程，拿不到这条路。**
硬造一条（发 socket + 塞令牌）等于给前端开后门，且令牌会与后端会话互相顶掉
（`SessionRegistry.issue` 按 `(programId, generation)` 去重）。

### 3.2 实际做法

`CapabilityBroker.invokeLocal(programId, method, params)`：

```
签会话（generation=0）→ 算 serverGranted → 组 JSON-RPC 帧 → 走同一个 dispatch()
```

**能力组、作用域、审计三条判据全部原样复用**，不重写一套。
`generation=0` 是安全的：`ProcessLedger.nextGeneration` 从 1 起算（`max + 1`），
永不为 0，所以**既不会覆盖后端会话，又靠去重保证每个程序只留一条**。

### 3.3 作用域闸

`LobosBridge.invoke` 先按 `ApiSpec.scopeOf` 过滤：
**`SCOPE_SYSTEM` 的方法快应用一律拒**（`CODE_POLICY_DENIED`），
因为那些是给宿主与控制面板用的。

### 3.4 修掉一个越权风险

`isProgramSession` 在 `ProgramRegistry.listIds` **读空时返回 true**（视作系统会话）。
若走这条路，`invokeLocal` 会在索引读空时把所有快应用调用当成**系统会话**，
直接拿到 `GROUP_SYS`。

已加前置校验：`invokeLocal` 自己核对 `listIds` 含该 id，不依赖调用方自查。
（`LobosBridge` 侧也有一次 `ProgramIndex.get` 拦截，两层独立。）

---

## 4. 明确不做（现在）

| | 为什么 |
|---|---|
| **远端后端 `backend.remote`** | 零消费方、文档未定形态。现在凭空发明会锁死阶段 E 的设计 |
| **前端直连系统能力** | 能力只经 `invoke` 走 `dispatch`，不新增旁路 |
| **给前端发 socket 令牌** | 会与后端会话互顶（见 3.1） |
| **快应用自提 `lobos:sys`** | `SCOPE_SYSTEM` 一律拒（见 3.3） |

远端后端等形态定了一起做：那时才知道要防的是哪一类 SSRF
（现在 `network_security_config` 只放行 `127.0.0.1`/`localhost` 明文）。

---

## 5. 实现落点

| 文件 | 职责 |
|---|---|
| `ota/ProgramInstaller.kt` | `bindQuickApp`：分配端口 → 注入 config.json → 登记 |
| `quickapp/QuickAppRegistry.kt` | 读 `ui.*`/`http.*` → 写索引；`isQuickApp` 判定 |
| `quickapp/LobosBridge.kt` | `invoke` 事件 + 作用域闸 |
| `bridge/CapabilityBroker.kt` | `invokeLocal` / `live()`：同进程走 `dispatch` |
| `os/ProgramIndex.kt` | 新增 `uiName` / `uiIcon`，删死字段 `uiUrl` |
| `os/ProgramRegistry.kt` | `http` 段读法与 `ManifestSchema` 对齐 |
| `os/PackageInstaller.kt` | 卸载时 `PortBroker.release` + 摘桌面入口 |

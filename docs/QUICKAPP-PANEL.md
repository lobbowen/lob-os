# 控制面板

> 配套：`QUICKAPP-CAPABILITY.md`（能力面与 installer 会话）、`QUICKAPP-INSTALL.md`（包结构）。
> 阶段 D。本文先定职责与形态，页面细节最后调。

---

## 1. 它为什么必须先做

装**第一个**程序需要一个客户端连宿主桥会话。三条路都实测堵死：

| 尝试 | 结果 |
|---|---|
| 设备起常驻服务（node http/socket） | adb shell 会话一结束进程就被杀 |
| 宿主起服务让设备拉 | 设备只能出网到公网，不能反向连宿主（`172.30.225.117` / `192.168.88.239` 都超时） |
| `run-as` 里连 abstract socket | `ENOENT` —— Android mount namespace 隔离，socket 在 app 私有命名空间 |

**而控制面板本身就跑在宿主进程里。** 它不需要 socket ——
`CapabilityBroker.invokeLocal` 同进程就能调 `dispatch`。

所以依赖关系是反的：**控制面板 → 装第一个程序 → 端到端验证 B 阶段**。

---

## 2. 职责

| 做什么 | 走哪条路 |
|---|---|
| 列出已装快应用（id / 名字 / 图标 / 端口 / 桌面状态） | `QuickAppRegistry.listed` + `DesktopIcons.state` |
| 装快应用（输 id，从 CDN 拉） | `os.appmgr.install` |
| 升级 / 检查更新 | `os.appmgr.upgrade` / `checkUpdate` |
| 卸载 | `os.appmgr.uninstall` |
| 加/移桌面图标 | `desktopIcon.add` / `remove`（乙入口） |
| 打开快应用 | `QuickAppHost.open` |
| 看端口占用 | `PortBroker.list` |

**不做什么**：不装商店的系统件（那是 `SupplyProvisioner` 的活，面板只管 APPLICATION 级）、
不碰 `SCOPE_SYSTEM` 的能力（`invokeLocal` 里已对快应用关掉，面板自己走 `ApiSpec` 判据）。

---

## 3. 形态

照 `SetupActivity` 的做法：**纯代码构建**，无 XML 布局。
本项目已经这么干了（`LinearLayout` + `Button` + `TextView`），不引 Compose。

- 入口：`MainActivity` 里一个按钮，或独立 `PanelActivity`
- 列表项：id、名字、版本、端口、桌面三档状态，一行
- 动作按钮：打开 / 装桌面 / 移桌面 / 升级 / 卸载
- 长列表用 `ScrollView` + `LinearLayout`（程序数量级不大，不上 `RecyclerView`）

---

## 4. 关键约束

| 约束 | 依据 |
|---|---|
| 桌面图标状态只能三档 | `DesktopIcons.State`，不接受中间态 |
| 端口只读不写 | 分配只能由 `PortBroker.claim` 做，面板不发明端口 |
| 卸载会摘桌面入口但摘不掉 | `disableShortcuts` 是上限，面板要如实告知 |
| 装完不会自动有桌面图标 | 用户要在面板点「装桌面」，系统弹窗要确认 |
| 长耗时动作走后台线程 | `startProgramJob` 是异步的，UI 要等回调 |

---

## 5. 验收

面板能装上 `com.lobos.fixture`（走 CDN `qa/com.lobos.fixture/`），然后：

| 验收点 | 判据 |
|---|---|
| 注册表 | `program-index.json` 的 `uiPackage` / `uiName` / `httpPort` |
| 端口 | `ports.json` 落在 41000-50999 |
| 前端落位 | `programs/com.lobos.fixture/quickapp/` 有 `config.json` + `main/` |
| 端口注入 | `quickapp/config.json` 的 `backend.endpoint` = `http://127.0.0.1:<那个端口>` |
| 后端真起来 | `PORT` 环境变量 = 那个端口（验 B4 那个段名缺陷的修复） |
| 桌面图标 | 面板点「装桌面」→ 系统弹窗 → 确认后 `未添加` 变 `已添加` |

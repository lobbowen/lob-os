# 一次安装 = 一套完整程序

> 本文定义**安装逻辑**：用户点一次"安装"，前端与后端怎么落位、怎么配对、怎么被识别为一套。
> 配套：`QUICKAPP-PLAN.md`（开发计划）、`QUICKAPP-DESIGN.md`（框架落地方案）。

---

## 1. 结论

**发布方交一个包，目录规范由我们定。**

不是"我们接受什么都行"——目录规范写进规范，发布方按规范打，
安装器按规范拆。开发者给一个包或两个包都不影响我们**能不能装**，
但**规范只支持一种形态**，否则我们的目录约定就形同虚设。

**为什么一个包更好**：
- 用户看到的是一个应用，提交侧也应该是一个应用
- 拆装、升级、回滚都以程序为单位，不会出现"前端升了后端没升"
- 配对天然成立（同一个 zip 里），不靠猜

---

## 2. 包结构

```
<appId>.zip
├── program-manifest.json     ← 我们的清单（已有 schema + 本次新增 frontend 段）
├── frontend/                 ← dimina 编译产物（配置声明在这个）
│   ├── config.json
│   └── main/
│       ├── app-config.json
│       ├── logic.js
│       ├── app.js
│       ├── app.css
│       ├── template.js
│       └── page/…
└── backend/                  ← 开发者的后端（源码或产物，形态我们不管）
    └── …                     ← 有 program-manifest.json 声明的 entry 在里面
```

**为什么前端在 `frontend/` 而不是直接放根**：dimina 要求安装时能找到
`config.json` 和 `main/app-config.json`（`RemoteUpdateManager.requiredPackagePaths`）。
我们把它的编译产物**整体**放在 `frontend/` 下，装的时候整目录交回给它——
它认的是它自己的结构，我们不介入它内部。

---

## 3. 清单新增段

`program-manifest.json` 已有：`id` / `version` / `entry` / `args` / `env` /
`lifecycle` / `http` / `requires` / `capabilities` / `ui`

新增：

```json
{
  "id": "com.example.demo",
  "version": "1.0.0",
  "entry": "demo-server.js",

  "frontend": {
    "dir": "frontend"
  },

  "ui": {
    "type": "quickapp",
    "package": "com.example.demo",
    "name": "示例程序"
  }
}
```

| 字段 | 谁填 | 说明 |
|---|---|---|
| `frontend.dir` | 规范固定 | 前端产物目录，规范里就是 `frontend`；写出来是为了将来能演进 |
| `ui.name` | 开发者 | **桌面图标下显示的字**（微信那套就是这样，`name` ≤ 6 字） |
| `ui.package` | 开发者 | 小程序包名，1:1 绑定 |
| `ui.icon` | 开发者 | 图标（件内相对路径），**图标是程序自己的** |

**`http.port` 这一条要删掉** —— 端口不再由开发者填，改由宿主装时分配
（阶段 B）。规范里明写：**后端端口从环境变量 `PORT` 读，禁止硬编码。**

---

## 4. 安装那一刻发生什么

```
用户点「安装」
   │
   ├─ 1. 验签：zip 整体过 Ed25519（程序包，不拆）
   │
   ├─ 2. 读 program-manifest.json 拿 id
   │      拿不到 → 拒装（"内核不猜安装目标"，沿用现有判据）
   │
   ├─ 3. 查 id 是不是系统件（tools/lib 那些）→ 是则走另一条路，不进本流程
   │
   ├─ 4. 分配端口：PortBroker.claim(id)（段 41000-50999；已领过的返回原端口）
   │
   ├─ 5. 后端落位  programs/<id>/<version>/     ← 只放 backend/ 的内容
   │
   ├─ 6. 前端落位  programs/<id>/quickapp/      ← frontend/ 的内容
   │      改写 config.json：加 {"hostManaged":true,"backend":{"endpoint":"http://127.0.0.1:<port>"}}
   │
   ├─ 7. 写注册表   { id, version, port, frontendPath, ui:{name,icon} }
   │
   ├─ 8. 切 CURRENT 指针 → 新版生效
   │
   └─ 9. 桌面图标：按 ui.name / ui.icon 生成
```

**第 6 步可行已证**：`RemoteUpdateManager.kt:162` 表明 dimina 自己在装的时候
就会 `JSONObject(config.json 内容).put("hostManaged", true)` —— 它本来就允许
宿主往 config.json 塞字段。我们只是多加一个 `backend` 键。

**前端怎么拿到端口**：`app.getInfo()` 读到 `config.json` 里的 `backend.endpoint`，
然后照常 `wx.request`。

**后端怎么拿到端口**：宿主起进程时注入环境变量 `PORT=<分配到的端口>`。
node `process.env.PORT`、python `os.environ["PORT"]`、go `os.Getenv("PORT")` —— 一行。

---

## 5. 配对怎么保证

**同一个 zip，所以配对天然成立。**但仍要防两件事：

| 风险 | 处理 |
|---|---|
| `id` 与已有程序重名 | 不是系统件且已存在 → 判为升级，走替换，不新增 |
| `frontend.dir` 指向不存在 / 结构非法 | **装之前**验三个必需文件，缺一即拒装 |

**升级**同样以程序为单位：重新解 zip、重新分配端口（**保持原端口**，不变）、
替换两半、切 CURRENT、失败回退到旧版。

> 端口**升级时保持不变** —— 因为注册表里写着它，前端 config.json 里也写着它。
> 换端口就得重写 config.json 并通知正在跑的程序，得不偿失。

---

## 6. 卸载

```
programs/<id>/        整个删（含两半、旧版本、CURRENT/FLOOR）
注册表条目            删
桌面图标              摘
```

---

## 7. 规范里要给开发者的（阶段 E 写）

1. 目录结构（就上面那棵树）
2. `program-manifest.json` 字段表
3. **后端必须从环境变量读端口**
4. **前端从 `app.getInfo().backend.endpoint` 取后端地址**
5. **不可用的能力**：`LocalNetworkApi`（mDNS 那套）不提供，走 `wx.request` 打 HTTP
6. 远端后端：声明 `backend.remote`，前端拿到的仍是同一个 endpoint
7. 升级/回滚/卸载的语义

---

## 8. 本次要落的东西（阶段 A/B 的一半）

| | 做什么 | 属阶段 |
|---|---|---|
| A1 | 清单加 `frontend.dir`，`ui` 加 `name`/`icon` 必填 | A |
| A2 | 安装器拆 `frontend/` 与 `backend/` 各自落位 | A |
| A3 | `config.json` 加 `backend.endpoint`（含占位端口） | A |
| A4 | 桌面图标 | A |
| B1 | 注册表 | B |
| B2 | ~~端口分配~~ **已由 PortBroker 建好**（段 `41000-50999`，claim 已领过则返回原端口）。只差接线到安装流程 | B |
| B4 | 后端环境变量注入 `PORT` | B |

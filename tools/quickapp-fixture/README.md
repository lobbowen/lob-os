# 快应用测试包

我们自己的最小可跑样例，用来端到端验证「装包 → 拆前后端 → 注入端口 → 装入 dimina」。
生产包由发布方编译；这里只证明我们的校验与接线是对的。

## 编译（实测通过）

```sh
npm install @dimina/compiler@1.2.1
npx @dimina/compiler build -c . -s ./dist
```

要求 node >= 22.22.3。

## 输入规范（实测得出，不是推的）

| 文件 | 谁放 | 必需 | 说明 |
|---|---|---|---|
| `project.config.json` | 项目 | **是** | 里面的 `appid` 决定产物目录名；缺了目录会叫 `undefined/` |
| `app.json` | 项目 | 是 | `pages` 数组指页面路径 |
| `app.js` | 项目 | 是 | 用全局 `App({...})`，**不是** `export default` |
| `app.wxss` | 项目 | 是 | |
| `pages/<页>/index.js` | 项目 | 是 | 页面逻辑，与视图**分离** |
| `pages/<页>/index.json` | 项目 | 是 | `{"componentFramework":"glass-easel"}` |
| `pages/<页>/index.wxml` 或 `.ux` | 项目 | 是 | 视图 |
| `pages/<页>/index.wxss` | 项目 | 是 | 页面样式 |
| `config.json` | **宿主或发布方** | **是** | 编译器**不产出**；我们往里写 `backend.endpoint` |

## 编译产物（编译器生成）

```
dist/<appId>/
  main/app-config.json
  main/logic.js
  main/app.css
  main/pages_*.js / .css
```

**注意 `config.json` 不在其中** —— 它是宿主或发布方的职责。

## 上游的坑（我们踩过）

编译器 1.2.1 的 `dist/index.js:512` 有 bug：

```js
const sourcemapTargetPath = path.resolve(cwd(), targetPath, useAppIdDir ? getAppId() : "");
```

- `targetPath` 已被 resolve 成绝对路径，而 `path.resolve` 遇到绝对路径会丢弃其后所有分量
- 此刻 `getAppId()` 尚未赋值（appId 在后面才设），返回 `undefined`
- 于是报 `The "paths[2]" argument must be of type string`

**与输入格式无关**：官方 `examples/miniprogram/base` 原样编译也挂同一行。

本地打一行补丁绕过：`getAppId() || "_"`。只影响我们自己的测试包——生产包发布方自己编。

## 装成 LobOS 程序包后应长这样

```
<appId>.zip
└── <version>/
    ├── program-manifest.json
    ├── frontend/          ← 编译器产物（dist/<appId>/ 的内容）
    │   ├── config.json
    │   └── main/…
    └── backend/           ← 开发者后端
        └── server.js      ← 从环境变量 PORT 读端口，不硬编码
```

# 第二个快应用测试工程

用于验证「快应用 A 打开快应用 B」。独立于 `quickapp-fixture`，因为编译产物的
`appid` 决定产物目录名，两个快应用不能编进同一个工程。

## 编译

```sh
cd tools/quickapp-fixture-second
ln -sfn ../quickapp-fixture/node_modules node_modules
npx @dimina/compiler build -c . -s ./dist
```

产物在 `dist/com.lobos.second/main/`。

## config.json 不在产物里

编译器**不产出** `config.json`，它由宿主或发布方写。至少要有：

| 字段 | 值 | 原因 |
|---|---|---|
| `appId` | `com.lobos.second` | 与目标程序一致 |
| `versionCode` | 递增整数 | 不递增会被当同版本，走缓存不刷新 |
| `path` | `pages/second/index` | 入口页；缺了容器能起来但无入口 = 灰屏 |

## 被打成 LobOS 程序包

包结构 `<version>/{program-manifest.json, frontend/, backend/}`，
打包脚本见 `_scripts/second-pack.js`。

## 页面注册约束

页面逻辑必须**全局 `Page({...})` 注册**，不能用 `export default`。
后者会被编译成 ES module，`modDefine` 里没有页面注册，运行时报
`module not found`，表现为灰屏。详见 `docs/DIMINA-SPEC.md`。

# 版本管理规范

APK 版本的唯一真相是仓根的 `version.json`。`container/app/build.gradle.kts` 与
`scripts/publish-native-manifest.js` 都从那里读 —— 仓里没有第二处版本号。

```json
{
  "schema": 2,
  "shell": {
    "versionName": "0.0.1",
    "versionCode": 1,
    "bridgeProtocol": 1
  }
}
```

## 两个号的分工

| 字段 | 谁看 | 规则 |
|---|---|---|
| `versionCode` | Android 与设备 | **单调递增的整数，永不回退，永不复用** |
| `versionName` | 人与日志 | 语义化 `MAJOR.MINOR.PATCH` |

设备判断「能不能覆盖安装」**只看 `versionCode`**。`versionName` 只是给人看的标识。
所以内容一变就必须提 `versionCode` —— 换了名字不换 code，这一版永远装不到设备上。

## 语义化的三级含义

- **MAJOR**：不兼容的协议或格式变更。存量设备上的数据要迁移。
- **MINOR**：加了能力，向后兼容。
- **PATCH**：只修问题。

当前处于 `0.x` 段。按语义化约定，`0.x` 里 MINOR 的变化也算可能不兼容 ——
提 MINOR 前先想清楚老设备上的程序能不能继续跑。

## 发版流程

1. 改 `version.json`（只改这一处）
2. 本地跑 `node tools/check-version-bump.js` —— 它会拿上一次发布比对并算出该填什么
3. 推 `release-<versionName>` tag（如 `release-0.0.1`）
4. `build-apk.yml` 由该 tag 触发构建与投递

门禁挂在 `ci.yml`，每次 push 都跑。**tag 是发版的凭据** ——
没有 `release-<versionName>` tag 就没有「上一次发布」，`versionCode` 的单调性无从比对。

## 三个会造成「跑路分支」的错

门禁对下面三种情况硬判红：

1. **`versionCode` 变小** —— 已装的设备再也收不到更新。
2. **版本号与上一次发布完全相同** —— 同号只能配同一批字节；内容变了而号没变，
   设备按「已是最新」跳过更新，所有按号说话的证据（债表、真机读数）失去所指。
3. **换了 `versionName` 但 `versionCode` 没动** —— 这一版装不到任何设备。

## 与 `bridgeProtocol` 的关系

`bridgeProtocol` 是引擎与壳之间的协议版本，与 APK 版本**各自独立**：
同一 APK 内换引擎实现可以只动 `bridgeProtocol`。
但它变化时必须同步改 `container/engine` 与 ApiSpec 三处声明 ——
`tools/check-protocol-version.js` 会核那三处。
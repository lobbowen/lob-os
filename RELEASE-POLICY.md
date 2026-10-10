# 构建与发布规范

这份规定**什么改动触发什么构建**、**什么东西该进 Release**。
目的是：不再出现「推一下就占一次 runner」和「Release 里堆满没人用的东西」。

---

## 一、Release 里只该有两类

| 类别 | tag 形态 | 生命周期 | 数量 |
|---|---|---|---|
**正式版** | `os-release-<版本>` | 永久 | 每次发版 +1 |
**件** | `<筐>-<件名>` | 永久 | 每件只留最新一个 |

**其余一律不进 Release**：

- 验证包 / 测试包 → 走 CI artifact（保留 30 天，够用）
- 同一件的旧版本 → 删，只留最新
- 已被清单改名/移除的件 → 删

判断某件该不该有 Release：它的名字必须在 `scripts/component-verify.json` 的 24 项里，
且筐前缀对得上。

---

## 二、什么时候构建什么

### Build APK（出 APK）

| 触发 | 说明 |
|---|---|
`push main` | **仅当**改了下面这些路径 |
`push os-release-*` tag | 总是跑（那是发布） |
手动 dispatch | 总是跑 |

paths 名单（9 条）：
```
container/**           scripts/**            tools/**
components/**          gradle.properties
build.gradle.kts       settings.gradle.kts
.github/workflows/build-apk.yml
.github/workflows/build-isolated-apk.yml
```

**不在名单里的改动不构建**：文档、`.md`、`ACCEPTANCE.md`、`version.json`
（版本号只在发 tag 时才被读取）、`RELEASE.md` 等。

### 件构建（21 个 build-*.yml）

**件构建一次，产物长期有效，不要重复构建。**

触发条件只有一个：**那个件需要重建**。具体是：

- 改了它自己的构建脚本（`scripts/recipes/build-piece-<id>.sh` 或 `build-native-<id>.sh`）
- 改了它的钉值（`scripts/component-sources.json` 里那一项）
- 它的 Release 被删了

其余任何改动都**不该**触发它 —— 每个 workflow 都有自己的 paths 名单，
改 `container/**` 不该让 12 个件全部重编。

---

## 三、发版流程

### 前置检查

```
1. version.json 的 versionName / versionCode 已提（versionCode 必须比上次大）
2. version.json 与已发布版本不重复（同号只能配同一批字节）
3. 签名 secret 就位：ANDROID_KEYSTORE_BASE64 / _PASSWORD / _ALIAS / _KEY_PASSWORD
4. APK_PUBLISH_TOKEN 就位
5. 签名密钥没变过（version.json 的 shell.signingKey.certificateSha256 与 CI 报出的一致）
```

### 执行

```bash
git tag -a os-release-<版本> -m "<说明>"
git push origin os-release-<版本>
```

### CI 会做的

第 6 步「发布轮前置校验」四条全过才往下走：
tag 与 `version.json` 逐字一致 · 四件签名 secret 齐 · 发布令牌在位 · 同号未发过。

**任何一条不过就停在那，不浪费编译时间。** 看 Job Summary（不是日志，日志会被截断）。

### 产物

```
https://github.com/lobbowen/lob-os/releases/download/os-release-<版本>/app-release.apk
```

---

## 四、base 筐的件为什么每次都要重编（当前状态 · 已知缺口）

base 筐 12 件随 APK 内置（`assets/supply/meta/` → `PrefixProvisioner` 铺到
`usr/lib/<id>/<版本>/`）。

**现状：没有固化产物复用。** `build-apk.yml` 第 15 步每次都跑
`ensure-native-capabilities.sh`，12 件全部现场编译 —— 这是 Build APK
一轮要 8 分钟的主要原因。

仓里没有「从 Release 下载已构建 zip」的路径（那条逻辑在前几轮清理时被删，
因为它依赖的 workflow 已不存在，留着是会「看起来能用但必然失败」的废弃逻辑）。

**正确做法应该是**：

```
构建一次 → 产出 zip + sha256 + 源码指纹
存进 Release（tag 如 piece-<id>-<指纹前12>）
pin.json 记录 id → 指纹 → release tag → asset 名
发 APK 时：指纹对上则下载解包；对不上则现场编译并回报废
```

**关键**：必须有「对不上就重建并更新」的闭环，否则又是死路。

---

## 五、真机验收

见 `ACCEPTANCE.md`：配对规范（**注意配对端口 ≠ 连接端口**）、大文件传输、
每轮验收结果。

诊断从外部可见靠 `services/log/Mirror`（镜像到应用外部目录）——
shell 读不到应用私有目录，这是唯一能读的通道。

---

## 六、禁止的事

| 禁止 | 原因 |
|---|---|
把 CI 中间产物发进 Release | 已造成 18 个 0 下载的垃圾 |
建「兼顾两种触发」的双份配置 | `paths` 与 `branches`/`tags` 是**并列**关系，不是嵌套 |
改 Release 里的东西不更新清单 | 清单是唯一真相源 |
同一件留多个 Release | 控制面板按清单取，留多个就取不准 |

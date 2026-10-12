# 构建与发布规范

**这份文档是唯一的行为规范。** 任何与它不符的脚本、workflow、约定都算 bug，
要么改代码，要么改这份文档 —— 不能「我这边好了、那边还是老样子」。

---

## 一、三条铁律

### 1. 件是独立的，一件一条命令

```
要构建 jq  →  只构建 jq
要构建 git →  只构建 git
```

**不由 push 触发。** 件构建 workflow 只有 `workflow_dispatch`。

理由：此前靠 `paths` 白名单过滤，结果是「改一个共享脚本触发 16 个件」。
白名单只能防「漏」，防不了「多」，而两个方向都是错。

### 2. 件编一次，之后一直用

```
第 1 步  算固化标识  piece-key.sh <id>  →  key / tag / asset
第 2 步  已固化就复用  Release 里有 → 下载解包 → hit=yes
第 3 步  编           仅在 hit=no 时
第 4 步  固化         产物 + sha256 上传到 Release
```

`tag` = `<筐>-<件名>`，`asset` 含依赖与 NDK/API —— **依赖一变名字就变 = 重新固化**。

**这一套此前 11 个 workflow 全是空壳**（只有注释、没有计算），tag/asset 为空串，
复用从未命中。现已统一走 `scripts/toolchain/piece-key.sh`。

### 3. APK 只取件，不编件

**预装件是现成的。** APK 构建不再现场编译任何件，
只从各筐的固化产物取。

---

## 二、触发矩阵

| 改了什么 | 触发什么 |
|---|---|
`.md` 文档 | 无 |
`container/**`（Kotlin 等） | **Build APK** |
`scripts/recipes/build-piece-<id>.sh` | 无（要重建那个件时手动 dispatch） |
`component-sources.json` | 无 |
`.github/workflows/build-apk.yml` | **Build APK** |
`.github/workflows/build-<id>.yml` | 无 |
打 `os-release-*` tag | **Build APK（发布轮）** + Component Manifest |
手动 dispatch `build-<id>` | 只有 `<id>` 这一件 |

**推送不会重建任何件。**

---

## 三、件构建怎么做

```bash
# 1. 算固化标识（本地先看一眼）
bash scripts/toolchain/piece-key.sh jq
#   key   = uw-jq-nodeps-ndk30.0.16248370-api35-Linux-X64
#   tag   = base-jq
#   asset = jq-nodeps+ndk30.0.16248370+api35.tar.gz

# 2. 触发构建
curl -X POST -H "Authorization: Bearer $TOK" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/lobbowen/lob-os/actions/workflows/build-<id>.yml/dispatches \
  -d '{"ref":"main"}'

# 3. 等 run 结束（成功 → 产物已进 Release）
```

**需要重建的四种情况**：
- 改了它自己的 `build-piece-<id>.sh` / `build-component-<id>.sh`
- 改了它的钉值（`component-sources.json` 里那一项，含 sha256）
- NDK / API 钉值变了（asset 名会变，自动重新固化）
- 它的 Release 被删了

其余一律不重建。

---

## 四、Release 里只该有两类

| 类别 | tag 形态 | 生命周期 |
|---|---|---|
**正式版** | `os-release-<版本>` | 永久 |
**件** | `<筐>-<件名>` | 永久，每件只留最新一个 |

**不进 Release 的**：CI 中间产物（走 Actions artifact）、验证包、同一件的旧版本。

判断依据：tag 必须能在 `component-sources.json` 里找到对应的 `<筐>-<件名>`。

---

## 五、发版流程

### 前置检查

```
1. version.json 的 versionName / versionCode 已提（versionCode 递增）
2. version.json 与已发布版本不重复（同号只能配同一批字节）
3. 四个签名 secret 就位：ANDROID_KEYSTORE_BASE64 / _PASSWORD / _ALIAS / _KEY_PASSWORD
4. APK_PUBLISH_TOKEN 就位
5. 签名指纹未变（version.json 的 certificateSha256 与 CI 报出的一致）
6. base 筐 8 个件在 Release 里都有产物（APK 取件的前提）
```

### 执行

```bash
git tag -a os-release-<版本> -m "<说明>"
git push origin os-release-<版本>
```

### CI 会做的

「发布轮前置校验」四条全过才往下走：
tag 与 `version.json` 逐字一致 · 四件签名 secret 齐 · 发布令牌在位 · 同号未发过。

任何一条不过就停在那，不浪费编译时间。看 Job Summary，不是日志（日志会被截断）。

### 产物

```
https://github.com/lobbowen/lob-os/releases/download/os-release-<版本>/app-release.apk
```

---

## 六、内核 vs 件（判定标准）

判据是**「它是不是一个可替换的外部软件」**：

| 判据 | 件 | 内核的一部分 |
|---|---|---|
用户主动调用 | 是 | 否（系统自动注入） |
进 PATH | 是 | 否 |
版本 | 上游真实版本 | 没有「上游」这个概念 |
能 OTA 替换 | 应该能 | 不该（换 = 换系统行为） |
判据性质 | 「起得来、真能用」 | 「符号能解析、顺序对」 |

**已按此判定移出件的**：`posix` `flock` `ptyprobe` `ptysession`
（`container/native/` 下我们自己写的 C，随 APK 而来，归 `kernel/compat/`）

**判定看的是「component-sources.json 里有没有上游版本与 urls」**，
不是看它有没有构建脚本。

---

## 七、禁止的事

| 禁止 | 原因 |
|---|---|
把 CI 中间产物发进 Release | 已造成 18 个 0 下载的垃圾 |
用 `paths` 白名单决定触发 | 只能防漏不能防多，两个方向都是错 |
建「兼顾两种触发」的双份配置 | `paths` 与 `branches`/`tags` 是**并列**关系，不是嵌套 |
同一个标识算法在多处各写一遍 | 已造成 11 个空壳；单点定义（如 `piece-key.sh`） |
让 cache 恢复失败杀死 job | 缓存是优化不是正确性，要 `continue-on-error` |
改 Release 里的东西不更新清单 | 清单是唯一真相源 |
## 借 Android 的能力 vs 内化（Lob OS 自己的机制）

Android 是**硬件**（电源、内存、调度、内核、Zygote、链接器、binder、SELinux 沙箱）。
Lob OS 是**装在这台硬件上的系统**。

判据：**这件事该由谁负责**。硬件层的借，系统层的自己做。

### 借得对（别自己造）

```
生命周期组件（Activity/Service/Application）   进程保活、电源管理、通知
Binder IPC                                  内核提供的 IPC，重复造轮子
ProcessBuilder                              fork/exec，Unix 原语的封装
```

### 借但内化不了（已知地基债）

```
LD_PRELOAD       kernel/proc 下 4 个垫片（exec-path / link-interpose /
                 open-fallback / tmp-paths）必须在进程启动前生效，
                 Android 没有别的机制。

LD_LIBRARY_PATH  Android 的链接器不读 ld.so.cache（实测 /system/etc/ld.so.conf
                 不存在）。试过 linker namespace（API 29+）：
                   $ strings /apex/…/linker64 | grep namespace
                     __loader_android_init_anonymous_namespace
                   $ strings /apex/…/libdl.so | grep namespace
                     （空）
                 那些入口全在 linker 内部带 __loader_ 前缀，
                 应用侧拿不到它的句柄，dlsym 取不到。
                 **这是 Android 的硬限制，不是设计选择。**
                 所以「程序认我们装的库」靠程序的环境，不靠 cache。
```

### 不该借（已改/已清）

```
nativeLibraryDir 塞件     那是旧实现残留（曾堆了 43 MB 在 jniLibs 里），
                          现在只有 3 个内核垫片。已清 + .gitignore 防再犯。

getenv("PATH") 当系统环境  程序的 PATH 属于系统视图；宿主 PATH 只是 fallback。
                          已改为 joinPath(prefixBin, nodeBin, inheritedPath)。
```

### 系统视图 = 一套 env，程序继承它

```
GuestAdapter.programPlan 给每个程序：
  PATH             prefixBin : nodeBin : inheritedPath
  LD_LIBRARY_PATH  nativeLibDir : prefixLib
  HOME / TMPDIR / LANG
  LD_PRELOAD / LOBOS_FLOCK_SO / LOBOS_BRIDGE_SOCKET / …

程序内部再起子进程（bash 跑 curl、node spawn）自动继承 ——
那是 Unix 语义，我们不用做任何事。

真机验收：探针 18 项全过（见 ACCEPTANCE.md「系统视图与透传」）。
```

# APK 发布规范

这一份讲**怎么把一个正式版 APK 发出去**，以及每一步的判据在哪、失败了会看到什么。

只讲这一条链。仓库里没有别的 `.md`，这是唯一一份；下面引用的文件与行号都能核。

---

## 一、两条轮次

同一个 workflow 跑两种轮次，由 **ref 是不是 `os-release-*` tag** 决定：

```yaml
RELEASE_ROUND: ${{ startsWith(github.ref, 'refs/tags/os-release-') }}
RELEASE_TAG:   ${{ startsWith(github.ref, 'refs/tags/os-release-') && github.ref_name }}
```

| | 校验轮 | 发布轮 |
|---|---|---|
| 触发 | push `main` | push `os-release-*` tag，或 workflow_dispatch 到 tag ref |
| `RELEASE_ROUND` | `false` | `true` |
| 签名 | 没密钥就用 AGP 现场生成的 debug 签名 | 必须用发布签名，缺则判红 |
| 变体 | debug | release |
| 产出 | `apk-debug` artifact（每次都有，供下载验证） | Release `os-release-<versionName>` + `lobos-os-<versionName>.apk` |

**校验轮的产物不可发布**：debug 签名每次都不一样，装到已装设备上会
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

---

## 二、发布签名：一次性，永不更换

Android 没有密钥回退机制 —— 签名一换，所有已装用户就再也装不上新版本。
所以这把钥匙只在第一次生成，之后**永不更换**，也不该有任何能覆盖它的脚本留在仓里。

当前这一把（记录在 `version.json` 的 `shell.signingKey`）：

```
alias    lobos
SHA-256  0f80988f167418d9c1325d938b7ac765d7c75b5727857ddcefd6c2d3935c8f43
DN       CN=Lob OS, OU=Container, O=LobOS, C=CN
首次发布  os-release-0.0.1
```

**指纹的唯一权威来源是 `apksigner` / `keytool` 读出来的值**，不要自己从
keystore 二进制里解析 —— 那次我这么干过，取到的不是签名证书，报了个错的指纹。

**每次发布 CI 都会自动核验**（第 20 步「验签」）：
`verify-apk-signing.sh` 用 `apksigner verify --print-certs` 读出 APK 里
的证书，再与本次注入的 `keys/release.cert` 比对指纹，不一致就判红
（「签名身份不符」）。所以正常发布不需要人工核对。

**要脱离 CI 单独核对**（比如确认某个已下载的 APK 是哪一把签的）：

```bash
bash scripts/verify/verify-apk-signing.sh app-release.apk --cert keys/release.cert --require-stable
```

本机没有 keytool/apksigner 时，只能从 APK 的 v1 签名里取证书
（`META-INF/CERT.RSA` 是 PKCS#7，里面嵌着 X.509 证书），
把它的 DER 算 SHA-256 与上面比对 —— 注意别像我在 keygen 时那样用
「找第一个 30 82 DER 段」的启发式去解析 keystore，那取到的不是签名证书。

### 四个 secret

`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` /
`ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`。

> ★ **GitHub 只注入 `env` 里出现过的 secret。**
> `run` 块里 `${!v}` 只是读环境变量，读不到没声明的 secret。
> 新增用到 secret 的 step，必须在它的 `env:` 里显式写出来。
> 参考第 17 步用的是别名（`KS_B64` / `KS_PASS` / `KS_ALIAS` / `KS_KEYPASS`），
> 第 6 步用的是原名 —— 两种都可以，**但都得声明**。

> ★ **写 secret 只能用 `gh secret set`。** GitHub 的 secret 是 libsodium
> sealed box（crypto_box_seal，X25519 公钥）。手工做的密文 GitHub 解不开，
> 表现是「secret 在设置里存在、job 里读到长度 0」。
> 验证某个 secret 到底注入没注入，就在某个早期 step 里加一行
> `eval "v=\${NAME:-}"; echo "[probe] $NAME ${#v}"`（只报长度，不报内容）。

---

## 三、发一个新版本

### 1. 提 `version.json`

```json
{ "shell": { "versionName": "0.0.2", "versionCode": 2 } }
```

`versionCode` 单调递增。`signingKey` 那段**不要动**。

### 2. 打 tag，必须与 `versionName` 逐字一致

```bash
git tag -a os-release-0.0.2 -m "Lobos 0.0.2"
git push origin os-release-0.0.2
```

差一个字符就会在**前置校验**那步被拦下（第 6 步，编译之前）。

### 3. 等它跑完，看第 23 步

第 23 步「发布轮：发正式版」成功后，Release `os-release-<versionName>` 上会有
`lobos-os-<versionName>.apk`。

### 4. 核对指纹

从发布出去的 APK 里解出证书 SHA-256，与 `version.json` 记的一致才算这版发对了。

---

## 四、两个闸门

### 版本闸门（第 23 步内）

```
[error] 同版本号已发布过：os-release-0.0.2 已存在。
         同号只能配同一批字节；变了就提 versionCode 再打新 tag。
```

**同号只允许配同一批字节。** 要重发就把 `versionCode` 提上去、打新 tag。

### 签名闸门（第 17 步）

没配密钥时校验轮放行、发布轮判红，并说明为什么不能用一次性 debug 签名
（指纹每次不同 → 装到已装设备上冲突）。

---

## 五、发布前置校验（第 6 步）

发布轮的四个判据，**在任何编译之前**跑：

1. tag 与 `version.json` 的 `versionName` 逐字一致
2. 四件签名 secret 齐备
3. `APK_PUBLISH_TOKEN` 在位
4. 同版本号的 release 不存在

任一不满足立刻退出，后面 18 个步骤全 `skipped` —— 不白跑十几分钟。

判定结果**写进 `$GITHUB_STEP_SUMMARY`**，逐条 ✅/❌ 加末尾结论。
看那里，不要翻日志 —— job 日志有行数上限（实测 494 行就断了），
前置校验的实际输出经常被截掉。

> 这一步一开始没有，是踩出来的：原先这三个判据排在第 30 步之后，
> 也就是编完 13 个原生件 + 整个 APK 之后才判。tag 打错一个字符、
> secret 没配，都要等十几分钟才报错。

---

## 六、踩过的坑（每一条都实际浪费过时间）

### 1. workflow 解析失败 = 静默不触发

GitHub 对「workflow 解析失败」的表现是**不报错、不触发**。
`if:` 里的表达式必须写成 `if: ${{ env.RELEASE_ROUND == 'true' }}`，
少了 `${{ }}` 包裹就整个 workflow 报废。

> `js-yaml` 能解析通过，GitHub 用的是自己的表达式引擎，**两者不等价**。

### 2. `push` 下的 `paths` 与 `tags` 不能并存

`paths` 过滤器只对**分支** push 生效；`push` 下带了 `paths`，tag push
一律判为不匹配。现在 `build-apk.yml` 的 `on.push` 只有 `branches` 与
`tags`，没有 `paths`。

### 3. 同一 tag 的强制更新不重新触发

改完 workflow 想重跑发布，必须**删掉远端 tag 再推一个新的** ——
`git push --force` 到已存在的 tag，GitHub 认为处理过了。

### 4. 跨 run 取不到 artifact

`actions/download-artifact@v4` 只能下载**当前 run** 的产物。
密钥是上一条 workflow 生成的，要走 API 取：

```bash
AID=$(gh api "repos/$REPO/actions/artifacts?name=XXX" \
        --jq '[.artifacts[]|select(.expired_at==null)]|sort_by(.created_at)|last|.id')
gh api "repos/$REPO/actions/artifacts/$AID/zip" > a.zip
```

### 5. `if:` 与 shell 的引号

YAML 里写 `if: env.X == 'true'`（单引号）没问题；
`if: env.X == "true"`（双引号）GitHub 报
`Unexpected symbol: '"true"'`，整个 workflow 失效。

### 6. `[ "$FAIL" = "0" ] && echo …`

`FAIL=1` 时这行返回非零，`set -e` 直接终止脚本，
**后面几条检查与总结那句都来不及跑**。用 `if` 判断。

---

## 七、哪些东西不要放进仓里

| | 原因 |
|---|---|
| keystore、证书、密码 | `.gitignore` 已挡（`keys/*`、`*.keystore`）；泄漏等于泄漏发布身份 |
| 「生成/写入签名材料」的 workflow | 它有覆盖 secret 的能力，等于留了一把能换掉发布身份的钥匙。生成密钥的脚本自带拒绝覆盖的闸门（`keygen-android-keystore.sh` 第 13-16 行），写 secret 的没有 |
| `Release` 里 base 筐的 tag | base 筐随 APK 内置、控制面板不取它们，那些是历史构建产物 |

指纹可以入库（公开信息），密钥材料不行。

> 指纹**不需要**打进 APK。签名本身就在 APK 里，谁拿到都能用
> `apksigner verify --print-certs` 读出来 —— 再塞一份进去只是多一个
> 可能与实际不符的副本。APK 里已有 `versionName` / `versionCode`
> （Android 标准字段，任何工具都读得到），足够了。

---

## 八、相关文件

| 位置 | 作用 |
|---|---|
| `.github/workflows/build-apk.yml` | 两条轮次的全部步骤 |
| `version.json` | 版本号 + 发布签名指纹（`shell.signingKey`） |
| `scripts/publish/inject-apk-keystore.sh` | 解出密钥、写进 `keys/`、导出证书 |
| `scripts/publish/keygen-android-keystore.sh` | 生成密钥（拒绝覆盖） |
| `scripts/verify/verify-apk-signing.sh` | 读 APK 证书，与注入锚点比对 |
| `scripts/verify/verify-apk-native.sh` | 产物形态（条目 / 件说明 / 依赖闭环） |

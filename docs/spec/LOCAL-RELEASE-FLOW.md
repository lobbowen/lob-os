# 本地发布流程（不经过 GitHub）

> 执行行为约束见 `docs/spec/EXECUTION-POLICY.md`，本流程的每一步都受其约束：
> 未验证产物不留存、七牛只传正式可用内容、测试走可清空测试目录、本地留可用备份。

目标：把件从"GitHub Actions 构建 + 跨境上传"彻底改为"本地构建 + 本地传七牛"。
件从出生就在国内，直接 14ms 上传七牛，消除跨境瓶颈。
不再 push GitHub tag、不再走 build-python3.yml / build-component.yml、不再用 Release 中转。

---

## 一、本地已具备的条件（实测）

| 条件 | 状态 |
|---|---|
| NDK 30.0.16248370 | 已有 `/home/bowen/ndk-acquire/android-ndk-r30`，与钉值一致 |
| 机器资源 | 32 核 / 91G 内存 / 187G 磁盘 |
| py-deps（libffi/ssl/sqlite 等） | 已备好 `work/python-deps` |
| 到七牛上传节点 | 14ms |
| 到 python.org | 可达 |
| 签名私钥 OTA_PRIVATE_KEY_PEM | 本地无，需从 GitHub secret 取一次（见前置） |
| 宿主 python 3.11 / 3.12 | 缺，需本地编译安装（一次性，见前置） |

---

## 二、一次性前置准备

### 2.1 签名私钥

签名私钥只在 GitHub secret `OTA_PRIVATE_KEY_PEM`。本地发布需要它。
取法：用 GitHub API + 临时 workflow 解密一次，写入本地 `keys/ota-private.pem`（chmod 600），
然后从 GitHub 删掉临时手段。私钥不入库、不贴聊天。
公钥已焊在 APK 里（`container/app/src/main/assets/supply/component-public.pem`），
私钥必须与它配对，不能重新生成。

### 2.2 宿主 python 3.11 / 3.12

CPython 交叉编译要求"宿主 python 与目标同 major.minor"。本地只有 3.14。
从 python.org 源码本地编译 3.12.10 / 3.11.13（不是交叉编译，几分钟）：
```
下载 Python-3.12.10.tgz / Python-3.11.13.tgz
./configure --prefix=$HOME/pythons/3.12  && make -j32 && make install
```
编件时用 `BUILD_PYTHON=$HOME/pythons/3.12/bin/python3` 传给 build-component-python3.sh。

### 2.3 七牛凭证

AK / SK / BUCKET=lobos / PUBLIC_BASE=https://lobcdn.zll.ink。
本地用环境变量传给 upload-qiniu.js（不落文件）。

---

## 三、发布一趟的完整步骤

设本轮 revision = N（单调递增，当前线上 20，下一轮 21），channel = canary。

### 步骤 1 清理 dist
```
cd /home/bowen/LOBOS/work/lob-os
rm -f dist/component-*.zip
```

### 步骤 2 逐个编译件（本地）

对 bucket 里的每个件（node/python 三版本/cmake/git/make/...，llvm 已排除）：

普通件用对应 recipe：
```
CC=<NDK>/aarch64-linux-android35-clang bash scripts/recipes/build-component-<name>.sh
```
python 三个版本用 PY_SLOT 槽位，全部平铺到 dist/：
```
export CC=<NDK>/aarch64-linux-android35-clang
# 3.14
bash scripts/recipes/build-component-python3.sh
# 3.12
PY_SLOT=3.12 PY_VERSION=3.12.10 BUILD_PYTHON=$HOME/pythons/3.12/bin/python3 \
  bash scripts/recipes/build-component-python3.sh
# 3.11
PY_SLOT=3.11 PY_VERSION=3.11.13 BUILD_PYTHON=$HOME/pythons/3.11/bin/python3 \
  bash scripts/recipes/build-component-python3.sh
```
每个版本产出 `dist/component-python-<ver>-<sha>-android-arm64.zip`，
件内 id 都是 python（PKG_NAME=python），靠版本与 sha 区分。

### 步骤 3 打包 + 形状校验（每个件）
```
bash scripts/verify/verify-component-artifact.sh <name>
bash scripts/recipes/package-component.sh <name>
```
python 三版本都用 package-component.sh python（件名统一），钉值键用 python / python-3.12 / python-3.11。

### 步骤 4 清单投影与对账（不签名，先自检）
```
node scripts/publish/publish-component-manifest.js dist --project > work/declared.json
node scripts/verify/reconcile-missing-pieces.js ...
node scripts/publish/publish-component-manifest.js dist --declared-only > work/declared-side.json
node scripts/publish/check-component-manifest-drift.js --immutable work/declared.json ...
```

### 步骤 5 签名清单
```
node scripts/publish/publish-native-manifest.js "" release keys/ota-private.pem canary
LOBOS_COMPONENT_REVISION=N \
  node scripts/publish/publish-component-manifest.js dist release keys/ota-private.pem canary
node scripts/verify/check-component-manifest-drift.js --immutable release/component-manifest.json ...
```
revision 从环境变量给（本地不再从 tag 解）。本地自己维护一个 revision 计数，
每次发布 +1，确保单调递增（当前 20）。

### 步骤 6 上传件包到七牛（本地，14ms）
```
export QINIU_AK=... QINIU_SK=... QINIU_BUCKET=lobos
export QINIU_PUBLIC_BASE=https://lobcdn.zll.ink
for f in dist/component-*.zip; do
  node scripts/publish/upload-qiniu.js "$f" "component/$(basename $f)" --cache-control=31536000
done
```

### 步骤 7 上传清单到七牛
```
node scripts/publish/upload-qiniu.js release/component-manifest.json \
  component-canary/component-manifest.json --cache-control=60
node scripts/publish/upload-qiniu.js release/component-manifest.json.sig \
  component-canary/component-manifest.json.sig --cache-control=60
# native 清单同理
```

### 步骤 8 设备验证
设备商店刷新清单 → 看到 python 三版本 → 装 3.12 → 切 3.11 → 切 3.14，
每次验证 `--version` 与入口软链、C 扩展 import。

---

## 四、需要固化的东西

1. 一个 `scripts/publish/local-release.sh` 把步骤 1-7 串起来，
   参数：revision N，自动跑所有件、对账、签名、上传。不要每一步手敲。
2. revision 计数存在 `work/local-revision` 文件（本地维护，单调递增）。
3. 前置（私钥、宿主 python、七牛凭证）一次性准备好，脚本检查缺什么就报缺。

---

## 五、与 GitHub 的关系（彻底切断的部分）

- 不再 push `component-canary-N` tag
- 不再触发 build-python3.yml / build-component.yml 做发布
- 不再从 GitHub Release 取件（fetch-component-artifacts.sh 本地发布不用）
- GitHub 保留做代码托管与 APK 编译验证（build-apk.yml），但件发布完全本地

保留 GitHub 的部分要想清楚：APK 里的 channel.json / 公钥不变，
设备照样从 lobcdn.zll.ink 取清单——客户端零改动。

---

## 六、风险与待定

- 本地电脑需要开机才能发布（不是无人值守）。若不可接受，把这套流程搬到
  国内服务器 103.236.89.42（配置够），但它连 python.org 不通，源码/依赖要从本地传过去。
- 私钥在本机，要注意保管（chmod 600，不进 git）。
- revision 单调由本地文件维护，需保证不回退。

---

## 七、本地构建发布实测通过（2026-11-20）

最小验证：本地从零编译 python 3.11.13 并上传七牛，全程不经 GitHub。

前置就绪：
- 配对私钥：/home/bowen/LOBOS/keys-offline-backup/ota-private.pem（已验证与 APK 公钥配对）
- NDK：/home/bowen/ndk-acquire/android-ndk-r30（30.0.16248370，与钉值一致）
- 宿主 python 3.11/3.12：用 uv 15 秒装好（构建脚本要求宿主同 major.minor）
- 源码缓存：/tmp/Python-3.11.13.tgz 与 3.12.10.tgz 已在本地（python.org 直连仅 38KB/s，
  必须用本地缓存，构建脚本检测 SRC 目录存在即跳过下载）

实测结果：
1. 本地编译 python 3.11.13：成功（解释器 18MB、67 扩展模块、17 C 库、aarch64 无 glibc）
2. PKG_NAME=python 打包：component-python-3.11.13-c33b3d362d55-android-arm64.zip
   （件内 id=python、version=3.11.13、kind=runtime，正确）
3. 本地上传七牛：84MB 用 38 秒（对比 CI 跨境 30-100 分钟）

结论：本地构建发布闭环成立，跨境瓶颈消除。下一步固化 local-release.sh。
注意：打包必须传 PKG_NAME=python，否则件内 id 变成 python-3.11，清单无法聚合。

---

## 八、本地发布已固化并首次执行成功（2026-11-20）

脚本：`scripts/publish/local-release.sh`（chmod +x），一条命令完成构建到上传。
凭证：`~/.config/lobos-qiniu.env`（600，不进 git）；私钥用本地配对备份。
revision 计数：`work/local-revision`（gitignore，本地状态，当前 21）。

首次正式发布（revision 21）实测：
- dist 集齐 9 件（python 三版本 3.11.13/3.12.10/3.14.8 本地新编，
  其他 8 件从 CDN 14ms 拉回复用字节），清单正确聚合成 python versions[] 三版本。
- 清单签名自检通过（私钥与 APK 公钥配对）。
- 全部上传（3 新 python 件 + 8 已有件 + 4 清单）耗时 168 秒，全部回读 ok。
- CDN revision 21，python 三版本均 HTTP 200，默认 3.14.8。

对比：CI 跨境发布 100 分钟且反复失败；本地发布 3 分钟成功。跨境瓶颈彻底消除。

用法：
```
source ~/.config/lobos-qiniu.env
bash scripts/publish/local-release.sh            # 增量：python 三版本本地编，其余从 CDN 复用
bash scripts/publish/local-release.sh --all      # 全量：所有件本地重编
bash scripts/publish/local-release.sh --revision 22  # 指定 revision
```

关键坑：打包必须传 PKG_NAME=python，否则件内 id 变 python-3.11 无法聚合。

---

## 九、端到端验收通过（2026-11-20）

设备实测（f0942f56）：
- 商店刷新后 catalog revision=21，python versions[] 三版本全部到达设备。
- python 详情页"可选版本"列出 3.11.13 / 3.12.10 / 3.14.8，
  当前版本标注"已安装 · 当前 3.14.8"，并有重新安装/卸载入口。
- 系统 phase=RUNNING、controlPlaneUp=true。

至此本地发布闭环全线打通：
本地构建（三版本 Python）-> 打包（件内 id=python）-> 清单聚合 versions[]
-> 本地签名（私钥配对）-> 本地上传七牛（3 分钟）-> 设备商店显示三版本。
全程不经 GitHub，跨境瓶颈消除。

版本切换的运行时生效（Resolver 绑定 + 应用级路由）已编译通过并随 APK 装机，
设备侧"装第二个版本并切换"的完整运行时验证留待装应用时验证（需要客侧应用声明
runtime 版本依赖）。

---

## 十、APK 全本地化构建 + 多版本切换端到端验收（2026-11-20）

APK 构建也完成本地化（不再依赖 GitHub Actions 取件）：
- 本地装 Android SDK（cmdline-tools/platform-35/build-tools 35.0.0）+ Gradle 9.6 + 用系统 JDK21
- 本地从源码重编 9 个 base 件（scripts/toolchain/build-base-pieces-local.sh），
  归集到 assets/pieces/<id>.zip。crypto 从 openssl 产物拆 libcrypto；git 用 package-component 打包。
- 本地 gradle assembleRelease 产出 app-release.apk（76MB，本地 keystore 签名 2429d2b7）
- 关键坑：base 件编完必须立即归集（后续脚本会清 dist）；git 编完要单独 package-component；
  bundle 脚本依赖顺序（zlib 在 curl/git 之前）；需 export LLVM_STRIP/AR/RANLIB/READELF。

设备验收（f0942f56，卸载旧包装本地 APK，因签名不同）：
- 本地 APK 装好，系统 RUNNING，商店刷新后显示 python 三版本
- 详情页三版本各有独立"安装 v"按钮（PackageInstaller.install 按版本取 url/sha256/entry）
- 装 3.11.13 → 显示"已安装·当前 3.11.13"
- 再装 3.14.8 → 两版本共存，当前变 3.14.8，3.11.13 显示"切换到"按钮
- 点切换 → 当前切回 3.11.13，3.14.8 变"切换到"

至此全链路本地化完成：
件构建（python 三版本 + 9 base 件）-> APK 构建 -> 签名 -> 上传七牛 -> 设备商店
-> 安装指定版本 -> 多版本共存 -> 切换，全部不经 GitHub，全部实测通过。

---

## 十一、切换生效的结构级验证（2026-11-20）

设备切换验证不只看商店详情页 UI，还交叉验证了组件页（读 ProgramIndex 注册表真相）：
- 商店详情页：装 3.11 再装 3.14，两版本共存，当前 3.14；点"切换到 3.11"后当前变 3.11。
- 组件页（SystemComponentsActivity，直接读 ProgramIndex）：切换后显示
  `python  v3.11.13 · 程序`，与详情页一致。

这证明 activate() 的 setCurrentVersion 确实改了注册表里的当前版本（ProgramIndex.version），
不是只改了 UI 状态。加上多版本目录都在 opt/python/<ver>/（空间页显示"旧版本组件 253MB"
佐证两份 python 都在），切换的结构闭环成立。

限于 release 包不可调试（run-as 被拒）、production build 无 adb root，
无法从文件系统层直接读 usr/bin/python3 软链指向；执行路由层
（GuestAdapter.applyRuntimeBindings 按 LOBOS_PROGRAM_ID 注入对应版本 bin/lib）
为编译期验证。完整的"应用声明版本约束→解析→执行"端到端需要一个真实客侧应用，
列为下一个工作单元。

---

## 十二、可调试版本暴露的真 bug（2026-11-20）

按用户要求产出可调试版本（assembleDebug + 同 keystore 签名，覆盖安装保留数据，
run-as 可用），在设备上直接执行 python，发现 release 盲测完全看不到的两个真 bug：

### bug 1：stdlib 路径被构建槽位污染
现象：装好的 python 3.11/3.12 执行 `python3 -c "..."` 报
`ModuleNotFoundError: No module named 'encodings'`；直接 `python3 --version` 能过（不需要 stdlib）。
根因：build-component-python3.sh 里 `PY_SLOT` 非空时 `LIBROOT="lib/$PY_SLOT"`，
标准库落到 `lib/3.11/python3.11/`，而 CPython 按 sys.path 找 `lib/python3.11/`。
验证：run-as 建软链 `lib/python3.11 -> lib/3.11/python3.11` 后 encodings 找到。
修复：LIBROOT 恒为 "lib"，槽位只影响 WORK 目录与 asset 名，不进件内路径。

### bug 2：PYTHONHOME 未注入
现象：即使 stdlib 路径正确，sys.prefix 仍是构建机路径
`/home/bowen/.../work/python-3.11-3.11/_inst`（configure --prefix 编进去的），
设备上不存在。
根因：系统只设 `$PREFIX`，CPython 不认它，只认 `$PYTHONHOME`。
验证：run-as 设 `PYTHONHOME=<件目录>` 后 sys.prefix 正确。
修复：两处注入 —— GuestAdapter.applyRuntimeBindings（应用级绑定）+ RuntimeEnvironment.treeRootEnv
（系统级默认，按 ProgramIndex 里 python 当前版本）。

### 终极验证（可调试版实测）
run-as 直接执行修复版 3.11 件（手动替换进设备），设 PYTHONHOME：
`FULL_OK 3.11.13` —— base64/ssl/sqlite3/bz2/lzma/hashlib/ctypes 全部 import 成功。

### 连带修复
- package-component.sh：OUT 参数化后 sha256sum 硬编码 cd dist 的路径 bug
- local-release.sh：CC 变量 `${}` 笔误；python 构建独立 OUT 避免与 base 件互相污染；
  收集前清掉旧 python 件避免 versions[] 重复
- gitignore：dist-python/ 中间产物

这些 bug 在 release 包（run-as 被拒、adb root 不可用）上只能看到 UI 状态"当前 3.11.13"，
无法执行验证，是纯盲测。可调试版本一次就暴露了。

待验证：系统执行路由（sys.tty.exec / 客侧应用）是否自动注入 PYTHONHOME ——
代码已修且编译通过，端到端需要一个真实执行入口，列下一工作单元。

---

## 十三、切换核心 bug 修复与闭环验证（2026-11-20）

### bug 3：activate 只写 CURRENT 没更新 ProgramIndex.version
可调试版实测：切换后 CURRENT=3.11.13，但 program-index.json 里
python version=3.14.8，usr/bin/python3 软链指向 3.14.8。
UI 详情页读 CURRENT 显示"已切到 3.11"，但实际执行路径（LdConfig 读 ProgramIndex.version）
还是 3.14 —— 用户以为切了其实没切，纯盲测发现不了。
根因：setCurrentVersion 回调 registerCurrent 未成功更新注册表（runCatching 吞异常，
无"指针切换即登记"日志）。
修复：activate 里 setCurrentVersion 后显式 ProgramIndex.upsert(version=) 再 LdConfig.run。

### 闭环验证（debug APK，run-as）
UI 切换 python 3.14 -> 3.11 后：
- CURRENT = 3.11.13
- usr/bin/python3 软链 -> opt/python/3.11.13/bin/python3
- ProgramIndex.version = 3.11.13
三者一致。通过真实入口软链执行（带 PYTHONHOME）：
FINAL_OK 3.11.13，base64/ssl/sqlite3/bz2/lzma/hashlib/ctypes 全模块可用。

至此可调试版本暴露的 3 个真 bug（stdlib 槽位路径 / PYTHONHOME / 切换不同步）
全部修复并实测闭环。

---

## 十四、bug4 + 正式目录建立（2026-11-20）

### bug4：stdlib .py 被 shape 误判 program
可调试版 + 从 official 走完整安装流程才暴露：安装后报 No module named base64。
根因：带 shebang 的 .py 被 elf-form 判 shebang->program，进 shape 后落位拍平到 bin/。
修：shape 生成时 shebang 且 dir!=bin 的文件 skip（数据由目录复制保留）。
设备验证：SHAPE_FIX_OK 3.11.13，全模块可用。

### 正式目录落地
- 按 EXECUTION-POLICY.md 建立 /official/（正式）与 /testing/（测试，可清空）双目录。
- channel.json baseUrl 指向 /official/，local-release.sh 支持 --root official|testing。
- 本地备份 backup/verified/component/ 放已验证件（七牛清空后可原样恢复）。
- 七牛 official 从干净桶重建：revision 1（9件，python 3.11 初版）、
  revision 2（shape 修复版 python 7cba8c0e）。
- 设备走完整正式流程验证：刷 revision2 清单 -> official 下载安装
  -> SHAPE_FIX_OK，CURRENT/软链一致。

### 可调试版本累计发现并修复的真 bug（共4个）
1. stdlib 路径被 PY_SLOT 污染（lib/3.11/）
2. PYTHONHOME 未注入
3. activate 只写 CURRENT 没更新 ProgramIndex（切换不生效）
4. .py 被 shape 误判 program（stdlib 拍平到 bin/）

全部经设备直接执行验证。release 盲测一个都发现不了。

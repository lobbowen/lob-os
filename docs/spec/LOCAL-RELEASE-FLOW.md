# 本地发布流程（不经过 GitHub）

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

# LobOS 本地工程地图（2026-11-26 · 清理后）

## 一、一句话回答：工程在哪

**唯一的真实工程在 `/home/bowen/LOBOS/work/lob-os/`**（git 仓库，456 个跟踪文件）。
工作区 `/home/bowen/LOBOS/work/` 清理后只有三个顶层目录：`lob-os/`（工程）、
`external-materials/`（外部素材）、`archive/`（早期资料归档）。
工作根 `/home/bowen/LOBOS/` 也不再有历史备份目录。

### 工作区层（`/home/bowen/LOBOS/work/`）

见 `work/README.md`。三个目录：
- `lob-os/` — 本工程（唯一活跃 git 仓库）
- `external-materials/` — 外部素材库（AOSP/kernel/firmware/upstream，1.6G）
- `archive/` — 早期散落资料的归档（early-docs/early-assets/ndk-scripts/dsh-diag），
  2026-11-26 从 work/ 根目录收敛，保留历史可追溯，日常不需进入

## 二、清理后的工作根

```
/home/bowen/LOBOS/
  work/                        <- 主工作区
    lob-os/                    <- 【真实工程】git 仓库，当前 HEAD
    external-materials/        <- 外部素材库（1.6G，参考用）
    assets/ docs/ scripts/     <- 旧工作副本（非工程，保留参考）
    dsh-diag-*.sh/py           <- 早期诊断脚本
  bin/                         <- dsh 工具脚本（4个，保留）
  dsh-home/                    <- 【当前会话活跃】DSH_HOME，含凭据，绝不动
  dsh-plugins/                 <- DSH 插件
  keys-offline-backup/         <- 密钥离线备份（敏感，保留）
  lob-os-team/                 <- 团队协作目录 + ARCHIVE-AREA（291M，保留）
  manifest/                    <- 含 keystore 备份（敏感，保留）
  *.md                         <- 交接/规范文档（HANDOVER/README 等）
  dsh-lan-gateway.mjs          <- DSH LAN 网关
  start-*.sh                   <- DSH 启动脚本
```

## 三、本次清理动作（已执行，用户授权）

释放约 **44G** 空间：

| 已删除 | 大小 | 性质 |
|---|---|---|
| 14 个 backup/clean/archive/quarantine/snapshot 目录 | ~150M | 历次整理的副本 |
| `work/lob-os/dist-llvm/` | 31G | LLVM 构建产物 |
| `work/lob-os/work` 中的构建产物已确认保留源码工作区 | — | 见下 |
| `container/app/build/` `container/ui/build/` | 2.9G | Gradle 构建缓存 |
| `work/lob-os/dist/` | 590M | 分发产物 |
| `tmpwork/` | 3.3G | apk 临时工作目录 |
| `phone-data/` | 6.5G | 早期设备数据备份（app-data.tar + 362 apk） |
| `apk/` `migrate/` `container/`(空) `dist/`(空) `sdcard/` | 49M | 早期遗留 |

**保守保留（未删）**：
- `work/lob-os/work/`（6.2G）— 不是产物，是组件源码工作区（git-src/openssl/python/glibc-build 等），删了无法重建组件
- `keys-offline-backup/` `manifest/keys-backup-existing/` — 密钥备份
- `dsh-home/` — 当前会话活跃目录（DSH_HOME）
- `lob-os-team/` — 团队共享资料
- `bin/` — dsh 工具脚本

## 四、工程本体（`/home/bowen/LOBOS/work/lob-os/`）

git 跟踪 452 文件。**按行为逻辑归类，同类放一起**：

| 行为类别 | 目录 | 职责 | 性质 |
|---|---|---|---|
| 产品源码 | `container/app` | Android 宿主 + kernel/services Kotlin | git 跟踪 |
| 产品源码 | `container/ui` | Compose UI | git 跟踪 |
| 产品源码 | `container/native` | C 系统垫片 | git 跟踪 |
| 构建逻辑 | `scripts/` | toolchain/recipes/verify/publish | git 跟踪（145） |
| 构建输入 | `work/` | 组件源码工作区（tarball + 解压树 + 中间产物） | 不跟踪，可再生 |
| 构建产物 | `verified-components/` | 已验证组件 zip（原 backup/，8件跟踪） | 组件资产 |
| 构建产物 | `dist-glibc-runtime/` | glibc 组件产物 | 不跟踪 |
| 发布签名 | `keys/` `release/` | keystore + manifest/sig | 不跟踪，敏感 |
| 规范文档 | `docs/` `design/` `brand/` | 规范/审计/设计稿/品牌 | git 跟踪 |
| 编译补丁 | `patches/` | 上游补丁 | git 跟踪 |
| CI | `.github/` | 工作流 | git 跟踪 |

**关键区分（不再混淆）**：
- `work/` = 构建的**输入和中间态**（源码工作区，可再生）
- `verified-components/` `dist-glibc-runtime/` = 构建的**成品资产**
- `keys/` `release/` = 发布的**签名材料**
- 三类都是"构建/发布链路"，但行为不同，所以分三个目录，不再混在一起

工程内占用（清理后）：
- `work/` 5.6G — 源码工作区；已删有 tarball 的解压树（git-src/openssl/curl/zlib），
  需要时用 `scripts/toolchain/restore-source-trees.sh` 恢复
- `verified-components/` 351M — 已验证组件资产，不是垃圾

## 五、给新人的最短路径

```
改代码      -> /home/bowen/LOBOS/work/lob-os/container/
改脚本      -> /home/bowen/LOBOS/work/lob-os/scripts/
看规范/审计 -> /home/bowen/LOBOS/work/lob-os/docs/
看工程地图  -> /home/bowen/LOBOS/work/lob-os/docs/PATH-MAP-CURRENT.md
其它目录    -> 保留但非工程本体
```

## 六、验证（清理后）

- git 工作区干净，HEAD `a7ebdeaf`，452 跟踪文件
- 设备连接正常，dsh web 401，终端 PTY 稳定
- 构建产物已删，但源码和组件工作区完整——需要时重新跑构建脚本即可

## 七、待办（未执行，需确认）

1. `verified-components/`（351M）是已验证组件资产，不是垃圾；不要清理
2. `lob-os-team/ARCHIVE-AREA` 评估归档价值
3. `work/` 内组件源码工作区——如需进一步省空间，可按组件逐个归档，但**不能整体删**
4. 顶层 `*.md` 交接文档（HANDOVER 系列）评估归档

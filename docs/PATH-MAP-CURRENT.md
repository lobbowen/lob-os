# LobOS 本地工程地图（2026-11-26）

## 一、一句话回答：工程在哪

**唯一的真实工程在 `/home/bowen/LOBOS/work/lob-os/`**（git 仓库，451 个跟踪文件）。
其余目录要么是历史备份、要么是构建产物、要么是外部素材。

## 二、目录全貌

```
/home/bowen/LOBOS/                        <- 工作根（混乱，见第四节）
├── work/
│   ├── lob-os/                           <- 【真实工程】git 仓库
│   ├── external-materials/               <- 外部素材库（1.6G，参考用）
│   ├── assets/ docs/ scripts/            <- 旧的工作副本（非工程）
│   └── dsh-diag-*.sh/py                  <- 临时诊断脚本（11个）
├── tmpwork/                              <- 临时工作区（历史）
├── lob-os-team/                          <- 团队协作目录
├── apk/ container/ dist/ manifest/ migrate/ phone-data/ sdcard/
│                                         <- 早期工作目录（历史遗留）
└── [16个 backup/clean/archive/quarantine/snapshot 目录]  <- 历史备份
```

## 三、真实工程内部地图（`/home/bowen/LOBOS/work/lob-os/`）

### 3.1 git 跟踪的（451 文件）— 这是工程本体

| 目录 | 文件数 | 内容 |
|---|---|---|
| `container/app` | 大部分 | Android 宿主 + kernel/services Kotlin 代码 |
| `container/ui` | 16 | Compose UI |
| `container/native` | 15 | C 垫片（d1/d2/d3/guest-sigsys） |
| `scripts` | 145 | 构建/组件/校验脚本 + 配置 |
| `docs` | 33 | 规范/审计/诊断文档 |
| `.github` | 24 | CI 工作流 |
| `backup` `design` `patches` | 17 | 备份/设计/补丁 |

### 3.2 未跟踪但占空间的（构建产物，可清理）

| 目录 | 大小 | 性质 |
|---|---|---|
| `dist-llvm/` | 31G | LLVM 构建产物 |
| `work/` | 6.2G | 中间产物 |
| `container/app/build/` | 2.9G | Gradle 构建缓存 |
| `dist/` | 590M | 分发产物 |
| `backup/` | 351M | 工程内备份 |
| `container/ui/build/` | 6.3M | Gradle 构建缓存 |

**合计约 41G 是可重新生成的产物，不是源码。**

### 3.3 一个反常点：`.gitignore` 忽略 `*.md`

当前 `.gitignore` 有 `*.md`（只放宽了 kernel README），导致所有规范文档
（ARCHITECTURE-AUDIT / DIRECTORY-LAYOUT-STANDARD / VIRTUAL-ROOT-NAMESPACE 等）
都必须 `git add -f` 强加入库。这是历史遗留的坏习惯——文档是工程的重要组成，
不应该被默认忽略。**建议修正**：移除 `*.md` 忽略，改为只忽略明确的临时 md。

## 四、混乱根源：LOBOS 顶层（工作根）的 16 个历史目录

以下目录名字带 backup/clean/archive/quarantine/snapshot/step/migrate/tmp，
全部是某次整理时的备份副本，**不是工程**：

```
archive-clean-20261113-114103/
backup-05-stage4-20261103-081716/
backup-pre-cleanup-2026-11-03/
clean-backup-20261113-044351/
cleanup-backup-20261103-074122/
comment-clean-backup-20261113-104525/
dsh-home-quarantine-20261018/
keys-offline-backup/
marker-clean-20261114-013958/
migrate/
safe-clean-20261113-104706/
secure-backup-20261113-044251/
spec-clean-20261113-111918/
spec-snapshots/
step1-20261113-104802/
tmpwork/
```

建议：确认无保留价值后归档到一个 `_archive/` 目录或直接清理，让 LOBOS 顶层
只剩：`work/lob-os`（工程）、`work/external-materials`（素材）、
`README.md`、`HANDOVER-*`（交接文档）、必要的工具目录。

## 五、给新人的最短路径

```
想改代码       -> /home/bowen/LOBOS/work/lob-os/container/
想改构建/脚本  -> /home/bowen/LOBOS/work/lob-os/scripts/
想看规范/审计  -> /home/bowen/LOBOS/work/lob-os/docs/
想跑构建       -> /home/bowen/LOBOS/work/lob-os/ (gradlew/scripts)
其它目录       -> 都不是工程本体
```

## 六、建议的整理动作（按优先级，需用户确认后执行）

1. **修正 .gitignore**：去掉 `*.md` 全局忽略（低风险，立即）
2. **LOBOS 顶层清理**：16 个 backup/clean 目录归档到 `_archive/`（需确认无保留价值）
3. **工程内构建产物**：`dist-llvm/ work/ container/*/build/` 加入清理脚本，
   需要时重新生成（保留 `dist/` 用于发布则单独说明）
4. **工程内 backup/**：351M，评估后清理
5. **临时诊断脚本**：`work/dsh-diag-*.sh/py` 11 个，确认后清理

**注意**：以上清理涉及删除，必须先逐项确认，不擅自执行。本文档只做地图说明。

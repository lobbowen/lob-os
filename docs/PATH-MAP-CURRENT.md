# LobOS 本地工程地图（2026-11-26 · 清理后）

## 一、一句话回答：工程在哪

**唯一的真实工程在 `/home/bowen/LOBOS/work/lob-os/`**（git 仓库，452 个跟踪文件）。
工作根已清理，不再有一堆历史备份目录干扰。

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

git 跟踪 452 文件：

| 目录 | 内容 |
|---|---|
| `container/app` | Android 宿主 + kernel/services Kotlin 代码 |
| `container/ui` | Compose UI |
| `container/native` | C 垫片 |
| `scripts` | 构建/组件/校验脚本 |
| `docs` | 规范/审计/地图 |
| `.github` | CI |

工程内仍保留的占用：
- `work/` 6.2G — 组件源码工作区（保留）
- `backup/` 351M — 工程内备份（尚未清理，需进一步确认）

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

1. 工程内 `backup/`（351M）评估是否清理
2. `lob-os-team/ARCHIVE-AREA` 评估归档价值
3. `work/` 内组件源码工作区——如需进一步省空间，可按组件逐个归档，但**不能整体删**
4. 顶层 `*.md` 交接文档（HANDOVER 系列）评估归档

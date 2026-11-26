# LobOS 工程规范（唯一入口）

本文件是工程规范的总入口。后续所有开发、整理、审计工作都先读这一份，
再按下面的索引去读具体规范。规范之间若有冲突，以本文件的"硬规则"为准。

## 一、硬规则（不可违反）

这些是 2026-11-26 全项目整理后确立的纪律，后续工作必须遵守。

### 0. 根本原则：APK 是载体，APK 下是独立系统
- Android 环境是硬件层，APK 是我们在该环境里的安装载体——这是不可逃避的现实，不是缺陷
- 目标不是在 APK 之外另造系统，而是在这一个 APK 之内做出**完整的、自成体系的 LobOS**
- 因此：系统机制层要尽量独立于 Android 语义；对 Android 的依赖只允许出现在宿适配面，
  并通过 SPI 向系统层提供能力。判断依据永远是"APK 内的系统是否自成体系"，
  不是"是否脱离 APK"

### 1. 工作区与工程位置
- 唯一工作区：`/home/bowen/LOBOS/work/`
- 唯一 git 工程：`/home/bowen/LOBOS/work/lob-os/`
- 工作区只有三个顶层目录：`lob-os/`（工程）、`external-materials/`（外部素材）、`archive/`（早期资料归档）
- 不要在工作区根目录新建散落的 `assets/` `docs/` `scripts/` 或诊断脚本

### 2. 目录按行为逻辑归类
`lob-os/` 顶层每类逻辑只有一个目录：

| 类别 | 目录 |
|---|---|
| 产品源码 | `container/app` `container/ui` `container/native` |
| 构建逻辑 | `scripts/` |
| 构建输入（可再生） | `work/`（内部只分 `tarballs/ sources/ artifacts/ patches/ readings/` 五类） |
| 已验证组件资产 | `verified-components/`（不入 git） |
| 发布签名 | `keys/` `release/` |
| 规范文档 | `docs/` `design/` `brand/` |
| 编译补丁 | `patches/` |
| CI | `.github/` |

同类东西只放一个地方。禁止再出现一个逻辑散落 N 个目录的情况。

### 3. 版本库纪律
- 大文件（>50MB，如组件 zip、二进制、构建产物）**绝不入 git**
- 用 component-sources.json 钉值 + 本地保留 + .gitignore 管理
- 构建产物、缓存、解压树都可再生，不入 git
- 大文件一旦误入未推送的历史，先 reset 再重提，不要硬推（会卡死）

### 4. 分层纪律（在单一 APK 内）
- 依赖方向单向：宿主适配 → 应用 → 系统服务 → 系统机制 → native 垫片
- 系统机制层（现 `kernel/` 包）不得 import `services/device/api`；
  对 Android 的依赖必须收敛到宿适配面，通过 `kernel/spi/` 的 SPI 提供能力
- 逻辑独立性靠**包结构 + SPI + 校验脚本**保证，不靠物理拆成 Gradle module。
  APK 内可以是单一 app module，但系统机制层要像独立系统一样自洽：
  不认识业务词、不直接拿 Android Context、能力由宿主注入（见 DEEP-ARCHITECTURE-AUDIT）
- 新增跨层能力走 SPI，不直接 import 上层

### 5. 规范文档纪律
- 架构/目录/能力类规范入 git（用 `git add -f`，因为 .gitignore 忽略 *.md）
- ONE-STANDARD.md 是特例：不入库，按 `docs/spec/README.md` 管
- 诊断记录、过时方案放 `docs/` 但标注"历史"，不要和活规范混在一起
- 新规范先写进本文件索引，再写正文

## 二、规范索引

### 必读（动手前）
1. `docs/PATH-MAP-CURRENT.md` — 本地工程地图（目录在哪、什么在哪）
2. `docs/spec/DIRECTORY-LAYOUT-STANDARD.md` — 目录结构与命名规范
3. `docs/spec/ARCHITECTURE-AUDIT.md` — 架构审计（现状问题）
4. `docs/spec/ARCHITECTURE-REFACTOR-PLAN.md` — 重构方案（分阶段路径）

### 系统能力规范
- `docs/spec/ONE-STANDARD.md` — 安装与运行唯一标准（不入库，按 spec/README.md 管）
- `docs/spec/NODE-CLI-COMPATIBILITY.md` — Node CLI 通用兼容
- `docs/spec/VIRTUAL-ROOT-NAMESPACE.md` — 用户态虚拟根/ELF 加载
- `docs/spec/RUNTIME-VERSION-ARCHITECTURE.md` — 运行时版本管理

### UI / 交互
- `docs/spec/UI-STANDARD.md` — 界面规范
- `docs/spec/UI-DESIGN-SPEC-v1.md` — UI 设计
- `docs/spec/STATE-MODEL.md` — 系统状态

### 发布 / 组件
- `docs/spec/LOCAL-RELEASE-FLOW.md` — 本地发布流程
- `docs/spec/ARTIFACT-CLASSIFICATION.md` — 产出物定位
- `docs/spec/SELF-HOSTED-DISTRIBUTION.md` — 自建分发

### 历史 / 诊断（只读参考，不作为当前规范）
- `docs/BASE-CAPABILITY-GAPS.md` — 基础能力缺口诊断档案
- `docs/spec/UI-CONCEPTS.md` `UI-REFACTOR-PLAN*.md` — UI 演进历史
- `docs/COMPAT-LAYER-DISCUSSION.md` `NATIVE-LINUX-APPROACH-ANALYSIS.md` — 早期方案讨论

## 三、校验

所有校验脚本在 `scripts/verify/`。改动后按需要运行：

```bash
bash scripts/verify/verify-engineering-standard.sh   # 总入口：一次跑完下面所有
# 单项（需要时单独跑）：
#   verify-layer-deps.sh       分层：kernel 不依赖上层
#   verify-directory-layout.sh 目录：深度/文件数/包声明
#   verify-ota-anchor.sh       official 锚
```
注：`verify-apk-native.sh` 等依赖构建产物的校验在有 APK 时单独跑，不放进提交前总校验。

提交前最低要求：编译通过 + 分层/目录校验通过 + 产品三件套回归
（claude TUI / codex / dsh web）+ official 锚 intact。

## 四、变更流程

1. 读本文件 → 读对应规范
2. 改动遵守硬规则
3. 跑校验
4. 提交（大文件/生成物不入库）
5. 若改动影响规范，先更新规范再更新代码

本文件自身变更需同步更新索引与相关规范，保持单一事实源。

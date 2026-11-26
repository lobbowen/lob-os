# LobOS 目录结构与命名规范（第一阶段）

配套：`ARCHITECTURE-AUDIT.md`（架构审计）、`ARCHITECTURE-REFACTOR-PLAN.md`（重构方案）

## 一、规范目的

目录是新人理解项目的第一入口。当前有两类问题，其中第一类是根本性的：

**问题A（根本）：系统本体没有独立位置，被埋在 Android app 肚子里。**
核心系统代码（kernel 29 文件 / services 97 文件）当前位于
`container/app/src/main/java/lobos/` —— 要穿过 `container/app/src/main/java/lobos`
五层 Android 工程壳才碰到系统本体。Gradle 当前只有 `:app` 和 `:ui` 两个模块，
**没有独立的 kernel/system 模块**。这传递了一个错误信号：LOBOS 系统只是
Android app 的一个子目录，而不是与 app 并列的本体。必须把系统本体提升为
顶层 Gradle 模块，让它和 app 平级。

**问题B（次要）：职责边界模糊。**
- `services/app` 22 个文件混了 Activity / NavHost / ViewModel / 终端 / 程序组 / bashrc 生成
- `services` 整个包 97 文件（占 70%），成为"什么都往里塞"的垃圾桶
- `api` 层混了 Android TileService / ContextWrapper / 纯数据 / 客户端
- `scripts` 19 个顶层文件平铺，职责靠文件名猜
- 顶层 `docs` / `design` / `backup` / `patches` / `brand` 职责重叠

## 二、核心原则

0. **系统本体与 Android app 平级，不埋在 app 里** —— kernel/system 是顶层
   Gradle 模块，Android app 只是宿主，依赖它们而不是包含它们。这是最重要的一条。
1. **按职责分层，不按产品/功能堆砌** —— 目录回答"这是哪一层"，文件名回答"具体是什么"
2. **一个目录一个职责** —— 超过 15 个文件必须考虑再分
3. **模块内部层级 ≤ 4 段** —— `lobos/<层>/<域>/<文件>`；这是模块内的逻辑路径，
   不含 `container/<module>/src/main/java` 这类 Gradle 固定前缀（那不是我们的设计）
4. **Android 宿主适配单独隔离** —— `android.*` 依赖只允许在 host/device 层
5. **名字自解释** —— 目录名用名词（kernel/services/host/ui），不用缩写或产品名

## 三、目标目录结构（顶层模块视角）

系统本体与宿主 app 平级，都是顶层 Gradle 模块：

```
container/
  kernel/                    Gradle 模块 :kernel —— L1 系统内核（LOBOS 本体）
    src/main/java/lobos/kernel/
      spi/                   内核SPI契约（KernelServices等）
      ipc/ fs/ proc/ elf/    机制类（进程/文件/ELF）
      layout/ compat/ crypto/ 子系统
    build.gradle.kts         com.android.library（暂时依赖android API，见下）

  system/                    Gradle 模块 :system —— L2 系统服务
    src/main/java/lobos/system/
      supply/ registry/ supervise/ log/ power/ perm/
    build.gradle.kts         依赖 :kernel

  app/                       Gradle 模块 :app —— L4 Android 宿主（只有一个薄壳）
    src/main/java/lobos/app/
      MainActivity / host/ device/ adapters/
    src/main/AndroidManifest.xml
    build.gradle.kts         依赖 :kernel / :system / :ui

  ui/                        Gradle 模块 :ui —— 纯 Compose UI（已存在）

  native/                    C/C++ 系统垫片（独立目录，已是顶层）
```

关键点：
- **系统本体（`kernel/` `system/`）不再是 `app/src/main/java/lobos/` 的子目录**，
  而是在 `container/` 下与 `app/` 平级的独立模块。
- `app/` 变薄：只保留 Android 入口（Activity/Service/Receiver）和 SPI 适配器，
  是"宿主"而不是"系统的容器"。
- 新人打开 `container/`，第一眼就看到 kernel/system/app/ui/native 五个并列模块，
  立刻理解"这是系统 + 一个 Android 宿主"。

### 为什么暂时还是 Android library 而不是 pure Kotlin
kernel 层当前有 4 类 Android API 依赖（Context 20 / Log 2 / Os 2 / OsConstants 1）。
在阶段 1.5 用 KernelContext 抽象切断前，`:kernel` 先用 `com.android.library`
（和 :ui 一样），等 android 依赖清零后再改 pure Kotlin 模块。这不影响
"顶层独立模块"这个结构性目标——模块独立性先于依赖纯净度。

### 关键调整（对应当前混乱点）

| 当前位置 | 目标位置 | 理由 |
|---|---|---|
| `services/app/MainActivity/NavHost/TerminalRoute/*ViewModel` | `host/` 或 `ui/` | Activity/Compose 是 UI/宿主，不是服务 |
| `services/app/TerminalProfile/Runtime/Session` | `app/terminal/` | 终端是应用层能力 |
| `services/app/ProgramGroup/QuickApp*` | `app/program/` | 程序管理是应用层 |
| `services/app/PieceProvisioner/PreinstalledPieces` | `system/supply/` | 供应逻辑是系统服务 |
| `services/host/*` | `host/` | 名字就是宿主，应独立 |
| `services/reg/*` | `system/registry/` | reg 改为语义化名 |
| `services/log/*` | `system/log/` | 系统日志 |
| `api/StatusTileService` | `host/` | TileService 是 Android 组件 |
| `api/CapabilityBroker` | `api/` 保留但拆分 | 门面按能力域拆 |
| `device/*` | `host/device/` | 都是 Android 系统能力适配 |

## 四、scripts 目录规范

```
scripts/
  toolchain/       编译工具链（NDK/Node/glibc等）
  recipes/         组件构建配方
  verify/          校验脚本（分层/契约/产物）
  build/           构建编排（APK/镜像/发布）    ← 当前顶层run-*.sh迁入
  release/         发布相关（签名/渠道）        ← 当前scripts/publish并入
  component-sources.json  组件钉值表（保留顶层，是配置入口）
```

## 五、顶层文档规范

```
docs/
  spec/       规范与契约（系统能力的标准）
  audit/      审计与重构方案                    ← 新增，收拢ARCHITECTURE-* 
  reports/    验证报告/时序报告                 ← 新增
  README.md   文档索引
```

`design/` `backup/` `patches/` `brand/` 逐一评估：
- `design/` → 若是设计稿，归 `docs/design/`
- `backup/` → 移出主仓（归档或 .gitignore）
- `patches/` → 归 `scripts/patches/`（已有）
- `brand/` → 若是品牌资源，归 `assets/brand/` 或 `docs/brand/`

## 六、校验脚本

`scripts/verify/verify-directory-layout.sh`：
- Kotlin 包深度 ≤ 4
- 单目录文件数 ≤ 15（超限告警）
- 每个 Kotlin 文件的包声明与目录一致
- 目录职责白名单（host/ 之外不得出现 android.* import，阶段1.5 目标）

## 七、迁移策略（关键：低风险）

**把 kernel/system 提成独立模块 + 改 package 是高风险操作**（涉及 126 个文件、
Gradle 配置、Manifest、所有 import）。采用渐进策略：

1. **阶段 A（已完成，零风险）**：立规范文档 + 校验脚本，不改任何文件
2. **阶段 B（先做，中风险，最高价值）**：新建 `:kernel` 模块，把
   `app/src/main/java/lobos/kernel/` 整体移入 `container/kernel/`，package 保持
   `lobos.kernel.*` 不变（源码根从 app 的 java 换成 kernel 模块的 java，
   package 与相对路径都不变，只有物理位置变）。同步改 settings.gradle.kts
   和 app 的 dependency。这一步直接解决"系统本体埋在 app 里"的根本问题。
3. **阶段 C（中风险）**：同样方式新建 `:system` 模块，迁入 services 的系统本体
4. **阶段 D（高风险）**：模块内部按目标结构重组 package（kernel 内分层、
   services 拆分），配合阶段 1.5 的 KernelContext 切断 android 依赖
5. **阶段 E（低风险）**：api/app/host 的职责错配文件归位
6. **每阶段验证**：编译 + verify-layer-deps.sh + verify-directory-layout.sh +
   端到端回归（claude/codex/dsh/终端）

**阶段 B 是关键**：它不改变任何 package 名、不改任何 import、不改类名，
只是把 kernel 的物理位置从 app 肚子裡搬到顶层模块。对代码零侵入，
但对"项目结构的直观性"是质变——新人打开 container/ 就看到 kernel 与 app 平级。

## 八、不做的事

- 不为"好看"而改目录——每次调整必须有明确的职责理由
- 不一次性大规模重组（先搬物理位置，package 重组放后面独立阶段）
- 不改任何类名/对外 API
- 不破坏现有构建脚本的路径引用

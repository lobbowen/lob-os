# LobOS 目录结构与命名规范（第一阶段）

配套：`ARCHITECTURE-AUDIT.md`（架构审计）、`ARCHITECTURE-REFACTOR-PLAN.md`（重构方案）

## 一、规范目的

目录是新人理解项目的第一入口。当前问题不是"层级深"（Kotlin 最多 4 层，合理），
而是**分类标准不统一、职责边界模糊、一个目录塞太多不同职责的东西**：
- `services/app` 22 个文件混了 Activity / NavHost / ViewModel / 终端 / 程序组 / bashrc 生成
- `services` 整个包 97 文件（占 70%），成为"什么都往里塞"的垃圾桶
- `api` 层混了 Android TileService / ContextWrapper / 纯数据 / 客户端
- `scripts` 19 个顶层文件平铺，职责靠文件名猜
- 顶层 `docs` / `design` / `backup` / `patches` / `brand` 职责重叠

## 二、核心原则

1. **按职责分层，不按产品/功能堆砌** —— 目录回答"这是哪一层"，文件名回答"具体是什么"
2. **一个目录一个职责** —— 超过 15 个文件必须考虑再分
3. **层级上限 4 层** —— `lobos/<层>/<域>/<文件>`，不再深挖
4. **Android 宿主适配单独隔离** —— `android.*` 依赖只允许在 host/device 层
5. **名字自解释** —— 目录名用名词（kernel/services/host/ui），不用缩写或产品名

## 三、目标目录结构（Kotlin 源码）

```
container/app/src/main/java/lobos/
  kernel/                    L1 系统内核（不依赖Android/上层）
    spi/                     内核SPI契约（KernelServices等）
    ipc/ fs/ proc/ elf/      机制类（进程/文件/ELF）
    layout/ compat/ crypto/  子系统
  system/                    L2 系统服务（原services的系统本体）
    supply/                  组件供应与安装
    registry/                程序注册与状态（原reg）
    supervise/               进程监管
    log/ power/ perm/        系统基础服务
  host/                      L4 Android宿主适配（唯一允许android.*）
    Application / Service / Receiver
    device/                  Android系统能力适配（原device层迁入）
    adapters/                SPI宿主实现（KernelHostAdapters）
  app/                       L3 应用层（程序管理、终端会话、快捷应用）
    program/                 程序组与快捷应用
    terminal/                终端profile/session/runtime
  ui/                        纯Compose UI（独立module，已存在）
  api/                       对外能力门面（只做路由，不含Android组件）
```

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

**目录调整会改变 package 声明，风险高**。采用渐进策略：
1. **阶段 A（立即，零风险）**：写规范文档 + 校验脚本，不改任何文件
2. **阶段 B（低风险）**：只调整**文件名明显错配**的少数文件（如 StatusTileService 从 api→host）
3. **阶段 C（中风险）**：按目标结构迁移整个目录，用工具批量改 package 声明
4. **阶段 D（验证）**：编译 + 端到端回归

本阶段先做 A，把规范立起来；B/C 需逐个评估编译影响后执行。

## 八、不做的事

- 不为"好看"而改目录——每次调整必须有明确的职责理由
- 不一次性大规模移动（会制造巨大 diff 和合并冲突）
- 不改任何类名/API（只改目录和 package）
- 不破坏现有构建脚本的路径引用

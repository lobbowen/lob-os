# LobOS 目录结构与命名规范（APK 内独立系统）

## 一、根本前提：APK 是载体，不是缺陷

用户明确：**Android 环境是硬件层，APK 是我们安装在其中的载体；目标是在这一个
APK 之内做成一套完整的、自成体系的 LobOS**。不是脱离 APK 另造系统。

因此：
- 所有系统代码在 `container/app`（单一 Android app module）里是**正确的物理形态**
- "物理拆成 :kernel/:system Gradle module"不是目标，与 APK 载体的现实冲突
- 系统独立性靠**包结构 + SPI + 校验脚本 + 宿主适配隔离**实现，不靠物理拆分
- 判断标准永远是：APK 内的系统层是否自成体系、Android 依赖是否只出现在宿适配面

**问题（真正需要解决的）：**
- 系统层（kernel 29 文件 / services 97 文件）与 Android 宿主适配混在同一批包里，
  边界靠约定而非结构；kernel 有 25 处 android import，机制层没有能力抽象。
- `services/app` 22 文件混了 Activity/NavHost/ViewModel/终端/程序组/bashrc 生成
- `services` 97 文件成为"什么都往里塞"的垃圾桶
- `api` 层混了 Android TileService/ContextWrapper/纯数据/客户端
- 目录职责边界模糊，同类逻辑散落

## 二、核心原则

0. **APK 单 module 内做逻辑分层** —— 物理上就是 app module；逻辑上分为
   宿适配面 / 应用 / 系统服务 / 系统机制 / native，边界靠包名与 SPI，不靠 Gradle 模块
1. **按职责分层，不按产品/功能堆砌** —— 目录回答"这是哪一层"，文件名回答"具体是什么"
2. **一个目录一个职责** —— 超过 15 个文件必须考虑再分
3. **层级 ≤ 4 段** —— `lobos/<层>/<域>/<文件>`；不含 `container/app/src/main/java` 固定前缀
4. **Android 依赖收敛到宿适配面** —— 不是零 Android（APK 下不可能），而是
   `android.*` 只允许出现在 host/device 层；系统机制层通过 SPI 拿能力
5. **名字自解释** —— 目录名用名词，不用缩写/产品名；"kernel"若名不副实应正名

## 三、目标逻辑结构（APK 内）

```
container/app/src/main/java/lobos/          <- 单一 APK module
  system/            系统机制层（现 kernel/ 正名候选）
    spi/               能力契约（SystemServices 等）
    ipc/ fs/ proc/ elf/ layout/ compat/ crypto/
                       用户态机制翻译层，不认识业务词、不直接拿 Android Context
  services/          系统服务层（系统本体）
    supply/ registry/ supervise/ log/ power/ perm/
  app/               应用层（程序管理、终端、快应用）
    program/ terminal/ quickapp/
  host/              Android 宿适配面（唯一允许 android.* 的业务层）
    Application/Service/Receiver
    device/            Android 系统能力适配
    adapters/          SPI 宿主实现
  api/               对外能力门面（只做 transport + 按域分发的 handler）
  ui/                （Compose UI 在 container/ui 独立 module，纯渲染）
container/native/     C/C++ 系统垫片（事实上的 syscall 层）
```

关键点：
- 物理上仍是 `container/app` 一个 module + `container/ui`；这是 APK 载体的正确形态
- 逻辑上系统机制层要像独立系统一样自洽：能力经 SPI 注入、不识业务词、不直接依赖 Android
- 新人打开 `lobos/` 第一眼看到 system/services/app/host/api 五个逻辑层，
  立刻理解"这是 APK 内的一套系统 + 一层 Android 宿适配"

## 四、关键调整映射（渐进，不一次到位）

| 当前位置 | 目标位置 | 理由 |
|---|---|---|
| `kernel/*` | `system/*`（可选正名） | 现名"内核"名不副实，是用户态机制层 |
| `services/app/MainActivity/NavHost/TerminalRoute/*ViewModel` | `host/` 或 ui 装配 | Activity/Compose 是 UI/宿主 |
| `services/app/TerminalProfile/Runtime/Session` | `app/terminal/` | 终端是应用层能力 |
| `services/app/ProgramGroup/QuickApp*` | `app/program/` | 程序管理是应用层 |
| `services/app/PieceProvisioner/PreinstalledPieces` | `services/supply/` | 供应逻辑是系统服务 |
| `services/host/*` | `host/` | 名字就是宿适配，应独立 |
| `services/reg/*` | `services/registry/` | reg 改语义化名 |
| `api/StatusTileService` | `host/` | TileService 是 Android 组件 |
| `api/CapabilityBroker` | `api/` 保留但按方法域拆 handler | 门面不是业务实现 |
| `device/*` | `host/device/` | Android 系统能力适配 |

## 五、scripts / docs 目录规范

```
scripts/
  toolchain/ recipes/ verify/ build/ publish/
  component-sources.json  组件钉值表（顶层配置入口）
docs/
  ENGINEERING-STANDARD.md   总规范入口（docs 顶层）
  PATH-MAP-CURRENT.md       工程地图
  spec/       规范与契约
  audit/ reports/           审计与验证报告（按需）
```

## 六、校验脚本

- `verify-layer-deps.sh` — 系统机制层不得 import services/device/api；
  android import 收敛目标是"只在宿适配面"（当前 kernel 25 处是待还的技术债）
- `verify-directory-layout.sh` — 包深度、单目录文件数、包声明一致性
- `verify-engineering-standard.sh` — 总入口，一次跑完

## 七、迁移策略（低风险，APK 单 module 内）

1. **阶段 A（已完成）**：立规范 + 目录整理 + 校验脚本
2. **阶段 B（进行中）**：把 android 依赖从系统机制层收敛到宿适配面
   （PathProvider/AssetProvider SPI + KernelContext，恢复 android 检查）
3. **阶段 C**：拆上帝对象（PtySession / CapabilityBroker），切断 PtySession↔SysDev 环
4. **阶段 D**：services/app 的 5 个系统文件归位、UI 外壳归 host/ui 装配
5. **阶段 E（可选）**：`kernel/` 正名为 `system/`，让名字反映真实身份
6. 每阶段验证：编译 + 三校验 + 端到端（claude/codex/dsh/终端）

**不做**：不为逻辑分层去拆 Gradle module（与 APK 载体冲突）；不改类名/对外 API；
不一次性大重组。

## 八、不做的事

- 不把"系统仍是 APK"当问题——APK 是载体，独立性在 APK 内实现
- 不为"好看"改目录——每次调整有明确职责理由
- 不一次性大规模重组
- 不破坏现有构建脚本的路径引用

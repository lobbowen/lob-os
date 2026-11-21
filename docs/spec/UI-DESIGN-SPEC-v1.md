# LobOS UI 设计规范 v1（设计先行）

本规范是 UI 的唯一设计真相。先定设计、再写代码；任何界面实现必须能追溯到本文。
目标：产品级设计感 + 跟手交互。基于 Material 3 Expressive + Android 16 视觉语言，
结合 Agent OS（系统级常驻、运行时/组件管理）的产品定位。

---

## 一、产品定位与设计关键词

LobOS 是一个运行在 Android 上的 Agent OS，用户在这里管理运行时（python/node/go）、
组件与系统状态。它不是普通 App，是"系统管家 + 应用商店"的融合体。

设计关键词：**沉稳的系统感 + 克制的活力 + 明确的状态**。
- 沉稳：深色为底、层次清晰，像系统设置而非消费级 App。
- 活力：关键操作（停止系统、安装、版本切换）用有生命力的色彩与物理动效，但不花哨。
- 明确：运行中/已安装/失败等状态一眼可辨，用色、形、动效三重编码。

---

## 二、设计语言（Material 3 Expressive 落地）

### 2.1 色彩
- 使用 MaterialTheme colorScheme，动态颜色（Android 12+ 跟随壁纸）默认开启。
- 亮色/暗色两套：系统应用以暗色为主场景，亮色完整支持。
- 语义色（不硬编码，走 colorScheme 扩展）：
  - 运行中/成功：primary（暗色下用绿色系，如 dynamo 亮绿）
  - 警告/空间紧张：error 容器色
  - 已安装：tertiary 或 success
  - 未安装：outline 色 + 下载图标
- 高度表达：M3 用色调叠加（surfaceContainer / surfaceContainerHigh），不用重阴影。
- 禁止：自造十六进制颜色、GitHub Dark 式硬编码。全面改为 colorScheme token。

### 2.2 字体排版（Material 3 type scale）
- 大标题（系统管家/应用商店）：displaySmall 或 headlineMedium，粗体。
- 区块标题（版本/依赖/空间）：titleMedium，medium 字重。
- 正文：bodyMedium。
- 辅助信息（版本号/状态描述）：bodySmall，onSurfaceVariant。
- 数值（空间大小/版本数）：用 tabular figures 对齐。

### 2.3 形状与容器
- 卡片：M3 Card，shape = large（16dp）或 extraLarge（24dp），hero 卡用 extraLarge。
- 按钮：M3 Button/FilledTonalButton/OutlinedButton，全宽主操作用 FilledButton。
- 列表项：ListItem 或自定义 Card，高度 ≥ 72dp（触摸友好）。
- 间距节奏：8dp 基准，卡片间距 12dp，区块间距 24dp，页面边距 16dp。

### 2.4 动效（M3 motion springs，物理感）
- 导航切屏：spring，位置/形状变化用 spatial spring，轻快不拖沓。
- 状态切换（运行中↔停止、安装进度）：spring，有轻微回弹（expressive）。
- 列表项进入：渐进式 stagger，每项间隔 30-50ms，总时长可控。
- 关键原则：动效可中断、可重定向，按阻尼/刚度而非固定时长。
- hero moment 全 App 只留 2 个（见 2.6），其余动效克制。

### 2.5 图标
- 全部用 Material Icons（material-icons-extended），不再自绘 Canvas 图标。
- 状态图标语义固定：已安装=CheckCircle，未安装=FileDownload，运行中=PlayCircle/电源，
  停止=StopCircle，商店=ShoppingBag，组件=Apps，空间=Storage，我的=Person。

### 2.6 Hero moments（全 App 只 2 个）
1. 首页控制环：系统运行状态的环形指示器，点击启动/停止，带物理回弹与颜色过渡。
2. 详情页版本切换：选中版本时的卡片强调 + 确认动效，是全 App 最有"手感"的交互。

其余所有页面保持克制一致，不再制造第二个视觉焦点。

---

## 三、交互与跟手规范（根治"点不动/延迟"）

这一节是硬性标准，所有屏幕必须满足。

### 3.1 点击热区
- 任何可点击元素最小 48x48dp（Material 触摸目标标准）。
- 底部导航每项点击区覆盖整格（不是只点图标）。
- 卡片整块可点，ripple 覆盖整卡。
- 用户反馈"点不动"的根因之一就是热区太小/坐标漂移——本规范从尺寸上根治。

### 3.2 状态驱动，不手动重建
- 按钮 enabled/onClick/颜色全部由 UiState 派生，禁止 removeAllViews 全量重建。
- 点击后立即给视觉反馈（ripple + 状态变化），不等异步结果才响应。
- 异步操作：立即进入"处理中"state（按钮转圈/禁用+进度），完成后更新 state。

### 3.3 反馈时序
- 点击 → 0ms：ripple 立即响应（系统保证）。
- 点击 → <100ms：UI state 变化（如按钮变"安装中"）。
- 异步完成：state 更新，动效过渡，不闪烁。
- 失败：错误态就地显示（snackbar 或内联），不跳页、不丢上下文。

### 3.4 防重复点击
- 处理中的操作（安装/切换/刷新）按钮置 disabled 或显示进度，防止连点堆积。
- 事件处理幂等（ViewModel 侧去重）。

### 3.5 列表与滚动
- 用 LazyColumn/LazyVerticalGrid，稳定 key，滚动位置在 state 变化时保持。
- 折叠屏：内屏单列、外屏双列（WindowSizeClass），布局切换有动效。

---

## 四、信息架构与导航

5 个主 tab：首页 / 商店 / 组件 / 空间 / 我的。
- 首页（SystemHome）：系统总控 + 概况 + 故障入口。
- 商店（SystemStore）：选装件列表 → 详情（SystemInstall）。
- 组件（SystemComponents）：已装组件列表 → 组件详情（SystemComponentDetail）。
- 空间（SystemSpace）：空间占用与清理。
- 我的（SystemAbout）：关于/诊断/导出。
辅助页：SystemCheck（体检）、SystemRecovery（修复）、HostControl（宿主控制，不放主导航）。

导航统一底部 NavigationBar（Compose），每项是标准 48dp+ 热区，选中态用 primary 色 + 指示条。

---

## 五、11 屏逐屏设计稿

### 5.1 首页 SystemHome（hero moment 1）
目标：一眼看系统状态，一键控制，快速进入概况与修复。
布局（折叠屏：内屏上下、外屏左右分栏）：
- 顶部：大标题"系统管家" headlineMedium + 副标题 bodyMedium。
- 主区（左/上）：控制环——直径 ~200dp 的圆形进度指示器，中心大字"运行中/已停止"，
  环色 primary（运行）/error（停止）。点击整环 = 启停（hero moment：颜色过渡 + 弹性缩放）。
  环下小字：运行时长 + 常驻状态；异常时显示中断原因（可点展开）。
- 次区（右/下）：概况卡片组（M3 Card，large shape）：
  - 空间卡：图标 Storage + "空间" + 占用/可用（tabular figures）+ 可清理量。
  - 组件卡：图标 Apps + "组件" + 已装数量 + "查看状态 ›"。
  - 故障卡：图标 Build + "故障与修复" + 最近24h失败次数 + "进入处理 ›"。
- 主操作（FilledButton，全宽）：停止系统/启动系统（与控制环同状态）。
- 次操作（两个并排 OutlinedButton）：一键体检 / 清理空间。
状态：RUNNING / STOPPED / DEGRADED（降级时环色变 error + 顶部提示条）。
热区：控制环整圆可点（≥200dp）、每张卡整卡可点、按钮全宽。

### 5.2 商店 SystemStore
目标：浏览/安装选装件（含多版本运行时）。
布局：
- 顶部：标题"全部应用" headlineSmall + 刷新 IconButton（48dp，refreshing 时转圈）。
- 列表（LazyColumn，key=name）：每项 M3 Card（≥72dp），左侧状态图标
  （已装 CheckCircle/primary，未装 FileDownload/outline），中间应用名 titleMedium +
  版本/多版本数/大小 bodySmall（onSurfaceVariant），右侧箭头。整卡可点 → 详情。
- 已装项排在未装前（与现状一致），用分隔小标题"已安装/可安装"。
- 空态：插画区 + "还没有取到应用清单" + 刷新按钮（FilledTonal）。
- 错误态：错误文案 + 重试按钮，就地显示。
状态：Loading（居中进度）/ Ready(有数据/空)/ Error。
（本屏已实现 Compose，按此稿 review 视觉与热区。）

### 5.3 详情 SystemInstall（hero moment 2 + 最高交互密度）
目标：查看某件详情，选版本安装/切换/卸载。
布局：
- 顶部 hero 卡（extraLarge shape）：应用图标 + 名称 headlineSmall + 版本 bodyMedium。
- 版本卡（本屏核心）：标题"版本"，逐项列出所有版本：
  - 当前版本：高亮容器（primaryContainer）+ "当前"徽标（AssistChip）。
  - 已装非当前：显示"切换到 vX" OutlinedButton（48dp+）。
  - 未装：显示"安装 vX" FilledTonalButton。
  - 版本切换的确认动效 = hero moment 2（卡片强调 + spring 过渡）。
- 依赖卡：标题"依赖"，列出依赖运行时 + 版本区间；满足/缺失状态图标。
- 空间卡：标题"空间"，需要/可用（tabular figures），不足时 error 色提示。
- 底部主操作：安装/重新安装（FilledButton 全宽）、卸载（OutlinedButton，error 色文字）。
状态：安装进度四阶段（DOWNLOAD/VERIFY/LAND/LINK）用 LinearProgressIndicator + 阶段文案，
处理中按钮禁用防连点。
热区：每个版本操作按钮 ≥48dp；切换/安装互斥显示。

### 5.4 组件 SystemComponents
目标：列出已装与可装组件，分类展示。
布局：标题"组件" + 分组（已装 / 可装，带计数）——每组 M3 Card 列表，
每项图标 + 名称 + 版本 + 状态徽标 + 箭头，整卡可点 → 组件详情。
顶部可用搜索/筛选（搜索 IconButton，后续版本）。

### 5.5 组件详情 SystemComponentDetail
目标：单个组件的体积、依赖、安装/回滚与进度。
布局：hero 卡（名称+版本+角色）+ 能力/依赖卡 + 体积卡 + 进度卡（安装/回滚时显示四阶段）
+ 操作区（安装/回滚/卸载）。大件（>阈值）用 warning 色提示空间。

### 5.6 空间 SystemSpace
目标：展示空间占用与可清理项，执行清理。
布局：顶部大数字卡（占用/可用/总计，tabular figures）+ 环形或条形占用可视化 +
可清理项列表（旧版本组件/缓存，各带大小与"清理"按钮）+ 清理确认对话框。

### 5.7 体检 SystemCheck
目标：一键体检并展示结果清单。
布局：顶部"一键体检" FilledButton + 结果统计卡（通过 N 项/未通过 N 项，大数字）+
结果列表（每项：状态图标 + 标题 + 详情，M3 ListItem）。检查中显示进度。

### 5.8 修复 SystemRecovery
目标：故障修复入口与状态。
布局：故障摘要卡 + 修复动作列表（每项说明 + 执行按钮）+ 操作历史。
（当前较简，按此稿补齐结构。）

### 5.9 关于 SystemAbout
目标：版本信息、诊断导出、系统身份。
布局：应用标识卡（名称/版本/Git SHA）+ 诊断卡（导出日志按钮）+ 状态信息卡。
分组用 ListItem + Divider，克制。

### 5.10 HostControl HostControlActivity
目标：宿主能力控制（最复杂，最后迁）。
布局：按能力分组的卡片列表，每组含状态与操作；保留现有 15 处交互的语义，
统一为 M3 组件与 48dp 热区。此屏风险最高，ComposeView 可回退 View。

### 5.11 QuickAppLaunch QuickAppLaunchActivity
无 UI（intent 转发/执行入口），不参与设计。

---

## 六、设计到组件的映射（给前端）

- 大标题 → Text(style=MaterialTheme.typography.headlineMedium)
- 卡片 → M3 Card / ElevatedCard（shape large/extraLarge）
- 主按钮 → FilledButton；次按钮 → OutlinedButton；图标按钮 → IconButton(48dp)
- 状态徽标 → AssistChip / Badge
- 进度 → LinearProgressIndicator / CircularProgressIndicator
- 列表 → LazyColumn + ListItem / 自定义 Card
- 导航 → NavigationBar（5 项，选中态 primary + 指示条）
- 动效 → animate*AsState + spring（expressive）
- 图标 → Icons.Filled/Outlined（material-icons-extended）

验收：每个屏幕实现必须附 Preview（覆盖主要状态）+ UI test（点击热区与事件），
与本文布局稿对照。

---

## 七、评审要点（请用户确认）

1. 视觉方向：沉稳系统感 + 克制活力 + 明确状态，是否符合预期？
2. 两个 hero moment（首页控制环 / 版本切换）是否认可？
3. 亮色/暗色以暗色为主、动态颜色默认开，是否接受？
4. 5 tab 导航结构是否保持？
5. 11 屏逐屏布局是否有要调整的？

确认后，实施顺序：先按本稿重做商店/详情的视觉（已 Compose 化的直接改），
再逐屏迁移其余，每屏以本稿为验收标准。

---

## 八、首页定稿（用户确认 2026-11-20）

用户确认的第一屏设计语言（`design/mockup/home-final.png`），作为后续所有屏的真相：

### 结构（用户定义）
1. 无顶部标题栏。
2. 一体化主面板：上半是核心状态区，下半直接衔接 5 个功能入口，共用一块浅色面板。
3. 核心状态区：LOBOS Logo + 在线胶囊徽标 + 电源开关 + 大字"运行中" + 运行描述
   + 一行运行指标（内存/运行时版本/已装组件/活跃实例）。
4. 功能入口：一体化扁平条——白底、5 格平分、细竖线分隔、线性 SVG 图标 + 文字，
   顺序：终端 / 组件 / 空间 / 应用 / 更多。
5. 快速启动区：只放应用产品（开发者社区的应用），当前为空态；
   Python/Node 等运行时归属"组件"，不在此处。

### 视觉语言（设计感标准）
- 极浅冷灰背景 + 白色渐变面板；柔和光晕 + 三层柔和阴影 + 内描边；大圆角 30px。
- 排版：标题 42px/字重680/字距-.045em；标签全大写+.12em；数字 tabular-nums。
- 强调克制：绿色状态点 + 黑色胶囊"在线"，其余中性灰阶。
- 图标：线性 SVG、统一 stroke 1.7、视觉重量匹配文字；功能条细竖线分隔，非独立卡片。
- 所有状态（空/加载/错误/按下）按此语言设计。

### 功能定义（用户定义）
- 终端：新增功能，必须有。
- 组件：组件商店 + 统一管理入口（运行库/运行时/功能组件/工具）。
- 空间：系统唯一文件管理入口，像文件管理器。
- 应用：开发者社区创建、安装在系统里的应用。
- 更多：附加入口。

---

## 九、6 屏设计全部确认（2026-11-20）

用户确认全部 6 屏设计稿，进入实现：
- 首页 home-final.png（一体化状态面板 + 扁平功能条 + 应用空态快速启动）
- 组件 components-final.png（运行时/工具/基础库分组 + 多版本详情）
- 终端 / 空间 / 应用 / 更多 other-final.png
  - 终端：系统 shell，深色代码块 + 快捷命令
  - 空间：系统唯一文件管理入口，容量可视化 + 目录浏览（opt/usr/var/cache）
  - 应用：开发者社区应用，当前空态，不放组件
  - 更多：关于/体检/诊断/能力 API

实现要求：Compose + Material 3；保持定稿视觉语言；每屏 Preview + UI test；
按 15 条设计感清单验收。功能入口：终端/组件/空间/应用/更多。

---

## 十、交互式终端与 EACCES（2026-11-20）

### 事实链
- librivospty.so 是宿主底座（PIE 可执行），随 APK 内置，不是可安装件；
  build-kernel-compat.sh 编到 jniLibs，本轮补齐（此前 APK 一直缺失）。
- Android 10+ SELinux W^X：app 域不能直接 exec app_data_file 里的 ELF。
  bash 作为 PTY 会话初始程序能跑，是因为 PtySession.spawnOnSlave 用 ExecEntry 套了
  /system/bin/linker64；shell 内部 exec 的 python3/git 没经 ExecEntry，故 Permission denied。
- 环境不对会报"找不到前缀/.so"，不是 EACCES，二者要分开。

### 方案（已设备验证）
- 终端用 TerminalSession 交互式 shell（open/write/resize/close）。
- TerminalProfile.ensureBashrc 在每次会话启动前，遍历已装 program/multi-command 件，
  对每个 ET_DYN ELF 入口生成 shell function：`name() { linker64 <realpath> "$@"; }`，
  python 额外注入 PYTHONHOME/LD_LIBRARY_PATH；写 files/.bashrc，bash -i 自动 source。
- shell function 在当前 shell 内展开，内部只有 linker64（系统文件，允许 exec），
  绕开 shebang 与 app_data_file 直接 exec 两道墙；无 wrapper 脚本的 shebang 问题。
- 设备实测：交互 shell 里 python3 --version=Python 3.12.10、git --version=2.55.0。
- 排除 library 件与 bash/sh（避免递归）；集成为动态生成，装/卸后重进终端即刷新。

---

## 十一、系统电源管理原语 KernelPower（2026-11-21）

用户指出：对标 Linux，关机/开机/重启是系统基础能力，此前缺失。
澄清语义：APP 是管理界面（常驻），内核是它托管的类 Linux 运行时（可启停）。
启停针对内核，APP 管理界面始终在。

### 实现
- `lobos.services.power.KernelPower`：系统电源状态机，持久化到
  files/var/lib/power-state.json（state=RUNNING/HALTED）。
  - shutdown：写 HALTED + OsPhase.STOPPING + 触发监督池停所有程序
  - boot：写 RUNNING + OsPhase.RUNNING + 触发监督池按 desired 恢复
  - reboot：shutdown + 延迟 1500ms boot
- `SupervisorPool.sync()` 开头检查 KernelPower.isHalted：HALTED 时停掉所有
  supervisor 且不拉起（desired 不被篡改，开机后按原 desired 自然恢复）。
- `OsHostService.ACTION_SYNC_POOL`：让电源动作触发即时同步，不等 15s tick。
- 首页电源按钮二元（运行中=关机，停机=开机）；"更多"页电源分组含关机/开机/重启。
- HomeViewModel.running 改读 KernelPower 状态为权威。

### 设备实测（debug APK）
- 关机：power-state=HALTED、程序进程全停、APP PID 不变、首页"已停止"
- 开机：power-state=RUNNING、首页"运行中"
- 重启：journal 记录 shutdown(HALTED) -> reboot(1500ms) -> boot(RUNNING)，APP 全程在
- assembleDebug + UI test 编译通过

---

## 十二、PTY 子进程模型根治（2026-11-21）

用户要求终端标准稳定、不打补丁。从架构层修复 PTY。

### 根因（三 bug 同源）
- `pty-session.c` 的 child 在 setsid/TIOCSCTTY/dup2 后有 `for(;;) pause()` 死循环，
  导致后面的 execv 成死代码——native 从未真正 exec shell（诊断日志止于 dup2，
  无 "exec 即将做"）。真正的 shell 由 Java 侧 spawnOnSlave（ProcessBuilder）另起，
  没有 controlling terminal，故 bash -i 报 job control 错误。
- 同时 native child（pause 空转）和 Java 进程抢同一 slave，双进程模型导致
  runToCompletion 退出检测 -1（Java 等自己那个进程，native 退出信号被覆盖）。

### 架构修复（单一标准 PTY 模型）
- 删 native 死循环 → child 正确 execv（session leader + controlling tty）
- FRAME_READY 协议加 4 字节 child pid（slave\\0 + winsize8 + pid4）
- Java onReady 解析 nativePid；recordInLedger 用 nativePid（不再从 Process.toString 抠 pid）
- 删 Java spawnOnSlave（59 行重复 spawn）→ 程序只由 native child exec
- 删 Java exit-watch + onExited 里的 process.isAlive 判断 → 退出完全靠 native
  waitpid + FRAME_EXITED 的权威 status/signal

### 设备实测
- child 日志出现 "exec 即将做" + "signal 已做"（之前止于 dup2）
- 终端干净启动 bash-5.2$ 提示符，无 job control 报错
- 0 崩溃；exit 后会话正常结束，无卡死
- pid 传递验证：清日志重开终端，账本记录 "PTY 会话那进程已记账
  program=linker64 pid=29513"，nativePid 正确传到 Java（program 名显示
  linker64 是因 ExecEntry 用 linker64 加载 bash，pid 即最终 shell 进程）

---

## 十三、真终端模拟器重构（2026-11-21）

用户指出终端"假"：伪造提示符 lobos@os:~$ + 独立输入框 + 日志列表 +
"正在启动终端会话"占位残留。从架构层重构为标准终端模拟器。

### 根因
旧模型把输入从 PTY 剥出来做成独立 UI 控件、提示符是 UI 画的；
PTY 输出只当日志逐行 Append。不是终端模拟器，是"命令输入框+日志显示"。

### 架构重构
- TerminalBuffer(:ui)：解析 PTY 字节流的终端缓冲区，处理 \r \n \b \t、
  CSI（SGR 颜色）与 OSC 转义序列的标准状态机，维护行+光标+样式
- TerminalRuntime(app 单例)：进程级持有 buffer 与会话状态，Compose 只订阅快照；
  会话不随重组丢失（输入法弹出/窗口变化不再清空）
- TerminalScreen：渲染 buffer（按 StyledChar 颜色/bold），内容贴底；
  透明 TextField 仅作键盘入口，用户输入原样写 PTY，回显/提示符全部来自 shell；
  删除伪造提示符与假快捷命令区
- TerminalUiState.Ready(screen,connected) / TerminalEvent.Input(bytes)

### 设备实测（debug APK）
- 进屏即见 shell 提示符 bash-5.2$，无"正在启动"占位
- 弹键盘时内容保持贴底、不消失
- 输入 echo hi -> shell 回显 `echo hi` -> 输出 `hi` -> 新提示符，全链路来自 shell
- 0 崩溃

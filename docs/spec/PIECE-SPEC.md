# 件规范

本文件定义"一个件应该长什么样、怎么构建、怎么落位、怎么验证、怎么发布"。
目的：编件一次成型，不再反复返工。本规范是唯一约束，其余文档中的冲突表述以本文为准。

---

## 1 适用范围

本文约束所有进入件系统的产物（piece），包括必备件、能力件、后续新增件。
宿主底座（OsHostService / CapabilityBroker / 内置服务 / 垫片）不是件，不适用本文。

---

## 2 十条必须条款

### 条款一 身份与产物清单

件必须声明 id / version / abi / role / entry，以及 files[] 的 sha256 / mode / bytes。
entry 由字节形态推导，禁止人工填写。与 component-manifest.schema.json 同源。

### 条款二 ELF 解释器

可执行件的 PT_INTERP 必须指向设备上真实存在、且由系统自己分发的加载器。
本系统的具体值见 INTERPRETER-DECISION.md：`/data/data/lobos.os/files/usr/lib/ld-musl-aarch64.so.1`。

禁止两类写法：
- `/lib/ld-musl-aarch64.so.1`（设备上不存在，app 无权写 /lib）
- `/data/user/<uid>/...`（含 uid，不稳定；用等价的 `/data/data/<pkg>` 代替）

共享库（SHARED）与静态件（-static）不得有 PT_INTERP。
判别某一件能否被直接执行，判据只落在"这份字节的 PT_INTERP 是什么、那个路径在设备上是否存在"，
不许由件被登记成什么、或件叫什么来顶替。

### 条款三 加载器分发落位

musl 加载器与 libc（ld-musl-aarch64.so.1 与 libc.musl-aarch64.so.1）随 APK 分发，
开机落位到 files/usr/lib/，两个 soname 名都落。
加载器二进制不入库（gitignore），由 CI 按钉值从 Alpine 包生成。

### 条款四 RUNPATH / RPATH

本系统件的库查找走 LD_LIBRARY_PATH（由 RuntimeEnvironment 按登记表计算），
musl 加载器认它并传给子进程（历史实测）。禁止在件里烘焙构建机绝对路径的 RUNPATH/RPATH。
LD_LIBRARY_PATH 属 reserved env，件不得自行声明覆盖。
红线：LD_LIBRARY_PATH 只挂系统自己起程序那一层，禁止全局导出
（全局导出会让 bionic 的 rm/head 去加载 musl 库而崩，历史实测）。

### 条款五 依赖声明

逐件维护 DT_NEEDED 白名单与 requires 声明。共享库必带 SONAME。
同名库不得遮蔽宿主库，用 shlib_variant 机制区分。
件内 lib/ 目录必须在库搜索路径上。
纯 C 件用最小 musl sysroot（musl + musl-dev + linux-headers），不拉全套 C++ 工具链。

### 条款六 构建参数声明式

target / sysroot / 解释器注入点 / 补丁矩阵 / 静态件白名单 / buildSha 固定为声明式配置，
不散落在各 recipe 的硬编码里。
每个件包 component-meta.json 必须带 buildSha 与 buildRun（固化一致性校验要求）。
构建输出必须留日志（work/<piece>/build.log），失败可诊断。

### 条款七 落位派生物

库进 usr/lib，程序进 usr/bin，链保持链，由 piece-shape.json 唯一真源驱动。
落位目标重名时：内容相同（sha256 一致，软链指向同一真身）不算冲突；内容不同必须判红。
工具的 libexec/<id>-core/<id> 软链指向 ../../bin/<id>，不得拍平（拍平会让 git 子命令找不到）。

### 条款八 启动契约

任何入口不得依赖内核解析件内 PT_INTERP 之外的东西；所有可执行件靠条款二烘焙的解释器直接执行。
系统起程序时 ExecEntry 仍可按 PT_INTERP 选择加载器（兼容与兜底），但不是解释器正确性的依赖。
不改 exec-path.c 试图解决 musl 嵌套 exec：那是 bionic hook，musl 进程加载不了它
（实测 __errno / __register_atfork 缺失），改它无效，且嵌套 exec 由条款二的解释器解决。

### 条款九 验证

构建期断言 PT_INTERP / SONAME / NEEDED / 形态，并留阳性对照。
verify-runtime-elf.sh 对 musl 件加例外（现把 PT_INTERP 写死 linker64 会误判）。
设备侧必须验三个场景：系统启动、直接执行、嵌套 exec；另测非 user 0 场景（边界外，但需登记）。
件的 RUNPATH 含构建机绝对路径（如 /home/bowen/...）为禁止形态，必须判红。
判断件的 ABI 必须读 ELF 的 INTERP 段真实内容（readelf -l 的 INTERP 行 / file 的 interpreter 字段），
禁止用 readelf -p .interp 后 grep：静态件无 .interp 段时该读法会误报为 bionic（已发生过，见 MEMORY）。

### 条款十 发布与回滚

musl 与 bionic 用不同 tag（musl-<tool> / base-<tool>），物理隔离，避免"同名同版本换字节"闸门误判。
切换原子：要么全 musl，要么全 bionic，不混装。bionic 版本保留为回滚基线。

---

## 3 反复返工的成因（本规范要根治的问题）

此前每个件被反复重编十余次，根因是规范缺失，具体表现为：
- 解释器策略先在"前置加载器"、"烘焙 /data/user/0"、"位置无关"之间摇摆，没有定论（条款二、八根治）
- 件的 id 一度带 -musl 后缀，与取件校验期望的件名冲突（条款一根治：id 就是件名）
- cache-key 的 SRC_HASH 一度按 bionic 脚本算，asset 名不变导致复用坏件（条款六、十根治）
- 构建输出被 >/dev/null 吞掉，失败无从诊断（条款六根治：留日志）
- 架构断言在管道里重复读文件有竞态（条款九根治：先读值到变量再判断）
- 加载器随 APK 打包但装配器没装到 files/usr/lib（条款三根治）
- 落位重名判据把同内容软链误判为冲突（条款七根治）
- 验证脚本把 PT_INTERP 写死 linker64，musl 件误判（条款九根治）

本规范落地后，新增或修改件必须按十条逐条对照，不再边做边补。

---

## 4 与其他规范的关系

- ARTIFACT-CLASSIFICATION.md：定义哪些是件（本规范适用）、哪些是宿主底座（不适用）。
- STATE-MODEL.md：定义件的状态怎么显示（本管件本身，不管显示）。
- INTERPRETER-DECISION.md：条款二的实测依据与具体取值。
- ABI-MIGRATION-PLAN.md：条款十的迁移步骤（其中"烘焙 app 私有路径"表述以 INTERPRETER-DECISION.md 为准）。

# Lob OS 架构定论

每一次纠正架构时，把「发现的问题」与「纠正后的结论」记在这里。
**没有记录就会重复犯错。**

记录格式：问题 → 证据 → 结论 → 状态

---

## D1 ·系统视图不该逐程序设

**问题**
装件 8 处，环境三种做法并存：`InstanceHost` 用 ENV_CLEAR、`LocalExec` 用 ENV_INHERIT、
`InstalledRuntime` 自己拼 LD_LIBRARY_PATH、`PtySession` 继承宿主（而宿主是空的）。

**证据**
真机实测：宿主进程 `LD_LIBRARY_PATH` 为空，它自己都找不到我们装的库。
`services/` 设 `LD_LIBRARY_PATH` 的文件 4 个、设 `PATH` 的 4 个。

**结论**
系统视图由宿主设一次（`OsApplication.installSystemView`，JNI 改本进程 environ），
`execve` 层合并（`exec-path.c` 的 `env_merge_sys`）。
逐程序拼装的那一层后来被清理掉，只留 `RuntimeEnvironment.treeRootEnv` 一处。

**状态**：已落地。真机 logcat 证实系统视图 13 项写入宿主 environ。

---

## D2 ·入口不该由宿主猜

**问题** `GuestAdapter.programPlan` 写死 `node`；`PtySession`/`LocalExec` 却直接 exec。
「Lob OS 的程序是 JS」是从「用 node 跑」推出来的，不是事实。

**证据** Linux 内核 `fs/binfmt_script.c`：
```
40|  /* Not ours to exec if we don't start with "#!". */
42|  return -ENOEXEC;
79|  return -ENOEXEC;  /* No interpreter name found */
94|  return -ENOENT;
```

**结论** `ExecEntry` 只认 ELF 头与 `#!`，其余 ENOEXEC。
**不加 ShellLike 启发式**（那是发行版在 binfmt_misc 里注册的规则，不是内核默认）。
**不加 node 兜底**（Linux 上 libc 会把 ENOEXEC 原样返回；shell 自己 fallback 是 shell 的行为）。

**状态**：已落地。真机跑通 `#!`、ELF 两种入口。

---

## D3 ·共享库路径在 Android 上没有全局注册表

**问题** 「装一个库，所有程序立刻能用」拿不到。

**证据** 真机实测（linker namespace）：
```
__loader_android_init_anonymous_namespace  能取到（libdl 那层没有）
三参调用建 namespace                        成功（ns=0x1）
unsetenv("LD_LIBRARY_PATH")
dlopen("<usr/lib>/libz.so.1")              not found
```

**结论** namespace 做的事是「把几个 so 串起来」，不是「共享的搜索路径表被所有进程读」。
Linux 靠 ldconfig 写一个所有进程共享的文件，Android 的 linker 不读任何共享文件。
这是平台边界，不是设计缺陷。**不做 wrapper绕过去**（与 D2 方向相反：入口自己声明 vs 系统替程序决定）。

**状态**：已落地。`docs/architecture/LIBRARY-PATH.md` 记了实测与复查方法。

---

## D4 ·装件要记清单并清旧软链

**问题** `usr/bin` 里躺着 6 条本该在 `usr/lib` 的库软链，改对了旧的没人清。

**证据** 真机实测 `linkEntry` 建的 `usr/bin/libbusybox.so` 指向不存在的路径。

**结论** 新增 `EntryLinks`：装件前按清单删 + 扫 `usr/bin`/`usr/lib` 删任何指向该件的软链。
`linkEntry` 按 entry 位置建软链（`lib/` → `usr/lib`，`bin/` → `usr/bin`）。

**状态**：已落地。装机验：`usr/bin` 只有 `bash` 与 `rg`。

---

## D5 ·device 是独立一层，不属于 kernel

**问题** `kernel/` 3016 行里，越界 1688 行（56%）。其中 `kernel/device/` 8 个文件 1084 行，
含 `permission`、`manifest`、授权流程、PNG 压缩、存储目录——而 `kernel/README` 明令不许有这些词。

**证据** 全查数据：真内核约 1328 行（44%）。

**结论** `device/` 独立成层，回答「这个设备允许我们做什么」。
判据一句话：**它回答的是设备允许什么吗？是→device，不是→kernel/services/api。**
截屏、无障碍、通知、权限全在这一层。**它们不是「系统服务」**（见 D7）。

**状态**：已落地。装机验，manifest 11 个组件名正确。

---

## D6 ·电源策略不属于 kernel

**问题** `kernel/power/` 4 个文件 107 行，问的是「要不要省电」「要不要唤醒」——那是决策。

**结论** 归 `services/power/`。

**状态**：已落地，装机验。

---

## D7 ·「内核不许用业务词」管的是类型名，不是磁盘文件名

**问题** 规范被自己违反 4 次（`piece`/`program`/`manifest`）。

**查清了**：`layout/` 零命中；`crypto/` 的 11 处全是 `program-manifest.json` 这个**文件名**，
而它被 7 个文件跨层引用（kernel 读+验签、services 写、api 读）。

**结论** 改名要重打 8 个已发布的件包，收益只是让 grep 好看。**改规范，不改文件名。**

**状态**：已落地。`kernel/README` 补了适用边界。

---

## D8 ·架构论证的四条结论（外部调研支撑）

**C1 状态用文件暴露** —— 只有「事实与发现」这一半是唯一解。
systemd 自己的状态面是 D-Bus 属性（方法表），不是文件。**事实走文件，动作走方法。**

**C2 系统服务独立建表** —— **不成立，撤回**。
systemd（类型后缀）与 Android init（`on` / `service` 两条语句）都是单表+类别，没有分表先例。

**C3 环境设一次然后继承** —— 有更优替代。
systemd 是「spawn 时编译 envp」，`UnsetEnvironment` 末步删除；继承只是特例。

**C4 不自造驱动层** —— 方向对，措辞过强。上游有 CUSE/VDUSE（用户态驱动），
但 `D2` 就是 Unix 常态。`/dev /sys /proc` 是 API filesystem，不是不稳定的存储。

---

## D9 ·Android 能借什么、不能借什么（硬限制）

**证据**
```
seccomp 层拉黑（SECCOMP_BLOCKLIST_APP.TXT）
    init_module / delete_module / mount / umount2 / chroot / reboot
SELinux neverallow（private/app_neverallows.te）
    /proc/config.gz、sysfs、debugfs、cgroup、selinuxfs
GKI arm64（gki_defconfig:39）
    # CONFIG_PID_NS is not set
init/Kconfig:1235
    config USER_NS / default n
```

**结论**
借得到的：Binder IPC、FBE/metadata encryption、KeyMint/Keystore2、AVB/dm-verity、pVM（AVF，仅 ARM64）。
借不到的：内核模块装载、mount/chroot、Linux namespace、镜像替换。
**系统与硬件之间只有驱动关系**——这与「Android 是硬件」是同一件事。

---

## D10 ·进程模型（这一条最容易被自己违反）

**结论**
```
程序        = 一个可执行文件 + 一份系统视图 + 一个生命周期
入口怎么跑  = ExecEntry（Linux binfmt 语义），入口文件自己声明
生死谁管    = SupervisorPool + UnitJobs，我们自己的
它碰 Android 吗 = 不碰；只有显示（Bitmap）与 syscall 那一层借Android
```

**推论（反复用到）**
- 终端是一个**能力**，不是「Android 的东西」。`PtySession` 356 行全是自己写的。
- 截屏、无障碍、通知是**device 能力**，按需调用，**不是系统服务**。
  我曾把它列进「系统服务」，那违反了自己写的 D5 判据。

---

## D11 ·规范要有机器能验的那一道

**问题** D5/D6/D7 定的规范被自己违反了五次：
`piece`、`program`、`manifest`、`permission`、`ProgramPackageVerifier`。

**为什么光写文档不够** —— 那几次不是不知道规范，是没人拦。

**结论** 新增 `scripts/verify/check-layer-boundary.sh`，作为独立一步进 `build-apk.yml`：

- `kernel/device/` `kernel/power/` 不许重新出现（D5/D6）
- `kernel/` 下的类型名不许含 `program` `catalog` `ota` `piece`
  `permission` `manifest` `quickapp`（D7，Kotlin 大驼峰，故按「标识符含该词」匹配）
- **不管磁盘上的契约文件名**（D7 已明确）

**它第一次真跑就抓到了 `ProgramPackageVerifier`** —— 已改名为 `PackageVerifier`。

**四种情况的门禁自测**（都过）

| 造的违规 | 结果 |
|---|---|
| `kernel/device/` 目录 | 拦住 |
| `kernel/power/` 目录 | 拦住 |
| `object ProgramRegistry` / `class PieceStore` | 拦住 |
| `fun readProgramManifest()`（磁盘文件名） | **不拦** ← D7 允许 |

---

## 文档归属

这一份与它引用的两份随代码进仓（都在 `docs/` 下），
因为它们是【代码的真相】—— 门禁会直接指向它们。
关于流程与发布的那几份在仓外（发布流程、验收清单、审计报告），
因为那些不随代码版本走。

## 怎么用这份文档

**动架构前先读它。** 若某个动作与某条冲突，
要么改方案，要么明写「我要推翻 D 几条，依据是 X」。

不要出现「上一轮说过什么我已经不记得了」。

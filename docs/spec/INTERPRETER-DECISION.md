# 件解释器裁决（实测定案）

本文结束"解释器方案"的讨论。结论基于本机与设备的实测，不基于候选偏好。

---

## 1 裁决

**烘焙 PT_INTERP 为设备真实存在的绝对路径：**
`/data/data/lobos.os/files/usr/lib/ld-musl-aarch64.so.1`

件的 `DT_NEEDED` 依赖的 libc 用 `libc.musl-aarch64.so.1`（与加载器同名同源）。
`LD_LIBRARY_PATH` 只在系统自己起程序那一层设置，不全局导出。

---

## 2 为什么（实测证据，不是推理）

### 2.1 否决"改 exec-path.c 垫片 + 位置无关件"

第二份调查认为嵌套 exec 断的结构成因是 `exec-path.c:410-422` 写死 `/system/bin/linker64`，
改它即可。本轮设备实测推翻了这条：

```
musl 加载器 + LD_PRELOAD=liblobosposix.so 跑 musl bash：
  Error relocating liblobosposix.so: __register_atfork: symbol not found
  Error relocating liblobosposix.so: __errno: symbol not found
```

`liblobosposix.so` 是 bionic ELF；musl 加载器加载不了它。
也就是说 exec-path.c 的 hook **在 musl 进程里根本不存在**，改它的逻辑毫无作用。
"位置无关件 + 前置加载器"只在**我们主动起进程**那一层成立（ExecEntry），
覆盖不了 musl 程序内部发起的 exec。所以这条路线不成立。

### 2.2 确认"必须烘焙绝对路径"

03 历史真机记录（MEMORY.md:4024-4033）逐字：

- 不烘焙时：加载器前置能跑，但程序自己 exec 自己的子命令全断（git 报 cannot exec 'maintenance'）
- 烘焙成 `/data/user/0/lobos.os/files/usr/lib/ld-musl-aarch64.so.1` 之后，直接执行与嵌套 exec 都通
- LD_LIBRARY_PATH 能被 musl 加载器认并传给子进程
- 绝不能全局导出（bionic 的 rm/head 会去加载 musl 库，报 CANNOT LINK EXECUTABLE）
- 件布局要真实：libexec/git-core 里的软链指向 ../../bin/git，拍平会让子命令找不到

本轮实测复核了其中关键两条：
- 加载器前置跑 musl bash，bash 内部 exec `id` 成功（INNER_EXEC_OK）
  但这只是在**外层**前置了加载器，子进程 `id` 恰好是 bionic 且不需要我们的垫片；
  git 这类"musl 程序 exec 自己的 musl 子命令"依然要靠 PT_INTERP。
- musl 进程加载 bionic posix hook 失败（见 2.1）

### 2.3 消除"多用户路径会变"的风险

第二份调查指出 `/data/user/<uid>/` 含 uid，工作资料下会变，烘焙它脆弱。此点成立。
本轮实测找到稳定等价路径：

```
stat /data/data/lobos.os        -> 65096:1970471
stat /data/user/0/lobos.os      -> 65096:1970471
在 /data/data/lobos.os 建标记 -> /data/user/0/lobos.os 立即可见
```

二者是同一 inode。`/data/data/<pkg>` 是 Android 标准的、不随 uid 出现的路径写法。
因此烘焙 `/data/data/lobos.os/files/usr/lib/ld-musl-aarch64.so.1`，
既兑现历史验证的"绝对路径能通"，又避免把 `/data/user/0` 写死。

边界：本系统是单用户嵌入式定位；若将来支持 Android 工作资料（user 10），
`/data/data` 是否仍指向当前用户数据目录必须重新实测，不在本次结论覆盖范围。

---

## 3 两条红线

1. **LD_LIBRARY_PATH 只挂我们自己的 exec**：全局导出会让 bionic 命令去加载 musl 库而崩。
2. **不改 exec-path.c 来试图解决 musl 嵌套 exec**：musl 进程加载不了那个 bionic hook。

---

## 4 对件规范的影响

- 件规范条款应写"PT_INTERP 必须指向设备上真实存在、由系统自己分发的加载器"，
  并在此系统里具体化为 `/data/data/<pkg>/files/usr/lib/ld-musl-aarch64.so.1`。
- 禁止写 `/data/user/<uid>/...`（不稳定）与 `/lib/ld-musl...`（设备不存在）。
- 这是"位置相关"的件，绑定包名与安装路径，属于本系统的既定取舍；
  通用 Linux 发行版的"位置无关件"不适用于 Android app 私有目录这种部署形态。

---

## 5 与两份调查的关系

- 第一份（按 Lead 预设 A/B/C/D 框架）推荐"位置无关 + 前置 + 静态 launcher"：
  核心判断被本轮实测 2.1 否决（musl 进程加载不了 bionic hook，前置层覆盖不了嵌套 exec）。
- 第二份（指出 A/B/C/D 无定义、推荐甲+乙并行）方向正确：
  它确认"解释器真相源是 PT_INTERP"、"exec-path.c 是第二条路径"，
  但"改 exec-path.c 即可覆盖嵌套 exec"这一点被本轮实测 2.1 否决。
  它的"多用户路径脆弱"提醒促成了 2.3 的稳定路径方案。

# fastroot 迁移方案：双引擎架构

基于 FASTROOT-INTEGRATION-STATUS.md 的验证结果：fastroot 在无 root 的 LobOS
APK 进程里成功运行 glibc 程序并实现了虚拟根路径翻译（open+read 读到 rootfs 内容、
exit 0）。本文给出把 fastroot 作为"Linux 语义引擎"集成进 LobOS 的可执行方案。

## 一、核心判断

LobOS 当前执行层是 LD_PRELOAD hook（liblobosposix.so），覆盖 bionic 程序；
后来补了 glibc VFS hook（liblobos-vfs-glibc.so），覆盖 glibc 程序的路径翻译。
但 LD_PRELOAD 路线有天花板：覆盖不了静态 ELF 的 raw syscall，多 uid/chroot/
硬链接//proc 合成做不全。

fastroot 用 syscall 指令补丁（不用 ptrace）在同一 APK 进程里做到了这些。
它不是替换 LobOS，而是替换 LobOS 的"底层执行引擎"。

**关键约束**：fastroot 只支持 glibc 程序（README 明确 Alpine/musl 不支持）。
LobOS 的 node/claude/codex 走 musl 路径，bash/busybox 走 bionic——这些必须保留
现在的路径。所以最终是**双引擎**。

## 二、目标架构

```
程序启动请求 (Terminal/Instance/API)
          |
    ExecEntry 形态判定 (已有)
          |
   +------+------+--------+
   |             |        |
 glibc ELF    musl ELF   bionic/静态
   |             |        |
 fastroot      musl loader  现有 exec-path
 launcher      + shim      (LD_PRELOAD hook)
   |             |        |
   +------+------+--------+
          |
   能力门面 / 服务管理 / 包管理 / 会话 (全部保留)
```

## 三、分层职责

### fastroot 引擎负责（新增/替换）
- glibc 程序的虚拟根、chroot、bind mounts
- fake root uid/gid/caps、chown 存 xattr、setuid 位
- 硬链接（hidden store + nlink）
- /proc 合成（cmdline/environ/status/auxv）
- 静态二进制（Go/Rust）syscall patch
- SysV IPC、fake NETLINK（按需）
- execve 子进程经 launcher 重新 patch（统一路由）

### LobOS 保留（不动）
- 服务管理（SupervisorPool/InstanceHost/init 状态机）
- 包管理（component-sources/pieces/验签/OTA）
- 能力门面（CapabilityBroker/ApiSpec）
- node shim 注入（node 是 musl/glibc 官方二进制）
- musl loader + claude/codex 特化路径
- UI/终端/终端 PTY
- 会话鉴权/桥协议

### LobOS 替换/废弃（迁移完成后）
- liblobosposix.so 的 VFS 翻译部分（fastroot 接管）
- liblobos-vfs-glibc.so（fastroot 接管）
- SIGSYS 假成功 4242（fastroot 的 fake root 接管，统一 uid 视图）
- node shim 的 getuid 伪造（fastroot fake root 接管）
- link-interpose.c 的 copy（fastroot 真硬链接接管）

## 四、分阶段迁移

### 阶段 1：完整 rootfs 验证（当前卡点，需下载 Ubuntu rootfs）
- 拉 Ubuntu 24.04 minimal arm64 root.tar.xz（76MB，后台下载中）
- 用 fastroot 跑 boot 到 /bin/sh
- 跑 fastroot 的 compat.sh（174 项），记录与真实 Linux 的差距
- 验证 fake root / chroot / 硬链接 / /proc 合成四项关键能力

### 阶段 2：双引擎路由
- ExecEntry 形态判定增加 fastroot 分支：glibc ELF → fastroot launcher
- 保留 musl/bionic 现有路径
- 关键：两个引擎的虚拟根视图必须一致（都指向同一个 rootfs）
- 验证：同一程序走两个引擎的行为差异（差分基准）

### 阶段 3：能力与包管理适配
- 包安装到 fastroot rootfs（FHS 布局 /usr /etc /opt）
- 能力门面不变，path 参数翻译到 fastroot 虚拟根
- 保留现有 component 供应体系

### 阶段 4：逐步废弃旧 hook
- 迁移稳定后，移除 liblobos-vfs-glibc.so
- liblobosposix.so 精简为仅 bionic 路径
- 统一 uid 视图（移除 4242 假成功）

## 五、风险与开放问题

1. **双引擎虚拟根一致性**：fastroot 的 FR_ROOT 与我们的 LOBOS_VROOT 必须指向
   同一目录，但两者对用户态路径的翻译规则不同（fastroot 更严格，`..` 钳制）。
   需要差分基准验证一致性。
2. **musl 程序的能力差距**：node/claude 走 musl 路径，拿不到 fastroot 的
   fake root/chroot。是否需要 musl 版 fastroot？目前没有。
3. **性能**：fastroot 的 syscall 补丁在程序启动时扫描 text（有 site cache），
   对频繁启动的程序（CLI 工具）是否有回归需实测。
4. **构建链**：fastroot 源码 vendored 在 work/external/（不入库），
   build-fastroot.sh 可重建；需要决定是否入库 vendored 源码以保证可复现。
5. **许可证**：fastroot Apache-2.0，与 LobOS 兼容；需保留 NOTICE。

## 六、当前状态

- 已验证：fastroot 在 APK 进程跑 glibc 程序 + 虚拟根翻译成功
- 未验证：完整 rootfs、compat.sh、fake root/chroot/硬链接/proc
- 已固化的资产：build-fastroot.sh、libfr-launch.so/libfr-shim.so（可重建）、
  FASTROOT-INTEGRATION-STATUS.md（验证记录）
- 下一步：等 Ubuntu rootfs 下载完，执行阶段 1

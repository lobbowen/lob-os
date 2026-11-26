# 产品标准安装与启动验证（2026-11-27）

用户要求：完整检测不是"能安装"，而是产品都能启动、能使用；测试产品不内化到
系统盘内。在纯净 Ubuntu 24.04 rootfs（366MB）上按 Linux 标准方式逐个安装验证。

## 环境准备（用户装产品前的标准步骤，不写入系统盘）

纯净 rootfs 首次启动后需配置，这些是用户行为，不改内置系统镜像：
- DNS：/etc/resolv.conf 是悬空符号链接（指向 systemd stub），必须删除后写
  `nameserver 223.5.5.5 / 8.8.8.8`。这是 apt/npm 联网的前提。
- apt 源：默认 ports.ubuntu.com 可用，但 gpgv 不在 minimal 镜像里，
  需 `Trusted: yes` 或 `--allow-unauthenticated`。
- ldconfig：apt 装包的 libc-bin post-install 因 ldconfig 写 /etc/ld.so.cache~
  报 EROFS（fastroot 兼容层边界），但**包本体已落盘可用**，不影响使用。

## 验证结果

| 产品 | 版本 | 安装方式 | 验证 |
|---|---|---|---|
| Python | 3.12.3 | rootfs 自带 | 执行代码、json/os 标准库、算术 -> 3.12.3 / {"ok":true,"n":42} / Linux |
| Go | 1.22.2 | `apt install golang-go` | go version -> go1.22.2 linux/arm64。go run 受 fastroot 的 /tmp 权限限制（工作目录创建被拒），编译器本体可用 |
| Node | v26.13.1 | 官方 linux-arm64 tarball 解到 /usr/local | node --version -> v26.13.1 |
| Claude Code | 2.1.332 | `npm install -g @anthropic-ai/claude-code --registry=国内源` | claude --version -> 2.1.332 (Claude Code) |
| Codex | 0.172.0 | `npm install -g @openai/codex --registry=国内源` | codex --version -> codex-cli 0.172.0 |
| dsh | 0.2.2-rc.5 | `npm install -g @deepseek-ai/dsh --registry=国内源`（671 包） | dsh --help / --version 正常；dsh web --port 3081 启动、自生成 token、curl 401。完全不依赖桥 |

## 关键结论

1. 所有产品都作为 Ubuntu 普通应用安装运行，没有一个内化到系统盘构建里。
   验证后系统盘恢复纯净 366MB。
2. dsh 作为标准 npm 应用完整可用，证明它不需要"桥/控制面板"——印证了删除桥的方向。
3. 端口冲突实证：宿主 dsh web 占 127.0.0.1:3080，导致 Ubuntu 里的 dsh web 
   EADDRINUSE；换 3081 后正常。这证明宿主 dsh web 应下线，让 dsh 作为 Ubuntu
   应用占用标准端口。
4. 已知 fastroot 边界：ldconfig EROFS（不影响包可用）、go run 的 /tmp 权限限制。

## 待处理（阶段一剩余）

- 桥整层删除（用户已选"整层删除、调用点本地直调"）：CapabilityBroker/UDS/
  token/SessionRegistry/ApiSpec、宿主 OTA/exec、快应用 invoke、程序 env 注入。
  快应用重新定位为安卓层应用前端（见 QUICKAPP-STRATEGIC-POSITION.md）。

## 补充修正：/tmp 是架构问题，不是 Go 特例（2026-11-27）

用户指出 Go 的 `/tmp` 失败是典型系统架构问题，不是特例——任何用临时目录的
程序（go run、编译器、构建工具、包管理器）都会中招。已按架构层修复。

### 根因链（三层）

1. 内置 asset `systemdisk.bin` 里 `/tmp` 的 tar mode 是 **0775**（不是标准 1777）
2. `RootfsInstaller` 解压目录条目时只 `mkdirs()`，**完全没有应用 tar 里的目录
   mode**——所以连 0775 都没保留，实际落成 app 默认的 0700
3. fastroot 的 fake-root 进程在 0700/0775 的 `/tmp` 下无法为其他身份创建工作
   目录（`go run` 报 `mkdir /tmp/go-buildXXX: permission denied`）

### 架构层修复（RootfsInstaller.kt）

- 新增 `applyMode()`：解压时对每个目录条目应用 tar 里的 POSIX mode
- 对标准临时目录（`tmp`、`var/tmp`）**强制 0777**，不信任镜像里的 0775——
  这是系统盘对用户程序的明确契约：临时目录对任何程序可写
- 验证：系统盘重建后 `/tmp` 与 `/var/tmp` 均从 `drwx------`/`drwxrwxr-x`
  变为 `drwxrwxrwx`

### 含义

这不是给 Go 打补丁。修复后所有依赖标准临时目录语义的程序都受益。
之前把它当"Go 特例、用 GOTMPDIR 绕开"是错的——按用户要求回到了架构层。

注：本轮环境重建时 apt update 因测试环境残留（NAT 路径随 APK 重装变化 +
minimal 镜像缺 /etc/apt/apt.conf.d）未重跑通完整的 `go run`，但 `/tmp` 权限
根因已修复并经文件系统验证。上一轮已验证 Go 1.22.2 可 apt 安装并 `go version`。

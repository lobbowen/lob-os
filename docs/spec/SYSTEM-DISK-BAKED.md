# 系统盘内置决策（2026-11-27）

用户明确：Ubuntu rootfs 是系统本体，应直接内置完整干净版本，不再是下载机制；
node/claude/codex 等是验证系统能力的安装，不是系统的一部分，不应内置。

## 决策

- 内置：纯净 Ubuntu 24.04 minimal rootfs（release-20261124）
- 不内置：node / npm / claude / codex / apt 装的任何包——这些是用户/验证装的应用
- rootfs 是系统盘，首次启动从 APK asset 解压到 app_data，是硬前置，不是可选下载

## 实现

- `assets/system/systemdisk.bin`：纯净 rootfs 的 gzip 包（106MB，来自
  ubuntu-24.04-minimal-cloudimg-arm64-root，解压后 366MB）
- `noCompress += "bin"`：asset 以 Stored 方式进 APK，AssetManager 可直接打开
- `RootfsInstaller.kt`：首次启动后台线程从 asset 解压到 `filesDir/ubuntu`，
  处理符号链接（/bin -> usr/bin）、路径穿越防护、执行位；完成后写 marker
- `OsApplication.onCreate`：后台线程调用 install；不阻塞主线程
- `UbuntuSystem.isReady`：检查 marker + bash/loader/launcher/shim
- `UbuntuSystem.root`：统一指向 RootfsInstaller.root

## 验证（均已通过）

- 从内置 asset 全新解压：marker 写入，rootfs 366MB，`bin/sh -> dash` 符号链接正确
- 在纯净系统盘上按标准方式验证能力（不作为内置产物）：
  - apt update（USTC 国内源，Trusted: yes 绕过 gpgv 缺失）
  - apt install libatomic1（node26 依赖）
  - 官方 node v26.13.1 tarball 部署到 /usr/local（node 自带 npm 11.20.0）
  - npm install -g claude-code / codex（registry.npmmirror.com 国内源，10-18 秒）
  - node --version / claude --version (2.1.332) / codex --version (0.172.0) 正常
- 验证后清理系统盘，恢复纯净 366MB 版本

## 关键点

- 系统盘保持纯净，能力验证不改变内置产物——这是用户强调的边界
- 用户要在系统里装什么，由用户通过 apt/npm 自己做（标准 Linux 方式）
- musl 特化路径（380MB+314MB 二进制）可以摒弃：标准 npm 安装的 claude/codex
  已证明在 Ubuntu+fastroot 下正常工作

## 系统盘镜像的生成（不入库）

systemdisk.bin 是 106MB > 工程规范的 50MB 入库上限，不进 git（.gitignore 已排除）。
构建/CI 需按以下方式生成，放到 `container/app/src/main/assets/system/systemdisk.bin`：

1. 下载 Ubuntu 24.04 minimal cloudimg arm64 rootfs：
   USTC 镜像 `mirrors.ustc.edu.cn/ubuntu-cloud-images/minimal/releases/noble/`
   的 `ubuntu-24.04-minimal-cloudimg-arm64-root.tar.xz`
   SHA256 e1d9307663b7f4409f71574450a5694178694e51971413e7bab52b45e1173a7c
2. 解包为 tar 后重新 gzip（保留符号链接与执行位），命名 systemdisk.bin
3. build.gradle.kts 的 noCompress 含 bin，确保 asset 以 Stored 进 APK

## 待办

- 删除 musl 特化资产与代码（下一步，需确认降级路径处理）
- 把系统盘生成脚本固化进 CI（当前为手工生成）

# 设备全链路安装验证（2026-11-27）

目标：在设备 Ubuntu 系统盘上按 Linux 标准方式，先装运行时再装产品，
逐个启动抓真实版本/帮助输出，证明系统能稳定运行 Linux 等价产品。
所有安装都是验证期行为，不进系统盘构建。

## 环境准备
- apt：DNS 223.5.5.5/8.8.8.8 + USTC ubuntu-ports 源 + Trusted:yes（gpgv 缺失）
- apt update 成功（38.1MB，12 个 Packages 索引）
- 已知边界：libc-bin post-install 的 ldconfig 报 EROFS（fastroot 文件路径限制），
  但包本体已落盘可用，不影响运行。

## 运行时验证（全部通过）
| 运行时 | 版本 | 验证方式 |
|---|---|---|
| Python | 3.12.3 | `python3 -c 'import sys,json;...'` 实际执行代码 |
| pip | 24.0 (python 3.12) | `python3 -m pip --version` |
| Go | 1.22.2 linux/arm64 | `go version`（apt golang-go） |
| Node | v26.13.1 (LTS Lithium) | 官方 tarball 部署到 /usr/local |
| npm | 11.20.0 | `node npm-cli.js --version` |

## 产品验证（全部通过，版本 + 帮助输出）
| 产品 | 版本 | 帮助首行 |
|---|---|---|
| Claude Code | 2.1.332 | `Usage: claude [options] [command] [prompt]` |
| Codex | 0.172.0 | `Codex CLI` + commands (agents/exec/review) |
| dsh | 0.2.2-rc.5 | `Usage: dsh [--profile] <name> [options] [app-args...]` |

所有产品通过 `npm install -g --registry=https://registry.npmmirror.com` 安装。
codex/dsh 的 bin 是 `#!/usr/bin/env node` 脚本，用 `node <entry.js>` 执行；
claude bin 可直接执行。

## 结论
系统在纯净 Ubuntu 24.04.5 rootfs 上，从 apt 基础运行时到 npm 产品全链路
安装启动成功，三个产品都显示正确的 CLI 帮助与版本，判定系统可用。
验证后清理设备上安装的运行时/产品，系统盘恢复纯净 366MB。

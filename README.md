# LobOS

LobOS 是一个运行在 Android 硬件层之上的 Linux 兼容系统。
工程源码、构建、规范都在这个仓库里。

## 开始之前先读

**工程规范唯一入口**：[`docs/ENGINEERING-STANDARD.md`](docs/ENGINEERING-STANDARD.md)

它定义了工作区位置、目录归类、版本库纪律、分层规则、规范索引和校验方式。
所有开发、整理、审计工作从这一份开始，再按索引读具体规范。

## 最快路径

- 工程地图（什么在哪）：`docs/PATH-MAP-CURRENT.md`
- 目录规范：`docs/spec/DIRECTORY-LAYOUT-STANDARD.md`
- 提交前校验：`bash scripts/verify/verify-engineering-standard.sh`
- 产品三件套：claude TUI / codex / dsh web

## 工作区

工作区是 `/home/bowen/LOBOS/work/`，本仓库是其中唯一的 git 工程。
工作区结构见 `work/README.md`（上层）和 `docs/PATH-MAP-CURRENT.md`（本仓库）。

# SystemRoot 结构下的全程序验证（2026-11-27）

目标：验证 SystemRoot 抽象建立后，"Linux 能装的都能跑"。从终端 UI 按标准
方式安装多类程序，逐个抓 UI 实际输出。验证后恢复纯净系统盘。

## apt 运行时（终端 UI 实测，全部成功）

一次 apt install 装 python3/pip/golang-go/ruby/perl，UI 显示完整日志：
- Python 3.12.3
- pip 24.0（python3 -m pip --version）
- Go 1.22.2 linux/arm64
- Ruby 3.2.3 [aarch64-linux-gnu]
- Perl 5, version 38, subversion 2

## Node + npm CLI（终端 UI 实测，全部成功）

- node 官方 tarball 解压 5828 文件到 /usr/local
- node -v → v26.13.1
- npm -v → 11.20.0
- npm install -g @anthropic-ai/claude-code @openai/codex → 成功
- claude --version → 2.1.332 (Claude Code)
- codex --version → codex-cli 0.172.0
- claude 参数解析真实生效（-help 报 unknown option，证明程序真在运行）

## 关键结论

SystemRoot 结构（hostRoot/canonicalRoot/cwdHost/FR_* env 单一真相源）建立后：
- apt 装任意语言运行时可直接跑
- npm 装全局 CLI 可直接敲名字启动（不再需要原生 binary 路径绕过）
- 所有输出在终端 UI 真实可见可滚动
- node process.cwd() = /root，提示符 root@localhost:~#，与 Linux 一致

这验证了架构方向：系统盘结构正确后，执行链路是通用的，不需要按产品打补丁。

## 清理
验证后删除所有验证期安装，系统盘恢复 366MB 纯净版
（无 node/claude/codex/apt 索引残留）。产品未内化到构建。

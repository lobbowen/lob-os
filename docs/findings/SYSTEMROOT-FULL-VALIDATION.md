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

## 补充：apt update 的 N: Missing Signed-By 提示（2026-11-27）

用户指出测试里 apt update 有报错。复查确认：

```
N: Missing Signed-By in the sources.list(5) entry for 'https://mirrors.ustc.edu.cn/ubuntu-ports'
```

性质：apt 的 Note 提示（非 W/E），不影响 apt update 完成（rc=0、
Fetched 38.1MB、Reading package lists... Done）。

根因：这是我为国内测速**手动**把源改成 USTC + `Trusted: yes` 引入的，
去掉了 Signed-By。纯净系统盘出厂自带的 `ubuntu.sources` 本来是规范的：
`URIs: http://ports.ubuntu.com/ubuntu-ports/` + `Signed-By:
/usr/share/keyrings/ubuntu-archive-keyring.gpg`（从 asset 内
`./etc/apt/sources.list.d/ubuntu.sources` 提取确认）。

验证：恢复出厂源后从终端跑 apt update，完整 UI 输出：
```
Get:9..34 http://ports.ubuntu.com/...
Fetched 38.3 MB in 44s (880 kB/s)
Reading package lists... Done
root@localhost:~#
```
零 N:/W:/E 提示。出厂源慢（44s vs USTC 5s）但配置正确。

结论：**不要手动改 apt 源**。出厂源即标准 Linux 配置；之前为测速改 USTC
是错误做法，引入了不规范配置。后续所有验证保持出厂源不动。

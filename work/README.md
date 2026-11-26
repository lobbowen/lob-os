# work/ 组件构建工作区

本目录是 LobOS 组件构建的输入和中间态，所有内容均可再生成（不进 git）。
按行为逻辑分为 5 类，其余 55 个符号链接只为兼容旧脚本路径，指向以下分类目录。

## 分类目录

| 目录 | 职责 | 内容 |
|---|---|---|
| `tarballs/` | 下载的源码包（构建输入） | git.tar.xz / openssl.tar.gz / curl.tar.gz / node-glibc.tar.xz / component-*.zip 等 |
| `sources/` | 解压后的源码树（编译输入） | bash / busybox / crypto / jq / python* / pnpm / rg / zlib 等 |
| `artifacts/` | 编译产物（可再生） | glibc-build / glibc-runtime / llvm / node-glibc-unpack / kernel-compat / lh / .toolbin |
| `patches/` | 上游源码补丁 | compat-posix.h.patch / config.c.patch / disable-fdsan.patch 等 9 个 |
| `readings/` | 验证读数与声明 | declared.json / m0-m2-readings.json / official-declared.json / local-revision 等 |

## 符号链接兼容层

脚本历史上引用 `work/bash`、`work/curl.tar.gz`、`work/declared.json` 等平铺路径。
整理后这些路径是指向分类目录的符号链接（55 个），脚本无需修改即可继续工作。
新增构建时，优先使用分类目录路径（如 `work/tarballs/xxx.tar.gz`），旧路径保留兼容。

## 可再生的解压树

`git-src` / `openssl` / `curl` 的解压树曾为省空间删除，保留 tarball。
需要时运行：
```
bash /home/bowen/LOBOS/work/lob-os/scripts/toolchain/restore-source-trees.sh
```
该脚本从 tarballs/ 重新解压到 sources/ 并建链接。

## 注意

- 本目录 5.6G，主要是 `sources/`（python/llvm 相关源码）和 `artifacts/`（glibc-build/llvm 编译产物）
- 不进 git（.gitignore 中 work/）
- 清理原则：tarball 保留、源码树可由 tarball 再生、产物可由 recipe 重建

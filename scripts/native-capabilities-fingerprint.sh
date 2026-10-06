#!/usr/bin/env bash
# 原生件固化包的源指纹。命中固化就不重新编译，指纹变了就必须重固化。
#
# ── 为什么范围要包括「配方」而不只是「源码」──
# 原先只有两项：container/native/** 与 build-native-capabilities.sh 自身。
# 但原生件的来源不止自有 C 源码 —— 还有**上游源码配方**，而配方参数住在别处：
#   · build-native-capabilities.sh 里的 BASH_VER=5.2.15（写在脚本里，随脚本哈希走）
#   · scripts/userland-sources.json 里的 zlib/openssl/curl 版本与 sha256（**不在脚本里**）
#   · scripts/bionic-compat.c（bash 配方要编的桩）
#   · scripts/verify-userland-build-date.sh（判构建时间基准，改了判据就变了）
# 漏掉的后果很具体：改 userland-sources.json 里的 openssl 版本，指纹不变 →
# CI 命中旧固化包 → **用旧 openssl 编的产物**继续发布，而清单与源码已经声明了新版本。
# 这类「改了配方却不生效」最难查，因为它看起来一切正常。
#
# 读法：这里列的每个文件，任一字节变化都会让固化失效。
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)"

{
  # 自有 C 源码
  find container/native -type f | sort
  # 编法脚本（含 BASH_VER 等内联配方参数）
  echo scripts/build-native-capabilities.sh
  # 上游配方：底座共享库
  echo scripts/build-base-libs.sh
  # 配方依赖的桩与判据
  echo scripts/bionic-compat.c
  echo scripts/verify-userland-build-date.sh
  # 上游源码的钉值表（版本 + sha256 + 下载地址）—— 改这里必须触发重固化
  echo scripts/userland-sources.json
} | while read -r f; do
  if [ ! -f "$f" ]; then
    echo "[error] 指纹输入缺失: $f —— 该文件被删/改名会让固化悄悄沿用旧产物。" >&2
    exit 1
  fi
  sha256sum "$f"
done | sha256sum | cut -d' ' -f1
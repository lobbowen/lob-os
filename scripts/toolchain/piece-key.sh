#!/usr/bin/env bash
# 算本件的固化标识 —— 各件的 build-<id>.yml 共用这一段。
#
# 输出三个 GITHUB_OUTPUT：
#   key    缓存键（增量编译用）
#   tag    Release tag（<筐>-<件名>）
#   asset  资产名（含依赖与 NDK/API，所以依赖一变名字就变 = 重新固化）
#
# 为什么统一放这里：11 个 workflow 的这一段曾经各自留成空壳（只有注释），
# tag/asset 输出是空串，于是「已固化就复用」永远不命中 —— 固化机制从未真正工作。
# 单点定义，11 处引用，不会再各自漂移。
set -euo pipefail

id="${1:?usage: piece-key.sh <件名>  （component-sources.json 里的 id）}"
work="${2:-$(pwd)}"

run() {
  local k v
  k="$(bash scripts/toolchain/cache-key.sh "$1" "$id")" || {
    echo "::error title=$id 算不出 $1::cache-key.sh 报：$k"
    exit 1
  }
  echo "$k"
}

{
  echo "key=$(run key)"
  echo "tag=$(run tag)"
  echo "asset=$(run asset)"
} | tee -a "$GITHUB_OUTPUT"

echo "[key] $id → tag=$(run tag)  asset=$(run asset)"
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

# ★ dispatch 依赖顺序（2026-10-16 记，因为同一条原因已经让 crypto 红了两次）：
#   crypto 必须等 openssl —— build-crypto.yml:47-49 是 fetch openssl 的 asset 再 unzip
#   取里面的 libcrypto.so；openssl 还没编完时那个 asset 不存在，crypto 必然失败。
#   其余七件互不依赖，可以并行 dispatch。
#   ⇒ 顺序：先并行 dispatch 其余七件 → 等 openssl 绿 → 再 dispatch crypto。
#
# ★ 2026-10-20 补锚定：本脚本原先零锚定（无 HERE/ROOT_DIR），第 25 行用 `bash scripts/...`
#   这种依赖调用者 cwd 的相对路径 —— CI 在仓库根调用它所以当前对，**从仓库根以外调用立刻错**。
#   现与同族其余脚本及被它调用的 cache-key.sh:5-6 对齐（HERE + ROOT_DIR 跟脚本走）。
HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

id="${1:?usage: piece-key.sh <件名>  （component-sources.json 里的 id）}"
work="${2:-$(pwd)}"

run() {
  local k v
  k="$(bash "$ROOT_DIR/scripts/toolchain/cache-key.sh" "$1" "$id")" || {
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
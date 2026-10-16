#!/usr/bin/env bash
# 源码指纹的完整性判据 —— 2026-10-16。
#
# 它查的不是「哪个路径写错了」（那需要知道正确答案），
# 而是「有没有东西压根没算出来」—— 而后者只需要「它们不该相同」。
#
# 为什么必须独立于 cache-key.sh 自身：
# 一个值「算对了」与「压根没算出来」在输出上都是 12 位十六进制，格式上不可区分。
# 2026-10-16 实测：SRC_FILES 里三个路径在仓内并不存在，
# 而 crypto 与 git 拿到的都是空输入的 sha256（e3b0c44298fc）——
# 也就是说方案甲对这两个件一直静默失效，而没有人看出来。
#
# 判别形式：一个值「算对了」与「压根没算出来」在输出上可区分吗？
# 不可区分 ⇒ 必须有一条独立于它自身的检查。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TOOLS=(zlib openssl crypto curl jq bash ripgrep busybox git node python sqlite)

# 字节同源的件：它们**应当**同指纹。
# crypto 的 libcrypto.so 是从 openssl 的包里拆出来的（build-crypto.yml:49 unzip dist/$OASSET），
# 而 openssl 的 recipe 只被它自己 SRC_FILES 收录 —— 若不给 crypto 同一份，
# 「改了 openssl 而 crypto 没变」就会被漏掉。
# 而反过来说：它们同指纹是**预期**，所以不判重。
SAME_SOURCE="crypto:openssl"

declare -A HASH_OF=()
declare -A PAIR_OF=()
BAD=0

for t in "${TOOLS[@]}"; do
  a="$(bash scripts/toolchain/cache-key.sh asset "$t" 2>/dev/null || true)"
  [ -n "$a" ] || { echo "::error title=$t 算不出 asset 名::cache-key.sh asset $t 无输出"; BAD=$((BAD+1)); continue; }
  h="$(printf '%s' "$a" | sed -n 's/.*+src\([0-9a-f]*\)\.zip/\1/p')"
  [ -n "$h" ] || { echo "::error title=$t asset 名里没有 src 指纹::$a"; BAD=$((BAD+1)); continue; }
  # 空输入的 sha256 恒为这个值，而它只可能来自「一个文件都没列进去」。
  # 这一条比「互不相同」更强：路径写错时未必与人重复，但**必然**落在这个常量上。
  if [ "$h" = "e3b0c44298fc" ]; then
    echo "::error title=$t 指纹是空输入的哈希::$h —— 它的 SRC_FILES 一个文件都没找到（路径写错，或该件没有 recipe）"
    BAD=$((BAD+1))
    continue
  fi
  HASH_OF["$t"]="$h"
  PAIR_OF["$h"]="${PAIR_OF[$h]:-}${PAIR_OF[$h]:+,}$t"
done

for h in "${!PAIR_OF[@]}"; do
  p="${PAIR_OF[$h]}"
  case "$p" in *,*)
    EXPECTED=0
    IFS=',' read -ra names <<< "$p"
    for pair in ${SAME_SOURCE//,/ }; do
      [ "${names[0]}:${names[1]}" = "$pair" ] || [ "${names[1]}:${names[0]}" = "$pair" ] && EXPECTED=1
    done
    if [ "$h" = "e3b0c44298fc" ]; then
      echo "::error title=指纹是空输入的哈希::$p 都拿到 $h —— 而那是空输入的 sha256，即它们的 SRC_FILES 一个文件都没找到"
      BAD=$((BAD+1))
    elif [ "$EXPECTED" = "1" ]; then
      echo "[ok] $p 同指纹（字节同源，预期如此）"
    else
      echo "::error title=指纹重复::$p 都拿到 $h —— 至少有一个的 SRC_FILES 路径写错了，或它该跟随另一个件"
      BAD=$((BAD+1))
    fi
    ;;
  esac
done

if [ "$BAD" -gt 0 ]; then
  echo ""
  echo "::error title=源码指纹有 $BAD 处不对::改动这些件的 recipe 而 asset 名不变 = 改动进不了产物，而 CI 会全绿。"
  exit 1
fi

echo "[ok] ${#TOOLS[@]} 个件的源码指纹互不相同"
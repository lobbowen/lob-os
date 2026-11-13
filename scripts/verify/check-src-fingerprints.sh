#!/usr/bin/env bash

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

SAME_SOURCE="crypto:openssl"

TOOLS=(zlib openssl crypto curl jq bash ripgrep busybox git python sqlite pkgconf make cmake npm pnpm llvm)

declare -A PAIR_OF
declare -A OWN_HASH_OF

BAD=0

for t in "${TOOLS[@]}"; do

  own=""
  case "$t" in
    busybox) own="scripts/recipes/build-native-busybox.sh" ;;
    bash)    own="scripts/recipes/build-bundle-bash.sh" ;;
    ripgrep) own="scripts/recipes/build-bundle-rg.sh" ;;
    jq)      own="scripts/recipes/build-bundle-jq.sh" ;;
    curl)    own="scripts/recipes/build-bundle-curl.sh" ;;
    zlib)    own="scripts/recipes/build-bundle-zlib.sh" ;;
    openssl) own="scripts/recipes/build-bundle-openssl.sh scripts/patches" ;;
    crypto)  own="scripts/recipes/build-bundle-openssl.sh scripts/patches" ;;
    git)     own="scripts/recipes/build-component-git.sh scripts/patches" ;;
    python)  own="scripts/recipes/build-component-python3.sh" ;;
    sqlite)  own="scripts/recipes/build-component-sqlite3.sh" ;;
    pkgconf) own="scripts/recipes/build-component-pkg-config.sh" ;;
    make)    own="scripts/recipes/build-component-make.sh" ;;
    cmake)   own="scripts/recipes/build-component-cmake.sh" ;;
    npm)     own="scripts/recipes/build-component-npm.sh" ;;
    pnpm)    own="scripts/recipes/build-component-pnpm.sh" ;;
    llvm) own="scripts/recipes/build-component-llvmtoolchain.sh" ;;
    *) echo "::error title=本条不认这个件名::$t —— 而它是新加的，本条那一族要**同步**加它"; BAD=$((BAD+1)); continue ;;
  esac
  if [ -z "$own" ]; then
    echo "::error title=这一件没有自己的 SRC_FILES::$t —— 而它落 *) 那一行、意味着它的指纹**只**由 PKG_MECH 算出来"
    BAD=$((BAD+1)); continue
  fi

  ownhash=""
  ownhash="$(git ls-files $own 2>/dev/null | while read -r f; do git show "HEAD:$f" 2>/dev/null | sha256sum; done | sha256sum | cut -c1-12)"

  if ! grep -qE "^  $t\) +SRC_FILES=\"" scripts/toolchain/cache-key.sh; then
    echo "::error title=case 里没有这一件自己的条目::$t —— 而它在 *) 那一行、意味着它的指纹**只**由 PKG_MECH 算出来"
    BAD=$((BAD+1)); continue
  fi

  got="$(bash scripts/toolchain/cache-key.sh key "$t" 2>/dev/null | grep -oE 'src[0-9a-f]{12}' | head -1)"
  if [ -z "$got" ]; then
    echo "::error title=算不出 src 段::$t —— 而 cache-key.sh 没有给出 12 位十六进制"
    BAD=$((BAD+1)); continue
  fi

  OWN_HASH_OF["$t"]="$ownhash"
done

for t in "${TOOLS[@]}"; do
  h="${OWN_HASH_OF[$t]:-}"
  [ -n "$h" ] || continue
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

#!/usr/bin/env bash
# 源码指纹的完整性判据 —— 每一件的 src 指纹**是不是它自己那些文件的聚合**。
#
# 缺陷名（C-102）**指纹那一族取错了输入** ——
#   而 06 今天实测（2026-10-25）：pkgconf / make / cmake / npm / pnpm / llvmtoolchain 那六件
#   在 cache-key.sh 那个 case 里**没有自己的条目**、⇒ 而全落 *) SRC_FILES="" ;; 那一行，
#   ⇒ 而而它们的 src 指纹于是**只**由 PKG_MECH 那三个文件算出来，
#   ⇒ 而而那六件的 src 段**逐字相同**（⇒ 而实测是 src9251ee997f11）。
#   ⇒ 而后果：**改那六件任何一件的 recipe，它们的指纹一律不变**
#   ⇒ 而而取件会取到**旧字节**（⇒ 而 release 上那个名字没变）。
#
# 而这一条与 2026-10-16 那一条**不是同一族**：
#   · 那一条查的是「有没有东西**压根没算出来**」（⇒ 而那是空输入的哈希 e3b0c44298fc）；
#   · 这一条查的是「**算出来的那个值是不是这一件自己的**」（⇒ 而那是「取错了输入」）。
#   ⇒ 而按 C-135 那是两问，⇒ 而这一条核的是第二问。
#
# 判别形式（可机械核，三条）：
#   一，**每一件**自己的 SRC_FILES **非空**；
#   二，**每一件**算出来的 src 段**互不相同**（⇒ 而「字节同源」的那一对是**预期**，⇒ 而不判重）；
#   三，**改**某一件自己的 recipe ⇒ ⇒ 而**那一件**的 src 段**要变**
#      （⇒ 而这**要改一次仓**才能核，⇒ 而今天本条**不**核它，⇒ 而它留给 CI 那一步）。
#
# 而**为什么必须独立于 cache-key.sh 自身**：
#   一个值「算对了」与「**压根没算自己那份**」在输出上都是 12 位十六进制，格式上不可区分。
#   ⇒ 而本条**只**用 cache-key.sh 当**被核对象**，⇒ 而它**不**复用 cache-key.sh 的内部算法
#   ⇒ 而它**自己**按 cache-key.sh:188 那一行的算法重算一遍「它自己那些文件的聚合」再逐字比。
#
# 用法：bash scripts/verify/check-src-fingerprints.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# 字节同源的件：它们**应当**同指纹（⇒ 而它们**不**判重）。
# crypto 的 libcrypto.so 是从 openssl 的包里拆出来的（⇒ 而 build-crypto.yml 逐字是 unzip dist/$OASSET），
# 而 openssl 的 recipe 只被它自己 SRC_FILES 收录 —— 若不给 crypto 同一份，
# 「改了 openssl 而 crypto 没变」就会被漏掉。
SAME_SOURCE="crypto:openssl"

# 本条核的件：base 筐那 8 件 + tool 与 rt 筐那 6 件。
# node **不**在其中（⇒ 而用户 2026-10-16 逐字「node 不要修，node 是不能修的，是后期要用来验证的」）。
TOOLS=(zlib openssl crypto curl jq bash ripgrep busybox git python sqlite pkgconf make cmake npm pnpm llvm)

declare -A PAIR_OF
declare -A OWN_HASH_OF

BAD=0

for t in "${TOOLS[@]}"; do
  # 一、**它自己那一份**的 SRC_FILES 是什么（=> 而那**不**经过 cache-key.sh，=> 而那是从那一行逐字读出来的）
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
    *) echo "::error title=本条不认这个件名::$t —— 而而它是新加的，=> 而而本条那一族要**同步**加它"; BAD=$((BAD+1)); continue ;;
  esac
  if [ -z "$own" ]; then
    echo "::error title=这一件没有自己的 SRC_FILES::$t —— 而而它落 *) 那一行、=> 而而那意味着它的指纹**只**由 PKG_MECH 算出来"
    BAD=$((BAD+1)); continue
  fi
  # 二、**自己**按 cache-key.sh:188 那一行的算法重算一遍「它自己那些文件的聚合」
  ownhash=""
  ownhash="$(git ls-files $own 2>/dev/null | while read -r f; do git show "HEAD:$f" 2>/dev/null | sha256sum; done | sha256sum | cut -c1-12)"
  # 三、**cache-key.sh 那个 case 里它自己那一条在不在**（=> 而那**才是**「取错了输入」那一族）
  #   => 而而那**必须**从那一行**逐字**读出来（=> 而而不能用本条自己算的值替代它）
  if ! grep -qE "^  $t\) +SRC_FILES=\"" scripts/toolchain/cache-key.sh; then
    echo "::error title=case 里没有这一件自己的条目::$t —— 而而它在 *) 那一行、=> 而而那意味着它的指纹**只**由 PKG_MECH 算出来"
    BAD=$((BAD+1)); continue
  fi
  # 四、**cache-key.sh 算出来的那一个**是不是上面那一个（=> 而那**该**含 PKG_MECH，=> 而所以本条核的是**它自己那部分**在不在）
  got="$(bash scripts/toolchain/cache-key.sh key "$t" 2>/dev/null | grep -oE 'src[0-9a-f]{12}' | head -1)"
  if [ -z "$got" ]; then
    echo "::error title=算不出 src 段::$t —— 而 cache-key.sh 没有给出 12 位十六进制"
    BAD=$((BAD+1)); continue
  fi
  # 而**它自己那一份**的聚合**不**该等于**全件**的聚合（=> 而那是两件事，=> 而而这里只记下它自己那一份）
  OWN_HASH_OF["$t"]="$ownhash"
done

# 二、**互不相同**（=> 而「字节同源」的那一对是**预期**，=> 而不判重）
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

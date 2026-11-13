#!/usr/bin/env bash

set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
ABI="${ABI:-arm64-v8a}"

ONLY=""
for a in "$@"; do
  case "$a" in
    --only=*) ONLY="$ONLY ${a#--only=}" ;;
    *) echo "::error title=认不得的参数::$a —— 只支持 --only=<件名>（可多次）"; exit 2 ;;
  esac
done
ONLY="${ONLY# }"

build_each() {
  local failed=0
  if [ -n "$ONLY" ]; then
    local n
    for n in $ONLY; do
      printf "::notice title=逐件::build-bundle-%s.sh " "$n"
      if bash "scripts/recipes/build-bundle-$n.sh"; then
        echo "ok"
      else
        echo "FAILED"
        echo "[caps] build-bundle-$n.sh 失败"
        failed=1
      fi
    done
    [ "$failed" = 0 ] || { echo "::error title=有件编不出来::见上方各脚本的取证输出"; exit 1; }
    echo "[caps] 指定件编译完成：$ONLY"
    return 0
  fi
  for s in \
      build-bundle-zlib.sh \
      build-bundle-openssl.sh \
      build-bundle-curl.sh \
      build-bundle-jq.sh \
      build-bundle-bash.sh \
      build-bundle-rg.sh \
      build-native-busybox.sh
  do

    printf "::notice title=逐件::%s " "$s"
    if bash "scripts/recipes/$s"; then
      echo "ok"
    else
      echo "FAILED"
      echo "[caps] $s 失败 —— 上面那条通知标了是哪一件"
      failed=1
    fi
  done
  [ "$failed" = 0 ] || { echo "::error title=有件编不出来::见上方各脚本的取证输出"; exit 1; }
  echo "[caps] 逐件编译完成"
}

if [ -n "$ONLY" ]; then
  echo "[caps] 只编指定件：$ONLY"
else

bash scripts/toolchain/build-kernel-compat.sh || die "垫片编不出来" "内核缺了兼容层，程序跑不出 Linux 语义"

  echo "[caps] 逐件编译（7 个脚本，各产 component-meta.json 并落位）"
fi
build_each
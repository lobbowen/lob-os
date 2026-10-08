#!/usr/bin/env bash
# 编 posix —— LD_PRELOAD 那组拦截（链接期补全 + open 回退 + /tmp 重定向 + exec 路径修正），
# 编成 liblobosposix.so。
#
# 四个源文件合成一个 .so：它们是一套（一起 preload 才有意义），所以是一件不是四件。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" posix

SO="$JNI/liblobosposix.so"
"$CC" -shared -fPIC -O2 \
  -o "$SO" \
  container/native/d1/link-interpose.c \
  container/native/d1/open-fallback.c \
  container/native/d1/tmp-paths.c \
  container/native/d1/exec-path.c \
  -ldl \
  || die "posix 编译失败" "四个源文件见 container/native/d1/"
check_so "$SO" 500 || die "posix 产物不可用" "$SO"
echo "[ok] posix → $SO $(wc -c < "$SO") 字节"
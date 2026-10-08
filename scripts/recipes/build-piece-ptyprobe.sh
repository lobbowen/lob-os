#!/usr/bin/env bash
# 编 ptyprobe —— 探测「这台机器能不能起伪终端」，编成静态可执行件。

set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" ptyprobe

"$CC" -static -O2 -o "$WORK/liblobosptyprobe.so" container/native/d2/pty-probe.c \
  || die "ptyprobe 编译失败" "纯 C 静态编译，失败即环境问题"
[ -x "$WORK/liblobosptyprobe.so" ] || die "ptyprobe 产物不可执行" "静态编译应产出可执行件"
land_piece ptyprobe liblobosptyprobe.so 1000
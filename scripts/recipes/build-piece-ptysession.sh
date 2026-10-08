#!/usr/bin/env bash
# 编 ptysession —— PTY 会话宿主（打开伪终端、fork、收发字节），编成静态可执行件。

set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" ptysession

"$CC" -static -O2 -o "$WORK/librivospty.so" container/native/d3/pty-session.c \
  || die "PTY 会话宿主编译失败" "纯 C 静态，失败即环境问题 —— shell.exec 与终端都依赖它"
[ -x "$WORK/librivospty.so" ] || die "ptysession 产物不可执行" "静态编译应产出可执行件"
land_piece ptysession librivospty.so 1000
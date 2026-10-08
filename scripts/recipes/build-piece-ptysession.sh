#!/usr/bin/env bash
# 编 ptysession —— PTY 会话宿主（打开伪终端、fork、收发字节），编成静态可执行件。
#
# shell.exec 与终端都依赖它：终端起不来时终端功能不可用。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" ptysession

EXE="$JNI/librivospty.so"
"$CC" -static -O2 -o "$EXE" container/native/d3/pty-session.c \
  || die "PTY 会话宿主编译失败" "纯 C 静态，失败即环境问题 —— shell.exec 与终端都依赖它"
check_exe "$EXE" 1000 || die "ptysession 产物不可用" "$EXE"
echo "[ok] ptysession → $EXE $(wc -c < "$EXE") 字节"
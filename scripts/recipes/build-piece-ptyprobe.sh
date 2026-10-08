#!/usr/bin/env bash
# 编 ptyprobe —— 探测「这台机器能不能起伪终端」，编成静态可执行件。
#
# 终端与 shell.exec 起程序前要先知道 PTY 可用；这个件就是那个探测的实现
# （真正的探测由运行时调它，不是在这里跑）。
set -uo pipefail
# shellcheck disable=SC1091
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/piece-env.sh" ptyprobe

EXE="$JNI/liblobosptyprobe.so"
"$CC" -static -O2 -o "$EXE" container/native/d2/pty-probe.c \
  || die "ptyprobe 编译失败" "纯 C 静态编译，失败即环境问题"
check_exe "$EXE" 1000 || die "ptyprobe 产物不可用" "$EXE"
echo "[ok] ptyprobe → $EXE $(wc -c < "$EXE") 字节"
#!/usr/bin/env bash
# 编 hello —— 系统里第一个「程序」（不是模块）。
#
# 它存在的唯一理由：证明「一个包能被装上、登记为 PROGRAM、并被选中启动」这条路是通的。
# 2026-10-16 之前系统里没有任何程序（program-index 里 8 条全是模块，level 字段一个都没有），
# 于是「装程序」这件事从来没被走过一次。
#
# 零外部依赖：静态链接到 NDK 的 sysroot，不 NEEDED 任何 .so ——
# 而依赖闭包是另一段（安装链的第 4 段），在这里一起验会混掉两个变量。
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" hello

cat > "$WORK/hello.c" <<'CSRC'
#include <unistd.h>
#include <stdio.h>

int main(void) {
    printf("hello from a program\n");
    fflush(stdout);
    for (;;) pause();
    return 0;
}
CSRC

OUT_DIR="$WORK/out"
mkdir -p "$OUT_DIR/bin"

"$CC" -static -O2 -o "$OUT_DIR/bin/hello" "$WORK/hello.c" 2>"$WORK/build.log" \
  || { echo "=== hello 编译失败 ==="; cat "$WORK/build.log"; die "hello 没编出来" "见上面的 clang 输出"; }

chmod +x "$OUT_DIR/bin/hello"

PIECE_KIND=program emit_piece hello "$OUT_DIR" bin/hello 1000 "hello"
echo "[ok] hello 落位（程序形态，entry 指向 bin/hello）"
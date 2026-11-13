#!/usr/bin/env bash

set -uo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bundle-env.sh" bash

BASH_VER="$(bash scripts/toolchain/fetch-pinned.sh --src-version bash 2>/dev/null || true)"
[ -n "$BASH_VER" ] || die "钉值表里没有 bash" "scripts/component-sources.json 的 sources.bash"

TARGET_HOST="${TARGET_HOST:-aarch64-linux-android}"

if [ -n "${PC_TARGET_TRIPLE:-}" ]; then
  _decl_host="$(bash scripts/toolchain/pc-target.sh host-flag 2>/dev/null || true)"
  if [ -n "$_decl_host" ] && [ "$_decl_host" != "$TARGET_HOST" ]; then
    echo "[bash] 那 --host= 那一格逐字 = $TARGET_HOST、声明逐字 = $_decl_host"
    echo "[bash]一档逐字是「目标三元组」、按 C-05 报缺、那**不**猜"
    exit 1
  fi
fi
echo "[bash] 取源码 $BASH_VER（sha256 由钉值表校验）"
bash scripts/toolchain/fetch-pinned.sh --pin bash $WORK/bash.tar.gz \
  || die "bash 源码取不到或 sha256 不符" \
       "钉值与来源见 scripts/component-sources.json 的 sources.bash" \
       "所有镜像都试过了仍失败；不要改成不校验的下载"
echo "[bash] 命中钉值来源，sha256 校验通过"

rm -rf "$WORK/bash-$BASH_VER"

mkdir -p "$WORK"
tar -xzf "$WORK/bash.tar.gz" -C "$WORK" || die "bash 解包失败" "sha256 是对的但 tar 解不开"
[ -d "$WORK/bash-$BASH_VER" ] || die "bash 源码目录名不符" \
  "期望 $WORK/bash-$BASH_VER，实际解出：$(ls -d "$WORK"/bash-* 2>/dev/null | tr "\n" " ")"

cat > $WORK/termcap_stub.c <<'STUB'
int tputs(const char *s, int n, int (*f)(int)) { (void)s; (void)n; (void)f; return 0; }
int tgetent(char *bp, const char *name) { (void)bp; (void)name; return -1; }
int tgetflag(const char *id) { (void)id; return 0; }
int tgetnum(const char *id) { (void)id; return -1; }
char *tgetstr(const char *id, char **area) { (void)id; (void)area; return 0; }
char *tgoto(const char *cap, int col, int row) { (void)cap; (void)col; (void)row; return 0; }
char PC = 0;
char *BC = 0;
char *UP = 0;
STUB
"$CC" -c -O2 $WORK/termcap_stub.c -o $WORK/termcap_stub.o \
  && "$LLVM_AR" rcs $WORK/libtermcap_stub.a $WORK/termcap_stub.o

"$CC" -c -O2 container/native/bionic-compat.c -o $WORK/bionic_compat.o \
  && "$LLVM_AR" rcs $WORK/libbionic_compat.a $WORK/bionic_compat.o

(
  set -e
  cd "$WORK/bash-$BASH_VER"
  ./configure --host="$TARGET_HOST" --build=x86_64-pc-linux-gnu \
    --prefix=/native --disable-nls --without-bash-malloc \
    CC="$CC" \
    CFLAGS="-O2 -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-unused-result" \
    LDFLAGS="-Wl,--allow-multiple-definition" \
    LIBS="$WORK/libtermcap_stub.a $WORK/libbionic_compat.a" \
    bash_cv_getcwd_malloc=yes bash_cv_func_sigsetjmp=present \
    bash_cv_printf_a_format=yes bash_cv_dev_fd_standard=yes \
    bash_cv_unusable_rtsigs=no > $WORK/bash-configure.log 2>&1 \
    || { echo "=== configure 失败取证 ==="; tail -40 $WORK/bash-configure.log; exit 1; }

  make -j4 bash \
    CFLAGS_FOR_BUILD="-std=gnu89 -O2 -Wno-error=implicit-function-declaration -Wno-error=int-conversion" \
    > $WORK/bash-make.log 2>&1 || {
    echo "=== make 失败取证 ==="
    grep -nE "error:|Error [0-9]+$|undefined symbol" $WORK/bash-make.log | head -40 || true
    tail -120 $WORK/bash-make.log
    exit 1
  }
) || die "bash 编译失败" "日志在 $WORK/bash-configure.log 与 $WORK/bash-make.log"

mkdir -p "$OUT_DIR/bin"
cp -f "$WORK/bash-$BASH_VER/bash" "$OUT_DIR/bin/bash"
[ -x "$OUT_DIR/bin/bash" ] || die "bash 产物不可执行" "\$PREFIX/bin/bash 无回退路径"
emit_piece bash "$OUT_DIR" bin/bash 300000
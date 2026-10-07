#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/.."
ROOT_DIR=$(pwd)

if [ -z "${CC:-}" ]; then
  echo "::error title=缺 CC::需要 CC（aarch64-linux-android21-clang）"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT/bin" work

echo "[git] 取源码（钉值表的 git 那一格）"
bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin git "$ROOT_DIR/work/git.tar.xz" \
  --version-file "$ROOT_DIR/$OUT/git.version"
echo "[git] 源码包 $(stat -c%s work/git.tar.xz) 字节"
rm -rf work/git-src && mkdir -p work/git-src
if ! tar xJf work/git.tar.xz -C work/git-src --strip-components=1; then
  echo "::error title=解包失败::tar.xz 损坏或上游换了压缩格式"
  exit 1
fi
[ -f work/git-src/Makefile ] || { echo "::error title=源码树异常::没有 Makefile"; exit 1; }
echo "[git] 源码树就位"

DEPS="$ROOT_DIR/work/deps"
mkdir -p "$DEPS"
TC_DIR=$(dirname "$CC")
ANDROID_API=23
if [ ! -x "$TC_DIR/aarch64-linux-android$ANDROID_API-clang" ]; then
  echo "::error title=缺 API$ANDROID_API 的 clang::NDK 里没有 aarch64-linux-android$ANDROID_API-clang"
  exit 1
fi
export CC="$TC_DIR/aarch64-linux-android$ANDROID_API-clang"
echo "[git] 编译 API = $ANDROID_API（$CC）"
AR_BIN=${AR_BIN:-$TC_DIR/llvm-ar}
RANLIB_BIN=${RANLIB_BIN:-$TC_DIR/llvm-ranlib}
ANDROID_NDK_ROOT="$(cd "$TC_DIR/../../../../.." && pwd)"
[ -d "$ANDROID_NDK_ROOT" ] || { echo "[git] 从 CC 反推 NDK root 失败：$ANDROID_NDK_ROOT 不是目录"; exit 1; }
export ANDROID_NDK_ROOT
echo "[git] NDK root = $ANDROID_NDK_ROOT"

echo "[git] 编静态依赖库（build-shared-deps.sh）"
DEPS="$DEPS" CC="$CC" ANDROID_API="$ANDROID_API" bash "$ROOT_DIR/scripts/build-shared-deps.sh"
echo "[git] curl 库就位（静态）"

cd "$ROOT_DIR/work/git-src"

cd "$ROOT_DIR/work/git-src"

PATCHES="config.mak.uname.patch run-command.c.patch disable-fdsan.patch disable_daemon_syslog.patch compat-posix.h.patch config.c.patch help.c.patch tempfile.c.patch"
for p in $PATCHES; do
  bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin "git-$p" "$ROOT_DIR/work/$p"
  if ! patch -p1 -i "$ROOT_DIR/work/$p"; then
    echo "::error title=补丁打不上::$p —— 上游 git 版本与 Termux 补丁集不匹配？"
    exit 1
  fi
  echo "[git] 补丁已打：$p"
done
export CC
TC=$(dirname "$CC")
export AR="$TC/llvm-ar"

IMAP_LINES=$(grep -c '^PROGRAM_OBJS += imap-send[.]o' Makefile || true)
if [ "$IMAP_LINES" != "0" ]; then
  sed -i '/^PROGRAM_OBJS += imap-send[.]o/d' Makefile
  echo "[git] 已从构建目标里去掉 git-imap-send"
else
  echo "::error title=没找到 imap-send 的目标行::上游 Makefile 变了，得重新确认怎么排除"
  exit 1
fi
export RANLIB="$TC/llvm-ranlib"

MAKE_ARGS="CC=$CC AR=$AR RANLIB=$RANLIB PTHREAD_LIBS= NO_RUST=1 CURLDIR=$ROOT_DIR/work/deps OPENSSLDIR=$ROOT_DIR/work/deps uname_S=Linux uname_M=aarch64 prefix=$ROOT_DIR/$OUT CSPRNG_METHOD= HAVE_SYNC_FILE_RANGE= HAVE_GETRUSAGE= HAVE_SYSINFO= NO_EXPAT=1 NO_GETTEXT=1 NO_ICONV=1 NO_TCLTK=1 NO_NSEC=1 NO_INSTALL_HARDLINKS=1 NO_PERL=1 NO_PYTHON=1 RUNTIME_PREFIX=1 ac_cv_fread_reads_directories=yes ac_cv_header_libintl_h=no ac_cv_iconv_omits_bom=no ac_cv_snprintf_returns_bogus=no"
echo "[git] make（$MAKE_ARGS）"
if ! make -j2 $MAKE_ARGS CURL_LIBCURL="-L$ROOT_DIR/work/deps/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$ROOT_DIR/work/deps/include" LDFLAGS="-L$ROOT_DIR/work/deps/lib" all; then
  echo "::error title=make 失败::见上"
  exit 1
fi
echo "[git] make install"
if ! make $MAKE_ARGS CURL_LIBCURL="-L$ROOT_DIR/work/deps/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$ROOT_DIR/work/deps/include" LDFLAGS="-L$ROOT_DIR/work/deps/lib" install; then
  echo "::error title=install 失败::见上"
  exit 1
fi

[ -x "$ROOT_DIR/$OUT/bin/git" ] || { echo "::error title=没产出 bin/git::install 落点与预期不符"; ls -la "$ROOT_DIR/$OUT" || true; exit 1; }
if [ ! -d "$ROOT_DIR/$OUT/libexec/git-core" ]; then
  echo "::error title=缺 libexec/git-core::git 的子命令目录没装上（RUNTIME_PREFIX/prefix 落点问题）"
  exit 1
fi
rm -f "$ROOT_DIR/$OUT/bin/git-credential-"* 2>/dev/null || true

GITCORE="$ROOT_DIR/$OUT/libexec/git-core"

STRIPPED=0
if [ -n "${LLVM_STRIP:-}" ] && [ -x "${LLVM_STRIP}" ]; then
  for f in "$ROOT_DIR/$OUT/bin/git" "$GITCORE"/*; do
    [ -f "$f" ] || continue
    [ -L "$f" ] && continue
    if "$LLVM_STRIP" "$f" 2>/dev/null; then STRIPPED=$((STRIPPED+1)); fi
  done
  echo "[git] 已 strip $STRIPPED 个二进制（strip 前 bin/git $(stat -c%s "$ROOT_DIR/$OUT/bin/git") 字节）"
else
  echo "::error title=没有 LLVM_STRIP::未 strip 的件体积会离谱（CI 实测 102 MiB）—— 中止"
  exit 1
fi
BINSIZE=$(stat -c%s "$ROOT_DIR/$OUT/bin/git")
BININODE=$(stat -c%i "$ROOT_DIR/$OUT/bin/git")
FARM="$ROOT_DIR/$OUT/link-farm.txt"
: > "$FARM"
LINKED=0
SLIMMED=0
KEPT=0
for f in "$GITCORE"/*; do
  NAME=$(basename "$f")
  [ -L "$f" ] || [ -e "$f" ] || continue
  if [ -L "$f" ]; then
    TGT=$(readlink "$f")
    printf '%s\t%s\n' "libexec/git-core/$NAME" "$TGT" >> "$FARM"
    LINKED=$((LINKED+1))
  elif [ -f "$f" ] && [ ! -L "$f" ]; then
    FSIZE=$(stat -c%s "$f")
    FINODE=$(stat -c%i "$f")
    if [ "$FINODE" = "$BININODE" ] || [ "$FSIZE" = "$BINSIZE" ]; then
      printf '%s\t%s\n' "libexec/git-core/$NAME" "../../bin/git" >> "$FARM"
      rm -f "$f"
      SLIMMED=$((SLIMMED+1))
    else
      KEPT=$((KEPT+1))
    fi
  fi
done
echo "[git] 链接农场：符号链接 $LINKED、副本瘦身 $SLIMMED、真独立文件 $KEPT"
TREE=$(du -sm "$ROOT_DIR/$OUT" | cut -f1)
echo "[git] 件树体积 ${TREE} MiB"
if [ "$TREE" -gt 60 ]; then
  echo "::error title=件太大::${TREE} MiB —— 链接农场没生效？（bin/git $BINSIZE 字节）"
  exit 1
fi
SIZE=$(stat -c%s "$ROOT_DIR/$OUT/bin/git")
SUBS=$(ls "$ROOT_DIR/$OUT/libexec/git-core" | wc -l)
echo "[git] 产出 bin/git（$SIZE 字节），libexec/git-core $SUBS 项"

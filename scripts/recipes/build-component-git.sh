#!/usr/bin/env bash
set -euo pipefail

HERE=$(dirname "$0")
cd "$HERE/../.."
ROOT_DIR=$(pwd)

if [ -z "${CC:-}" ]; then
  echo "::error title=缺 CC::需要 CC（NDK 的 clang（CI 里 locate-ndk.sh 注入，形如 …/bin/aarch64-linux-android35-clang））"
  exit 1
fi

OUT="${OUT:-dist}"
mkdir -p "$ROOT_DIR/$OUT/bin" work

echo "[git] 取源码（钉值表的 git 那一格）"
bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin git "$ROOT_DIR/work/git.tar.xz" \
  --version-file "$ROOT_DIR/$OUT/git.version"
echo "[git] 源码包 $(stat -c%s work/git.tar.xz) 字节"
rm -rf work/git-src && mkdir -p work/git-src
if ! tar xJf work/git.tar.xz -C work/git-src --strip-components=1; then
  echo "::error title=解包失败::tar.xz 损坏或上游换了压缩格式"
  exit 1
fi
[ -f work/git-src/Makefile ] || { echo "::error title=源码树异常::没有 Makefile"; exit 1; }
echo "[git] 源码树就位"

TC_DIR=$(dirname "$CC")
ANDROID_API=35
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

echo "[git] 诊断：TC=$TC_DIR"
echo "[git] 诊断：PATH 含 TC ? $(case ":$PATH:" in *":$TC_DIR:"*) echo 是;; *) echo 否;; esac)"
if command -v aarch64-linux-android35-clang >/dev/null 2>&1; then
  echo "[git] 诊断：裸名可解析 → $(command -v aarch64-linux-android35-clang)"
else
  echo "[git] 诊断：裸名**不可解析** → 报错 127 就是这个原因"
  ls "$TC_DIR"/aarch64-linux-android35-clang 2>&1 | sed "s/^/[git] 诊断：  /"
fi
echo "[git] 诊断：Makefile 里 legacy-dso-legacyprov.o 那条规则用哪个编译器变量："
grep -n "legacy-dso-legacyprov" Makefile 2>/dev/null | head -3 | sed "s/^/[git] 诊断：  /" || true
grep -nE "^\s*(CC|GIT-CFLAGS)\s*[:?]?=" Makefile 2>/dev/null | head -5 | sed "s/^/[git] 诊断：  /" || true
# curl / openssl / zlib 现在是**预装件**（base 筐，见 component-verify.json），
# 落位在 usr/lib/<id>/<版本>/lib/ 并建了全局软链 —— 
#   git 只记 DT_NEEDED libcurl.so / libssl.so / libz.so，运行时从全局那份找。
#
#   此前这里编静态库然后 -lcurl -lssl -lcrypto -lz 全链进去 ——
#   那正是一个二进制里装了几个件（Linux 里 git 链的是 .so，不是 .a）。
#
# 所以：不再调 build-shared-deps.sh（那个一次编三份静态库的脚本已删）。
# 依赖从落位处取（prefix 在编译期就能算出来）。
echo "[git] 依赖用预装件（usr/lib 下的全局软链），不再静态链入"

cd "$ROOT_DIR/work/git-src"

PATCHES="config.mak.uname.patch run-command.c.patch disable-fdsan.patch disable_daemon_syslog.patch compat-posix.h.patch config.c.patch help.c.patch tempfile.c.patch"
for p in $PATCHES; do
  bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --pin "git-$p" "$ROOT_DIR/work/$p"
  if ! patch -p1 -i "$ROOT_DIR/work/$p"; then
    echo "::error title=补丁打不上::$p —— 上游 git 版本与 Termux 补丁集不匹配？"
    exit 1
  fi
  echo "[git] 补丁已打：$p"
done
export CC
TC=$(dirname "$CC")
export AR="$TC_DIR/llvm-ar"
export PATH="$TC_DIR:$PATH"

IMAP_LINES=$(grep -c '^PROGRAM_OBJS += imap-send[.]o' Makefile || true)
if [ "$IMAP_LINES" != "0" ]; then
  sed -i '/^PROGRAM_OBJS += imap-send[.]o/d' Makefile
  echo "[git] 已从构建目标里去掉 git-imap-send"
else
  echo "::error title=没找到 imap-send 的目标行::上游 Makefile 变了，得重新确认怎么排除"
  exit 1
fi
export RANLIB="$TC_DIR/llvm-ranlib"

# ── 依赖从哪来：base 筐三个预装件的构建输出 ──────────────────
# curl / openssl / zlib 早已不再编到 work/deps（那个一次编三份静态库的
# build-shared-deps.sh 已删）。它们现在是独立的件，各自由自己的脚本编，
# 构建输出留在 work/<件名>/out/{include,lib}/ —— 那正是 configure 想要的前缀形状
# （build-piece-curl.sh 对 openssl/zlib 就是这么找的）。
#
# 这里链的是**共享库**，不是一个二进制里静态塞几份 .a —— git 只记
# DT_NEEDED libcurl.so / libssl.so / libz.so，运行时从全局落位那份找。
# 这与脚本原有的设计意图一致（见上面那段注释），只是当时没跟上脚本的删除。
ZLIB_PREFIX="$ROOT_DIR/work/zlib/out"
OPENSSL_PREFIX="$ROOT_DIR/work/openssl/out"
CURL_PREFIX="$ROOT_DIR/work/curl/out"
for p in "$ZLIB_PREFIX" "$OPENSSL_PREFIX" "$CURL_PREFIX"; do
  [ -d "$p/lib" ] || {
    echo "::error title=依赖件没编出来::$p/lib 不存在 —— git 依赖 base 筐里的"
    echo "         zlib / openssl / curl 三个件。要么让本 workflow 先跑"
    echo "         ensure-native-capabilities.sh（build-apk.yml 就在跑它），"
    echo "         要么 git 不带 https（NO_CURL=1）—— 但那条路与"
    echo "         component-verify.json 的判据冲突（它要求 git ls-remote https 能用）。"
    exit 1
  }
done
echo "[git] 依赖三件就位：zlib / openssl / curl"

MAKE_ARGS="CC=$CC AR=$AR RANLIB=$RANLIB PTHREAD_LIBS= NO_RUST=1 CURLDIR=$CURL_PREFIX OPENSSLDIR=$OPENSSL_PREFIX uname_S=Linux uname_M=aarch64 prefix=$ROOT_DIR/$OUT CSPRNG_METHOD= HAVE_SYNC_FILE_RANGE= HAVE_GETRUSAGE= HAVE_SYSINFO= NO_EXPAT=1 NO_GETTEXT=1 NO_ICONV=1 NO_TCLTK=1 NO_NSEC=1 NO_INSTALL_HARDLINKS=1 NO_PERL=1 NO_PYTHON=1 RUNTIME_PREFIX=1 ac_cv_fread_reads_directories=yes ac_cv_header_libintl_h=no ac_cv_iconv_omits_bom=no ac_cv_snprintf_returns_bogus=no"
echo "[git] make（$MAKE_ARGS）"
if ! make -j2 $MAKE_ARGS CURL_LIBCURL="-L$CURL_PREFIX/lib -L$OPENSSL_PREFIX/lib -L$ZLIB_PREFIX/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$CURL_PREFIX/include -I$OPENSSL_PREFIX/include -I$ZLIB_PREFIX/include" LDFLAGS="-L$CURL_PREFIX/lib -L$OPENSSL_PREFIX/lib -L$ZLIB_PREFIX/lib" all; then
  echo "::error title=make 失败::见上"
  exit 1
fi
echo "[git] make install"
if ! make $MAKE_ARGS CURL_LIBCURL="-L$CURL_PREFIX/lib -L$OPENSSL_PREFIX/lib -L$ZLIB_PREFIX/lib -lcurl -lssl -lcrypto -lz -ldl" CURL_LIBS="-lcurl -lssl -lcrypto -lz" OPENSSL_LIBSSL="-lssl -lcrypto" CPPFLAGS="-I$CURL_PREFIX/include -I$OPENSSL_PREFIX/include -I$ZLIB_PREFIX/include" LDFLAGS="-L$CURL_PREFIX/lib -L$OPENSSL_PREFIX/lib -L$ZLIB_PREFIX/lib" install; then
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

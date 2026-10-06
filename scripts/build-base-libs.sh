#!/usr/bin/env bash
# 底座共享库 —— 编译一次，供多个件共用。产物进 $PREFIX/lib（usr/lib）。
#
# 为什么要它：
#   zlib / openssl / curl 原本在 build-userland-curl.sh 与 build-userland-git.sh
#   里**各编一遍静态**（两个不同的 work/ 目录）。openssl 尤其贵 —— 编两遍换不来
#   任何好处，只让两件各背一份体积。
#   这里编一份共享库。底座有它，第 2 阶段 curl/git 改动态链时才有东西可链。
#
# 与 build-native-capabilities.sh 的分工：
#   那个脚本编「小体积内核零件」（bash/rg/flock/posix/ptyprobe），产物进 jniLibs
#   → nativeLibraryDir。本脚本编的是**底座运行层共享库**，同样进 jniLibs
#   （APK 打包只有这一条路），但语义不同：它是库，不是可执行件。
#
# ── 产物形态（照抄现役的正确样板 libc++_shared.so，不是照抄错的）─────────────
#   真机 APK 里的实测事实：
#     liblobosflock.so   无 PT_INTERP · 无 SONAME   ← 真库
#     libc++_shared.so   无 PT_INTERP · SONAME=文件名 ← 真库
#     libbash.so         有 PT_INTERP · 无 SONAME   ← 可执行件（用 .so 扩展名装进 jniLibs）
#     liblobosrg.so      有 PT_INTERP · RUNPATH=$ORIGIN ← 可执行件
#     liblobospty.so     有 PT_INTERP · SONAME=pty.node ← 可执行件，且 soname 与文件名不符（真机故障）
#   所以「有 PT_INTERP」= 可执行件 = CAPABILITY 类目，不在本脚本职责内。
#
# ── 三条铁律，每条对应一类真机故障 ─────────────────────────────────────────
#   1. 不得带版本化 soname（libssl.so.3）。bionic 按 DT_NEEDED 里的**文件名**找库，
#      而 $PREFIX/lib 里只有 libssl.so —— .so.3 那份不存在，linker 报
#      cannot locate symbol。必须在**链接期**把 soname 定成无版本形态：
#      不能靠事后 patchelf 改名 —— CI runner 上没有 patchelf（仓内零引用），
#      静默跳过就是静默降级。
#   2. 必须带含 $ORIGIN 的 DT_RUNPATH。bionic 忽略 DT_RPATH；判据 5
#      （scripts/verify-runtime-elf.sh）对「依赖同目录随包库」的件硬性要求它。
#   3. 不得带 PT_INTERP。库带解释器段会被判据 3 挑出来。
#
# 用法：CC=<ndk clang> LLVM_STRIP=<llvm-strip> ABI=arm64-v8a bash scripts/build-base-libs.sh
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
cd "$HERE/.."
ROOT_DIR="$(pwd)"

ABI="${ABI:-arm64-v8a}"
J="$ROOT_DIR/container/app/src/main/jniLibs/$ABI"
WORK="$ROOT_DIR/work/baselibs"
ANDROID_API="${ANDROID_API:-23}"
JOBS="${JOBS:-4}"

die() {
  # 第二个及之后的参数都并进同一条 ::error。只取 ${2:-} 的话，
  # 调用点传的第 3 句往后会被**静默丢掉** —— 写上去像是说了，其实没输出。
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}
note() { echo "[note] $*"; }

[ -n "${CC:-}" ] || die "缺 CC" "需要 NDK 的 clang（build-apk.yml / build-userland.yml 的「定位 NDK」步会注入）"
TC="$(dirname "$CC")"
LLVM_AR="$TC/llvm-ar"
LLVM_RANLIB="$TC/llvm-ranlib"
LLVM_STRIP="${LLVM_STRIP:-$TC/llvm-strip}"
LLVM_READELF="${LLVM_READELF:-$TC/llvm-readelf}"
for t in "$LLVM_AR" "$LLVM_RANLIB" "$LLVM_READELF"; do
  [ -x "$t" ] || die "缺工具" "$t 不存在 —— 取不出可执行位就不得编库"
done
[ -x "$CC" ] || die "缺 clang" "$CC 不是 aarch64-linux-android$ANDROID_API-clang"

# 从 clang 路径反推 NDK 根目录（TC = $NDK/toolchains/llvm/prebuilt/<host>/bin）
NDK_ROOT="$(cd "$TC/../../../../.." && pwd)"
[ -d "$NDK_ROOT" ] || die "定位 NDK 失败" "从 clang 路径 '$TC' 反推得到 '$NDK_ROOT'，它不是目录"
export ANDROID_NDK_HOME="$NDK_ROOT" ANDROID_NDK_ROOT="$NDK_ROOT"
ANDROID_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[ -f "$ANDROID_TOOLCHAIN" ] || die "NDK 缺 android.toolchain.cmake" "路径 '$ANDROID_TOOLCHAIN' 不存在 —— CMake 交叉编译 Android 必需它"

mkdir -p "$J" "$WORK"
echo "[base-libs] ABI=$ABI API=$ANDROID_API JOBS=$JOBS"
echo "[base-libs] CC=$CC"
echo "[base-libs] NDK=$NDK_ROOT"

# 系统库白名单：唯一事实源是 scripts/native-deps.txt（判据 3/4/5 也读它）。
# 这里**读它**而不抄一份，抄一份必然漂移 —— 判据之间不许有两份名单。
DEPS_FILE="$ROOT_DIR/scripts/native-deps.txt"
[ -f "$DEPS_FILE" ] || die "缺系统库白名单" "$DEPS_FILE 不存在 —— 无法判断「同目录库依赖」，判据无依据"
SYSTEM_LIBS=" $( { grep -v '^[[:space:]]*#' "$DEPS_FILE" | grep -v '^[[:space:]]*$' || true; } | tr -d '\r' | tr '\n' ' ') "
[ -n "${SYSTEM_LIBS// /}" ] || die "系统库白名单是空的" "$DEPS_FILE 被清空了 —— 那会把所有系统库都当成同目录库"
echo "[base-libs] 系统库白名单 $(printf '%s' "$SYSTEM_LIBS" | wc -w) 项（源 $DEPS_FILE）"

# 链接共享库的统一切换。铁律 2 落在 RUNPATH_FLAG。
RUNPATH_FLAG="-Wl,--enable-new-dtags -Wl,-rpath,\$ORIGIN"

# ── 产物自检：架构 / 16KB 对齐 / 有动态段 / 无 PT_INTERP / soname / RUNPATH ──
# 判据不是「编出来了」，而是「装机后 linker 真能解析」。
# 每条判据都对着一个真实故障写的，见文件头的实测事实表。
check_lib() {
  local f="$1" min="$2"
  if [ ! -f "$f" ]; then die "未产出" "$(basename "$f") 没编出来"; fi
  local s; s=$(stat -c%s "$f")
  if [ "$s" -le "$min" ]; then die "产物可疑" "$(basename "$f") 仅 $s 字节"; fi

  local hdr dyn interp
  hdr="$("$LLVM_READELF" -W -h "$f" 2>/dev/null || true)"
  if ! printf '%s\n' "$hdr" | grep -qE 'AArch64|aarch64|arm64|ARM64'; then
    printf '  %s 的文件头：\n' "$(basename "$f")" | sed 's/^/         /'
    printf '%s\n' "$hdr" | sed 's/^/         /'
    die "架构不对" "$(basename "$f") 不是 arm64 —— 装到真机上 exec format error"
  fi

  # 16KB 页设备（Android 15+）要求 LOAD 段按 16KB 对齐，否则 ELIBBAD。
  local phdrs bad
  phdrs="$("$LLVM_READELF" -W -l "$f" 2>/dev/null || true)"
  if [ -z "$phdrs" ]; then die "读不出 Program Headers" "$(basename "$f") 不是合法 ELF？"; fi
  bad="$(printf '%s\n' "$phdrs" | awk '/^[[:space:]]*LOAD/{print $NF}' \
        | while read -r al; do
            case "$al" in 0x[0-9a-fA-F]*) ;; *) printf ' %s(取数取错列)' "$al"; continue ;; esac
            d=$(( al )); [ "$d" -eq 0 ] && continue
            [ $(( d % 16384 )) -ne 0 ] && printf ' %s' "$al"
          done)"
  if [ -n "$bad" ]; then
    printf '  %s 的 LOAD 段：\n' "$(basename "$f")" | sed 's/^/         /'
    die "16KB 对齐不合格" "$(basename "$f") 这些 LOAD 段对齐不是 16KB 整数倍：$bad"
  fi

  dyn="$("$LLVM_READELF" -W -d "$f" 2>/dev/null || true)"
  if ! printf '%s\n' "$dyn" | grep -q 'NEEDED'; then
    die "不是动态库" "$(basename "$f") 动态段里没有 DT_NEEDED —— 别名/静态产物混进来了"
  fi

  # 铁律 3
  interp="$(printf '%s\n' "$phdrs" | sed -n 's/.*\[Requesting program interpreter: \(.*\)\].*/\1/p')"
  if [ -n "$interp" ]; then
    die "库带了 PT_INTERP" "$(basename "$f") interp=$interp —— 带解释器段的是可执行件（bash/rg 那类），不是库"
  fi

  # 铁律 1：soname 要么等于文件名，要么不存在；**不得带版本号**。
  local soname base n
  n="$(printf '%s\n' "$dyn" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' | wc -l)"
  soname="$(printf '%s\n' "$dyn" | sed -n 's/.*(SONAME).*\[\(.*\)\].*/\1/p')"
  base="$(basename "$f")"
  if [ -n "$soname" ] && [ "$soname" != "$base" ]; then
    die "soname 与文件名不符" "$base 的 SONAME='$soname' —— bionic 按 DT_NEEDED 里的文件名找库，'$(printf '%s' "$soname")' 这份不存在就 cannot locate symbol"
  fi

  # 铁律 2：**条件**判据 —— 只有「依赖非系统库」时才要求 $ORIGIN。
  # 为什么是条件而不是一刀切：现役的 libc++_shared.so / liblobosflock.so 都是
  # RUNPATH 为空，照「一律要求 $ORIGIN」会把它们判死。它们在真机上确实正常，
  # 因为它们的 DT_NEEDED 只有系统库（libc.so/libm.so/libdl.so），bionic 找得到；
  # 而 RuntimeEnvironment 还设了 LD_LIBRARY_PATH 作兜底。
  # 只有当 DT_NEEDED 里有**非系统库**（同目录随包库）时，空 RUNPATH 才真的致命。
  local runpath rpath non_sys
  runpath="$(printf '%s\n' "$dyn" | sed -n 's/.*(RUNPATH).*\[\(.*\)\].*/\1/p')"
  rpath="$(printf '%s\n' "$dyn" | sed -n 's/.*(RPATH).*\[\(.*\)\].*/\1/p')"
  non_sys="$(printf '%s\n' "$dyn" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}' \
            | while read -r lib; do
                case " $SYSTEM_LIBS " in
                  *" $lib "*) ;;
                  *) printf '%s ' "$lib" ;;
                esac
              done)"
  if [ -z "$non_sys" ]; then
    # 无同目录库依赖：RUNPATH 有无都无所谓，但有 RPATH 而无 RUNPATH 仍是形态错。
    if [ -n "$rpath" ] && [ -z "$runpath" ]; then
      die "只有 DT_RPATH" "$base 有 RPATH=[$rpath] 但 bionic 忽略它 —— 链接须加 -Wl,--enable-new-dtags"
    fi
    echo "[ok] $base $s 字节  SONAME=${soname:-（无）}  RUNPATH=${runpath:-（空，可接受：只依赖系统库）}  NEEDED=${n}项（无同目录库）"
    return 0
  fi
  case "$runpath" in
    *'$ORIGIN'*) ;;
    *)
      if [ -n "$rpath" ]; then
        die "只有 DT_RPATH" "$base 有 RPATH=[$rpath] 但 bionic 忽略它 —— 依赖同目录库$non_sys须加 -Wl,--enable-new-dtags"
      fi
      die "RUNPATH 不含 \$ORIGIN" "$base 依赖同目录库$non_sys但 RUNPATH='${runpath:-（无）}' —— 换个落位目录就 CANNOT LINK"
      ;;
  esac

  echo "[ok] $base $s 字节  SONAME=${soname:-（无，与 liblobosflock.so 同形态）}  RUNPATH=[$runpath]  NEEDED=${n}项（含同目录库$non_sys）"
}

fetch() { bash "$ROOT_DIR/scripts/fetch-pinned.sh" --pin "$1" "$2"; }

# 从前缀里挑出共享库：无版本名优先，其次任一有版本名的。
pick_so() {
  local pre="$1" name="$2" cand
  cand="$(ls "$pre"/lib/"$name".so "$pre"/lib/"$name".so.* 2>/dev/null | head -1 || true)"
  [ -n "$cand" ] || die "前缀里没有 $name" "ls $pre/lib/ 看实际产出了什么：$(ls "$pre/lib" 2>/dev/null | tr '\n' ' ')"
  printf '%s' "$cand"
}

# ── libz ────────────────────────────────────────────────────────────────────
# zlib 1.3.2 自带 CMake。用 NDK 自带的 android.toolchain.cmake 交叉编，
# 不用「空 CMAKE_TOOLCHAIN_FILE + 手传 ANDROID 变量」那种写法（不成立）。
build_zlib() {
  echo "== zlib（共享 libz.so）=="
  local src="$WORK/zlib" pre="$WORK/prefix"
  fetch zlib "$WORK/zlib.tar.gz"
  rm -rf "$src" && mkdir -p "$src"
  tar xzf "$WORK/zlib.tar.gz" -C "$src" --strip-components=1
  [ -f "$src/CMakeLists.txt" ] || die "zlib 源码树异常" "没有 CMakeLists.txt"

  rm -rf "$pre" "$WORK/zlib-build"
  (
    set -e
    cmake -S "$src" -B "$WORK/zlib-build" \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN" \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$ANDROID_API" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="$pre" -DCMAKE_INSTALL_LIBDIR=lib \
      -DBUILD_SHARED_LIBS=ON -DZLIB_BUILD_EXAMPLES=OFF \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
      -DCMAKE_SHARED_LINKER_FLAGS="$RUNPATH_FLAG" \
      > "$WORK/zlib-cmake.log" 2>&1 \
      || { echo "=== zlib cmake 配置失败取证（末 40 行）==="; tail -40 "$WORK/zlib-cmake.log"; exit 1; }
    cmake --build "$WORK/zlib-build" -j"$JOBS" >> "$WORK/zlib-cmake.log" 2>&1 \
      || { echo "=== zlib 编译失败取证（末 40 行）==="; tail -40 "$WORK/zlib-cmake.log"; exit 1; }
    cmake --install "$WORK/zlib-build" >> "$WORK/zlib-cmake.log" 2>&1 \
      || { echo "=== zlib 安装失败取证（末 40 行）==="; tail -40 "$WORK/zlib-cmake.log"; exit 1; }
  )

  local so; so="$(pick_so "$pre" libz.so)"
  cp -f "$so" "$J/libz.so"
  "$LLVM_STRIP" --strip-unneeded "$J/libz.so" 2>/dev/null || true
  check_lib "$J/libz.so" 100000
}

# ── libssl / libcrypto ─────────────────────────────────────────────────────
# openssl 的 shared 构建默认带版本化 soname（libssl.so.3）。CI 上没有 patchelf，
# 改不了。所以走 openssl 自己的开关：共享库命名由 `-Wl,-soname` 直接在链接期定死，
# 由 `SHARED_LIBS` 指明要编哪些。
build_openssl() {
  echo "== openssl（共享 libssl.so + libcrypto.so）=="
  local src="$WORK/openssl" pre="$WORK/prefix"
  fetch openssl "$WORK/openssl.tar.gz"
  rm -rf "$src" && mkdir -p "$src"
  tar xzf "$WORK/openssl.tar.gz" -C "$src" --strip-components=1
  [ -x "$src/Configure" ] || die "openssl 源码树异常" "没有 Configure"

  export ANDROID_API
  export SOURCE_DATE_EPOCH="$(bash "$ROOT_DIR/scripts/fetch-pinned.sh" --time-base)"
  # no-module：省掉 .pyd（Windows 用）。no-apps：不要 fips-install 之类。
  # 关键是 shared：出 libssl.so / libcrypto.so。
  (
    set -e
    cd "$src"
    ./Configure android-arm64 -fPIC -D__ANDROID_API__=$ANDROID_API \
      --prefix="$pre" --openssldir="$pre/ssl" \
      shared no-tests no-ui-console no-module \
      -Wl,--enable-new-dtags -Wl,-rpath,'$ORIGIN' \
      > "$WORK/openssl-configure.log" 2>&1 \
      || { echo "=== openssl Configure 失败取证（末 30 行）==="; tail -30 "$WORK/openssl-configure.log"; exit 1; }
    make -j"$JOBS" build_libs > "$WORK/openssl-build.log" 2>&1 \
      || { echo "=== openssl 编译失败取证（error 行 + 末 30 行）==="; \
           grep -nE "error:|Error [0-9]+$|undefined symbol" "$WORK/openssl-build.log" | head -20 || true; \
           tail -30 "$WORK/openssl-build.log"; exit 1; }
  )
  bash "$ROOT_DIR/scripts/verify-userland-build-date.sh" "$src" \
    || die "openssl 构建时间基准不合格" "上条命令已打印原因"

  # openssl 把共享库放在顶层（$src/libssl.so.3），不带 .so 后缀的那个是链接期临时产物。
  local base so
  for base in ssl crypto; do
    so="$(pick_so "$src" "lib$base.so")"
    cp -f "$so" "$J/lib$base.so"
    "$LLVM_STRIP" --strip-unneeded "$J/lib$base.so" 2>/dev/null || true
    check_lib "$J/lib$base.so" 500000
  done
}

# ── libcurl ─────────────────────────────────────────────────────────────────
build_curl() {
  echo "== curl（共享 libcurl.so）=="
  local src="$WORK/curl" pre="$WORK/prefix"
  fetch curl "$WORK/curl.tar.gz"
  rm -rf "$src" && mkdir -p "$src"
  tar xzf "$WORK/curl.tar.gz" -C "$src" --strip-components=1
  [ -f "$src/configure" ] || die "curl 源码树异常" "没有 configure"

  export PKG_CONFIG_PATH="$pre/lib/pkgconfig"
  export CPPFLAGS="-I$pre/include"
  export LDFLAGS="-L$pre/lib $RUNPATH_FLAG"
  export LIBS="-lssl -lcrypto -lz"
  (
    set -e
    cd "$src"
    ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
      --prefix="$pre" --with-openssl="$pre" --with-zlib="$pre" \
      --with-ca-path=/system/etc/security/cacerts \
      --enable-shared --disable-static \
      --disable-ldap --without-libssh2 --without-libidn2 \
      --without-nghttp2 --without-brotli --without-zstd --without-libpsl --disable-manual \
      --disable-ftp --disable-file --disable-dict --disable-telnet --disable-tftp \
      --disable-pop3 --disable-imap --disable-smtp --disable-gopher --disable-mqtt --disable-rtsp \
      --enable-http \
      ac_cv_lib_crypto_HMAC_Update=yes ac_cv_lib_crypto_HMAC_Init_ex=yes \
      curl_cv_lib_crypto_HMAC_Update=yes curl_cv_lib_crypto_HMAC_Init_ex=yes \
      ac_cv_lib_ssl_SSL_new=yes curl_cv_lib_ssl_SSL_connect=yes curl_cv_lib_ssl_SSL_get_peer_certificate=yes \
      curl_cv_openssl_with_ldl=yes curl_cv_openssl_with_ldl_and_lpthread=yes \
      CC="$CC" AR="$LLVM_AR" RANLIB="$LLVM_RANLIB" > "$WORK/curl-configure.log" 2>&1 \
      || { echo "=== curl configure 失败取证（openssl 相关行 + 末 20 行）==="; \
           grep -i openssl "$WORK/curl-configure.log" | tail -12 || true; \
           tail -20 "$WORK/curl-configure.log"; exit 1; }
    make -j"$JOBS" -C lib >> "$WORK/curl-build.log" 2>&1 \
      || { echo "=== curl 编译失败取证（末 30 行）==="; tail -30 "$WORK/curl-build.log"; exit 1; }
  )

  local so; so="$(pick_so "$src/lib/.libs" libcurl.so)"
  cp -f "$so" "$J/libcurl.so"
  "$LLVM_STRIP" --strip-unneeded "$J/libcurl.so" 2>/dev/null || true
  check_lib "$J/libcurl.so" 200000
}

# ── 汇总 ────────────────────────────────────────────────────────────────────
declare -A RECIPE=( [zlib]=build_zlib [openssl]=build_openssl [curl]=build_curl )
if [ -n "${ONLY:-}" ]; then
  : "${RECIPE[$ONLY]:?ONLY 的取值只能是 zlib|openssl|curl（现有：${!RECIPE[*]}）}"
  "${RECIPE[$ONLY]}"
else
  build_zlib
  build_openssl
  build_curl
fi

echo
echo "== 底座共享库就位 =="
for f in libz.so libssl.so libcrypto.so libcurl.so; do
  if [ -f "$J/$f" ]; then
    echo "[ok] $J/$f $(stat -c%s "$J/$f") 字节"
  else
    echo "[缺] $J/$f 没产出"
  fi
done
echo "[base-libs] 产物进 jniLibs → APK lib/arm64-v8a/ → PrefixProvisioner 铺到 \$PREFIX/lib"
echo "[base-libs] 登记须同步：NativeAssetRegistry.kt（CAPABILITY，buildTier=upstream）"
echo "             重新生成清单：node scripts/gen-native-assets.js"
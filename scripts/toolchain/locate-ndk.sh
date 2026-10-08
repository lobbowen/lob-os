#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(dirname "$0")"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

WANT_NDK="$(bash "$ROOT_DIR/scripts/toolchain/fetch-pinned.sh" --ndk)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"

NDK=""
if [ -n "$SDK" ] && [ -d "$SDK/ndk/$WANT_NDK" ]; then
  NDK="$SDK/ndk/$WANT_NDK"
  echo "[ndk] 用 $NDK"
elif [ -n "$SDK" ] && [ -d "${ANDROID_NDK_LATEST_HOME:-}" ] \
     && [ "$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' \
            "$ANDROID_NDK_LATEST_HOME/source.properties" 2>/dev/null || true)" = "$WANT_NDK" ]; then
  NDK="$ANDROID_NDK_LATEST_HOME"
  echo "[ndk] 用 $NDK"
elif [ -n "${INSTALL_NDK:-}" ] && [ -n "$SDK" ] && command -v sdkmanager >/dev/null 2>&1; then
  echo "[ndk] 装 ndk;$WANT_NDK …"
  LOG="$(mktemp)"
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK" "ndk;$WANT_NDK" >"$LOG" 2>&1 || true
  if [ ! -d "$SDK/ndk/$WANT_NDK" ]; then
    die "装 NDK 失败" \
      "sdkmanager 没能装出 ndk;$WANT_NDK；它自己的输出（末 30 行）：" \
      "$(tail -30 "$LOG")"
  fi
  rm -f "$LOG"
  NDK="$SDK/ndk/$WANT_NDK"
else
  HAVE=""
  [ -n "${ANDROID_NDK_LATEST_HOME:-}" ] && HAVE="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' \
      "$ANDROID_NDK_LATEST_HOME/source.properties" 2>/dev/null || true)"
  die "没有 $WANT_NDK 的 NDK" \
    "钉值表要 $WANT_NDK，但这里找不到它（runner 自带的是 ${HAVE:-（读不出）}）。" \
    "两个选择：(a) 把 component-sources.json 的 ndkVersion 改成 $HAVE 并按它重取钉值；" \
    "(b) 让 CI 装 $WANT_NDK —— 设 INSTALL_NDK=1（CI 侧同时要开 NDK 的 actions/cache，否则每次都下 700 MiB）。" \
    "**不要**只改钉值了事 —— 不真的装上，所有编造 job 仍然会用 runner 自带的那版。"
fi

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android35-clang"
[ -f "$CC" ] || die "无 clang" \
  "缺 $CC" \
  "宿主标签在脚本里写死了 linux-x86_64；runner 若不是 x86_64 要改这里。"

CLANG_VER="$("$CC" --version 2>/dev/null | head -1 || true)"
[ -n "$CLANG_VER" ] || die "clang 跑不起来" \
  "$CC 存在但 --version 没输出 —— 它可能是宿主二进制（不该执行）或依赖缺失。"

TRIPLE="$("$CC" -print-target-triple 2>/dev/null || true)"
[ -n "$TRIPLE" ] || TRIPLE="$("$CC" -dumpmachine 2>/dev/null || true)"
MACH=""
OK_TARGET=0
case "$TRIPLE" in *aarch64*|*arm64*) OK_TARGET=1 ;; esac
if [ "$OK_TARGET" != 1 ]; then
  TMPD="$(mktemp -d)"
  printf 'int probe(void){return 0;}\n' > "$TMPD/probe.c"
  if "$CC" -c -o "$TMPD/probe.o" "$TMPD/probe.c" >/dev/null 2>&1 && [ -f "$TMPD/probe.o" ]; then
    MACH="$(od -An -tx1 -j18 -N2 "$TMPD/probe.o" 2>/dev/null | tr -d ' \n')"
    [ "$MACH" = "b700" ] && OK_TARGET=1
  fi
  rm -rf "$TMPD"
fi
[ "$OK_TARGET" = 1 ] || die "clang 产不出 aarch64 目标" \
  "目标三元组 = ${TRIPLE:-（读不出）}；试编 .o 的 e_machine = ${MACH:-（编不出）}（0xB7 才是 AArch64）" \
  "驱动是 $CC —— 名字里的 aarch64 只是命名约定，判据要看它自己的目标。"

GOT_NDK="$(awk -F= '/^Pkg\.Revision/ {gsub(/[[:space:]]/,"",$2); print $2; exit}' "$NDK/source.properties" 2>/dev/null || true)"
[ -n "$GOT_NDK" ] || die "读不出 NDK 版本" "$NDK/source.properties 里没有 Pkg.Revision"
[ "$GOT_NDK" = "$WANT_NDK" ] || die "NDK 版本与钉值不符" \
  "钉值表要 $WANT_NDK，实际在用的是 $GOT_NDK（目录 $NDK）"

echo "[ndk] 目录=$NDK 版本=$GOT_NDK"
echo "[ndk] clang: $CLANG_VER"
[ -n "$TRIPLE" ] && echo "[ndk] 目标三元组: $TRIPLE"
{ echo "CC=$CC"
  echo "CXX=$TC/aarch64-linux-android35-clang++"
  echo "LLVM_AR=$TC/llvm-ar"
  echo "LLVM_RANLIB=$TC/llvm-ranlib"
  echo "LLVM_STRIP=$TC/llvm-strip"
  echo "LLVM_READELF=$TC/llvm-readelf"; } >> "$GITHUB_ENV"
echo "CC=$CC" >> "${GITHUB_STEP_SUMMARY:-/dev/null}" 2>/dev/null || true
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "$0")" && pwd)"
FILE="${1:?需要 ELF 文件路径}"
LABEL="${2:-$(basename "$FILE")}"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")"
  exit 1
}

[ -f "$FILE" ] || die "没有产物可判" "$FILE 不存在"

READELF="${LLVM_READELF:-}"
if [ -z "$READELF" ] && [ -n "${CC:-}" ]; then
  READELF="$(dirname "$CC")/llvm-readelf"
fi
if [ -z "$READELF" ] || [ ! -f "$READELF" ]; then
  CAND="$(command -v llvm-readelf 2>/dev/null || command -v readelf 2>/dev/null || true)"
  if [ -z "$CAND" ]; then
    for c in "${ANDROID_NDK:-}" "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
             "${ANDROID_SDK_ROOT:-}/ndk" "${ANDROID_HOME:-}/ndk"; do
      [ -n "$c" ] && [ -d "$c" ] || continue
      for p in "$c"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
        [ -f "$p" ] && CAND="$p" && break 2
      done
    done
  fi
  READELF="$CAND"
fi
[ -n "$READELF" ] && [ -f "$READELF" ] \
  || die "缺 readelf" "LLVM_READELF/CC 都没给，PATH 与 ANDROID_NDK* 下也找不到 readelf —— 没有它就分不清系统库与缺失库，判据无依据（真机上会 cannot locate symbol）"

DEPS_FILE="$HERE/native-deps.txt"
[ -f "$DEPS_FILE" ] || die "缺系统库白名单" "$DEPS_FILE 不存在 —— 无法判断「谁是系统自带」，判据无依据"
SYSTEM_LIBS=" $( { grep -v '^[[:space:]]*#' "$DEPS_FILE" | grep -v '^[[:space:]]*$' || true; } | tr -d '\r' | tr '\n' ' ') "
[ -n "${SYSTEM_LIBS// /}" ] || die "系统库白名单是空的" "$DEPS_FILE 被清空了 —— 那会把所有系统库都当成缺失依赖"

APK_LIBS=" libc++_shared.so libz.so libssl.so libcrypto.so libcurl.so liblobosflock.so "

DYN="$("$READELF" -W -d "$FILE" 2>/dev/null || true)"
if ! printf '%s' "$DYN" | grep -q 'Dynamic section'; then
  echo "[$LABEL] 无 .dynamic 段（静态产物）—— 依赖闭包判据不适用；静态件不受 LD_PRELOAD 覆盖，形状门会判红"
  exit 0
fi

NEEDED="$(printf '%s\n' "$DYN" | sed -n 's/.*(NEEDED).*\[\(.*\)\].*/\1/p' | tr '\n' ' ')"
RUNPATH="$(printf '%s\n' "$DYN" | sed -n 's/.*(RUNPATH).*\[\(.*\)\].*/\1/p')"
RPATH="$(printf '%s\n' "$DYN" | sed -n 's/.*(RPATH).*\[\(.*\)\].*/\1/p')"

if [ -z "${NEEDED// /}" ]; then
  echo "[ok] $LABEL 动态但无 DT_NEEDED —— 无外部依赖可解析"
  exit 0
fi

LIBDIR="$(cd "$(dirname "$FILE")" && pwd)/lib"
LOCAL_LIBS=""
[ -d "$LIBDIR" ] && LOCAL_LIBS=" $(ls "$LIBDIR" 2>/dev/null | tr '\n' ' ') "

MISSING=""
SELF_DEPS=""
for lib in $NEEDED; do
  [ "$lib" = "$(basename "$FILE")" ] && continue
  case "$SYSTEM_LIBS" in *" $lib "*) continue ;; esac
  case "$APK_LIBS"     in *" $lib "*) continue ;; esac
  case "$LOCAL_LIBS"  in *" $lib "*) SELF_DEPS="$SELF_DEPS $lib"; continue ;; esac
  MISSING="$MISSING $lib"
done

if [ -n "$MISSING" ]; then
  echo "::error title=依赖闭包断了::$LABEL 依赖这些库，但它们既不在 bionic 白名单、也不是 APK 基础库、也不在件自己的 lib/ 下：$MISSING"
  echo "         白名单见 $DEPS_FILE。装到真机上报 'cannot locate symbol' —— 编译期完全看不出来。"
  printf '%s\n' "$DYN" | grep -E 'NEEDED|RPATH|RUNPATH' | sed 's/^/         /'
  exit 1
fi

if [ -n "$SELF_DEPS" ]; then
  case "$RUNPATH" in
    *'$ORIGIN'*)
      echo "[ok] $LABEL 依赖同目录库$SELF_DEPS，靠 DT_RUNPATH=$RUNPATH 自解析"
      ;;
    *)
      if [ -n "$RPATH" ]; then
        die "只有 DT_RPATH" "$LABEL 有 RPATH=[$RPATH] 但 bionic 忽略它 —— 依赖同目录库$SELF_DEPS须加 -Wl,--enable-new-dtags"
      fi
      die "同目录依赖不自解析" "$LABEL 依赖同目录库$SELF_DEPS却无含 \$ORIGIN 的 DT_RUNPATH（现有 RUNPATH=${RUNPATH:-（无）}）—— 载荷 run_code 起子进程时环境为空，必然 CANNOT LINK"
      ;;
  esac
fi

N_SYS=0; N_APK=0; N_LOCAL=0
for lib in $NEEDED; do
  [ "$lib" = "$(basename "$FILE")" ] && continue
  if printf '%s' "$SYSTEM_LIBS" | grep -qw -- "$lib"; then N_SYS=$((N_SYS+1)); continue; fi
  case "$APK_LIBS" in *" $lib "*) N_APK=$((N_APK+1)); continue ;; esac
  N_LOCAL=$((N_LOCAL+1))
done
echo "[ok] $LABEL 依赖闭环：$(printf '%s' "$NEEDED" | wc -w) 项 NEEDED —— 系统 $N_SYS · APK基础库 $N_APK · 同目录 $N_LOCAL"
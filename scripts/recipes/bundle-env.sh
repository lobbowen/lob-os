#!/usr/bin/env bash

set -uo pipefail

PIECE_NAME="${1:?用法: source bundle-env.sh <件名>}"

PIECE_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$PIECE_HERE/../.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/toolchain/elf-form.sh"

ABI="${ABI:-arm64-v8a}"
OUT="${OUT:-dist}"
case "$OUT" in /*) ;; *) OUT="$ROOT_DIR/$OUT" ;; esac

WORK="$ROOT_DIR/work/$PIECE_NAME"
OUT_DIR="$WORK/out"
mkdir -p "$OUT/bin" "$WORK"

die() {
  local title="$1"; shift
  echo "::error title=$title::$(printf '%s\n' "$@")" >&2
  exit 1
}

if [ -z "${CC:-}" ]; then
  [ -f "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" ] \
    || die "缺 CC" "环境里没有 CC，也没有 scripts/toolchain/locate-ndk.sh"

  _GHE="$(mktemp)"
  GITHUB_ENV="$_GHE" bash "$ROOT_DIR/scripts/toolchain/locate-ndk.sh" \
    || die "定位 NDK 失败" "locate-ndk.sh 没跑通（见上面的输出）"

  while IFS="=" read -r _k _v; do
    case "$_k" in
      CC|CXX|NDK|LLVM_AR|LLVM_RANLIB|LLVM_STRIP|LLVM_READELF|TRIPLE)
        printf -v "$_k" "%s" "$_v"
        export "$_k"
        ;;
    esac
  done < "$_GHE"
  rm -f "$_GHE"
fi
[ -n "${CC:-}" ] || die "定位不到 NDK 的 clang" "CC 为空"

TC_DIR="$(dirname "$CC")"
LLVM_AR="$TC_DIR/llvm-ar"
LLVM_RANLIB="$TC_DIR/llvm-ranlib"
LLVM_STRIP="$TC_DIR/llvm-strip"
LLVM_READELF="$TC_DIR/llvm-readelf"

AR_BIN="$LLVM_AR"
RANLIB_BIN="$LLVM_RANLIB"

NDK_ROOT="$(cd "$(dirname "$CC")/../../../../.." && pwd)"

API="${ANDROID_API:-35}"

JOBS="${JOBS:-2}"

check_so() {
  local f="$1" min="${2:-1000}"
  [ -f "$f" ] || return 1
  local sz
  sz="$(wc -c < "$f")"
  [ "$sz" -ge "$min" ] || return 1

  local form
  form="$(elf_form "$f" 2>/dev/null)"
  case "$form" in
    "$ELF_FORM_EXEC"|"$ELF_FORM_DYN_INTERP"|"$ELF_FORM_DYN_PLAIN"|"$ELF_FORM_SHEBANG") return 0 ;;
    *) return 1 ;;
  esac
}

check_exe() {
  local f="$1" min="${2:-1000}"
  check_so "$f" "$min" || return 1
  [ -x "$f" ]
}

version_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).version;
    if (v) process.stdout.write(String(v));
  ' "$ROOT_DIR/scripts/component-sources.json" "$1"
}

author_entry_name_of() {
  node -e '
    const s = require(process.argv[1]).sources || {};
    const v = (s[process.argv[2]] || {}).entryName;
    if (v) process.stdout.write(String(v));
  ' "$ROOT_DIR/scripts/component-sources.json" "$1"
}

emit_piece() {
  local id="$1" out="$2" entry="$3" min="${4:-1000}" bins="${5:-}"

  local marker="$(node "$ROOT_DIR/scripts/toolchain/read-marker.js" "$id")"

  local so base dir f b ver
  ver="$(version_of "$id")"
  [ -n "$ver" ] || die "${id} 没有版本" "scripts/component-sources.json 里查不到"

  local pick=""

  local want_entry="$(author_entry_name_of "$id")"
  if [ -n "$want_entry" ] && [ -f "$out/bin/$want_entry" ]; then
    local wf="$(elf_form "$out/bin/$want_entry" 2>/dev/null)"
    if elf_form_is_program "$wf"; then pick="bin/$want_entry"; fi
  fi
  if [ -z "$pick" ] && [ -d "$out/bin" ]; then
    for f in $(ls -A "$out/bin" 2>/dev/null | LC_ALL=C sort); do
      p="$out/bin/$f"
      [ -f "$p" ] || continue
      ff="$(elf_form "$p" 2>/dev/null)"
      if elf_form_is_program "$ff"; then pick="bin/$f"; break; fi
    done
  fi
  if [ -z "$pick" ]; then
    for f in $(ls -A "$out/lib" 2>/dev/null | LC_ALL=C sort); do
      case "$f" in *.so) pick="lib/$f"; break ;; esac
    done
  fi
  [ -n "$pick" ] || die "${id} 没产出" "bin/ 下无程序形态字节、lib/ 下无主名 .so —— 这一件没有可判形态的字节"
  if [ -n "$entry" ] && [ "$entry" != "$pick" ]; then
    echo "  （这一件按形态算出的入口是 $pick，而调用方声明的是 $entry —— 以形态为准）"
  fi
  entry="$pick"
  so="$out/$entry"
  [ -f "$so" ] || die "${id} 没产出" "$so 不存在"
  base="$(basename "$so")"
  dir="$(dirname "$so")"
  check_so "$so" "$min" || die "${id} 产物不可用" "$so 不是 aarch64 ELF 或小于 $min 字节"

  local form role
  form="$(elf_form "$so")"
  role="$(elf_role_of_form "$form")"
  echo "  （形态：$form → $role）"
  case "$role" in
    program)

      chmod 0755 "$so" ;;
    library)

      chmod 0644 "$so"
      local derived
      derived="$(elf_ensure_chain "$dir" "$ver")"
      [ -n "$derived" ] && echo "  （补齐三段链：$(echo $derived | tr ' ' ' ')）" ;;
    unknown)
      die "${id} 形态判不出来" "$so 不是 ELF —— 形态必须在构建期定下来" ;;
  esac

  if ls "$dir/$base".* >/dev/null 2>&1; then
    for f in "$dir/$base" "$dir/$base".*; do
      [ -e "$f" ] || continue
      b="$(basename "$f")"
      case "$b" in *.so|*.so.*) cp -Pf "$f" "$out/" 2>/dev/null || cp -f "$f" "$out/" ;; esac
    done
    echo "  （共享库整条链一起进包）"
  fi
  bash "$ROOT_DIR/scripts/recipes/package-bundle-zip.sh" "$id" "$out" "$ver" "$entry" "$bins" "$marker"
}
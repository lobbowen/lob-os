#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPS_FILE="$SCRIPT_DIR/native-deps.txt"
MANIFEST_FILE="$SCRIPT_DIR/../.github/native-assets.txt"
CAPS_FILE="$SCRIPT_DIR/../.github/native-capabilities.txt"

DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --deps)     [ $# -ge 2 ] || { echo "[error] --deps 缺值"; exit 2; }; DEPS_FILE="$2"; shift 2 ;;
    --manifest) [ $# -ge 2 ] || { echo "[error] --manifest 缺值"; exit 2; }; MANIFEST_FILE="$2"; shift 2 ;;
    --caps)     [ $# -ge 2 ] || { echo "[error] --caps 缺值"; exit 2; }; CAPS_FILE="$2"; shift 2 ;;
    -*)         echo "[error] 不认识的选项：$1"; echo "[error] 用法: bash $0 [--deps F] [--manifest F] [--caps F] <随包 ELF 目录>"; exit 2 ;;
    *)          [ -z "$DIR" ] || { echo "[error] 只接受一个目录参数（已给过 $DIR，又多一个 $1）"; exit 2; }
                DIR="$1"; shift ;;
  esac
done
if [ -z "$DIR" ] || [ ! -d "$DIR" ]; then
  echo "[error] 用法: bash $0 [--deps F] [--manifest F] <随包 ELF 目录>（目录不存在：${DIR:-未给}）"
  exit 2
fi

READELF="${READELF:-}"
if [ -z "$READELF" ]; then
  READELF="$(command -v readelf 2>/dev/null || true)"
fi
if [ -z "$READELF" ]; then
  for _cand in "${ANDROID_NDK:-/nonexistent}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
    if [ -f "$_cand" ]; then READELF="$_cand"; break; fi
  done
fi
if [ -z "$READELF" ]; then
  echo "[error] 找不到 readelf / llvm-readelf —— 无法校验就不得放行产物。"
  exit 2
fi
if ! "$READELF" --version >/dev/null 2>&1; then
  echo "[error] 指定的 readelf 无法执行：$READELF"
  echo "        这是环境/用法问题（不是产物不合格）—— 取不出数据就一条判据都不会跑，拒绝校验。"
  exit 2
fi
echo "[info] 使用 readelf: $READELF"

if [ ! -f "$DEPS_FILE" ]; then
  echo "[error] 找不到系统库白名单: $DEPS_FILE"
  exit 2
fi
SYSTEM_LIBS=" $( { grep -v '^[[:space:]]*#' "$DEPS_FILE" | grep -v '^[[:space:]]*$' || true; } | tr -d '\r' | tr '\n' ' ') "
[ -n "${SYSTEM_LIBS// /}" ] || { echo "[error] $DEPS_FILE 里没有任何库名（是不是被清空了？）"; exit 2; }

if [ ! -f "$MANIFEST_FILE" ]; then
  echo "[error] 找不到可执行资产清单: $MANIFEST_FILE —— 判据 3 无从进行。"
  exit 2
fi
DEP_SET=" $( { sed -n '/依赖库/,/^#/p' "$MANIFEST_FILE" \
                | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' || true; } | tr -d '\r' | tr '\n' ' ') "
EXEC_SET=" $( { sed -n '/可执行资产本体/,$p' "$MANIFEST_FILE" \
                | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' || true; } | tr -d '\r' | tr '\n' ' ') "

SOFT_SET=" "
if [ -f "$CAPS_FILE" ]; then
  SOFT_SET=" $( { while read -r TIER LIB _ID; do
                     case "$TIER" in soft) echo "$LIB" ;; esac
                   done < "$CAPS_FILE" | tr -d '\r' | tr '\n' ' '; } || true ) "
else
  echo "[error] 找不到档位表: $CAPS_FILE —— soft 件的降级语义无从判定，判据会被误用成硬红。"
  exit 2
fi
[ -n "${DEP_SET// /}${EXEC_SET// /}" ] \
  || { echo "[error] $MANIFEST_FILE 的「依赖库」与「可执行资产本体」两段都是空的 —— 清单被判据 3/4 空转，拒绝校验。"; exit 2; }
if [ -z "${EXEC_SET// /}" ]; then
  echo "[note] 清单的「可执行资产本体」段为空（node 走商店通道后 APK 内没有可执行件）—— 只校验依赖库段。"
fi
echo "== 校验器: $READELF  目录: $DIR =="
echo "   系统库白名单: $DEPS_FILE（$(printf '%s' "$SYSTEM_LIBS" | wc -w) 项）"
echo "   可执行资产: $EXEC_SET"

SO_LIST="$(cd "$DIR" && ls *.so 2>/dev/null || true)"
if [ -z "$SO_LIST" ]; then
  echo "[error] $DIR 下没有任何 .so，没有可校验的东西（多半是下载/拷贝没落到位）。"
  exit 2
fi
BUNDLED=" $(printf '%s\n' "$SO_LIST" | tr '\n' ' ') "

SEEN_EXEC=""
for _b in $EXEC_SET; do
  case "$BUNDLED" in *" $_b "*) SEEN_EXEC="$SEEN_EXEC $_b" ;; esac
done
if [ -n "${EXEC_SET// /}" ] && [ -z "$SEEN_EXEC" ]; then
  echo "[error] $DIR 里没有清单声明的可执行资产（$EXEC_SET）—— 判据 3 将一条不跑，拒绝校验。"
  exit 2
fi
SEEN_DEP=""
for _b in $DEP_SET; do
  case "$BUNDLED" in *" $_b "*) SEEN_DEP="$SEEN_DEP $_b" ;; esac
done
if [ -n "${DEP_SET// /}" ] && [ -z "$SEEN_DEP" ]; then
  echo "[error] $DIR 里没有清单声明的依赖库（$DEP_SET）—— DT_NEEDED 闭环无从进行。"
  exit 2
fi
echo "   依赖库段: ${DEP_SET:-（空）}"
echo "   已对上: 依赖库=$SEEN_DEP 可执行=$SEEN_EXEC"

fail=0
soft_degraded=""
for f in "$DIR"/*.so; do
  base="$(basename "$f")"
  line=""
  case "$SOFT_SET" in
    *" $base "*) tag="[soft-FAIL] $base（降级，不判红）"; tier=soft ;;
    *)          tag="[FAIL] $base"; tier=hard ;;
  esac
  note_fail() {
    if [ "$tier" = "soft" ]; then
      soft_degraded="$soft_degraded $base"
      return 0
    fi
    fail=1
  }
  hdr="$("$READELF" -W -h "$f" 2>/dev/null || true)"
  if [ -z "$hdr" ]; then
    echo "readelf 读不出 ELF 文件头，不是合法 ELF？"
    note_fail
    continue
  fi
  phdrs="$("$READELF" -W -l "$f" 2>/dev/null || true)"
  if [ -z "$phdrs" ]; then
    echo "readelf 读不出 Program Headers，形态判据全部无从进行。"
    note_fail
    continue
  fi

  machine="$(printf '%s\n' "$hdr" | awk -F': *' '/^[[:space:]]*Machine:/{gsub(/[[:space:]]+$/,"",$2); print $2}')"
  case "$machine" in
    AArch64|aarch64|arm64|ARM64) : ;;
    *)
      echo "  $tag —— 架构是「${machine:-读不出}」，不是 arm64/aarch64。"
      echo "         本仓只投 arm64-v8a；装到真机上就是 exec format error。"
      echo "         注：不同工具链的 readelf 对同一架构写法不同（AArch64 / aarch64 / arm64），三者都接受。"
      printf '%s\n' "$hdr" | { grep -E "Machine|Type:" || true; } | sed 's/^/         /'
      note_fail
      continue
      ;;
  esac
  line="arch=$machine"

  load_aligns="$(printf '%s\n' "$phdrs" | awk '/^[[:space:]]*LOAD/{print $NF}')"
  if [ -z "$load_aligns" ]; then
    echo "  $tag —— 没有 LOAD 段（或读不出），16KB 对齐判据无从进行。"
    printf '%s\n' "$phdrs" | sed 's/^/         /'
    note_fail
    continue
  fi
  bad_align=""
  n_load=0
  while IFS= read -r al; do
    [ -n "$al" ] || continue
    case "$al" in
      0x[0-9a-fA-F]*) : ;;
      *) bad_align="$bad_align $al(非十六进制，取数取错列)"; continue ;;
    esac
    dec=$(( al ))
    if [ "$dec" -eq 0 ] || [ $(( dec % 16384 )) -ne 0 ]; then
      bad_align="$bad_align $al"
    fi
    n_load=$(( n_load + 1 ))
  done <<<"$load_aligns"
  if [ -n "$bad_align" ]; then
    echo "  $tag —— 这些 LOAD 段的对齐不是 16KB 的整数倍:$bad_align"
    echo "         Android 15+ 在 16KB 页设备上会返回 ELIBBAD / Exec format error。"
    printf '%s\n' "$phdrs" | { grep -E '^ *LOAD' || true; } | sed 's/^/         /'
    note_fail
    continue
  fi
  line="$line 16KB-ok(${n_load}段)"

  # ── 动态段：判据 3/4/5 的取数 ──────────────────────────────────────────
  dyn="$("$READELF" -W -d "$f" 2>/dev/null || true)"
  has_dynamic="$(printf '%s\n' "$phdrs" | awk '/^[[:space:]]*DYNAMIC/{print "y"}')"
  needed="$(printf '%s\n' "$dyn" | awk '/NEEDED/ {gsub(/[\[\]]/,"",$NF); print $NF}')"
  runpath="$(printf '%s\n' "$dyn" | sed -n 's/.*(RUNPATH).*\[\(.*\)\].*/\1/p')"
  rpath="$(printf '%s\n' "$dyn" | sed -n 's/.*(RPATH).*\[\(.*\)\].*/\1/p')"

  # ── 3) 解释器 ──────────────────────────────────────────────────────────
  interp="$(printf '%s\n' "$phdrs" | sed -n 's/.*\[Requesting program interpreter: \(.*\)\].*/\1/p')"
  is_exec=0
  case "$EXEC_SET" in
    *" $base "*) is_exec=1 ;;
  esac
  if [ -n "$interp" ] && [ "$interp" != "/system/bin/linker64" ]; then
    echo "  $tag —— PT_INTERP=$interp —— 这不是 bionic 的解释器。"
    echo "         说明它是拿主机（glibc）工具链链接的，真机上无法 exec。"
    note_fail
    continue
  fi
  if [ "$is_exec" = 1 ]; then
    if [ -z "$interp" ]; then
      echo "  $tag —— 清单说它是要被 exec 的可执行资产，却没有 PT_INTERP。"
      echo "         带 DT_NEEDED 又没有解释器的 ELF，内核起不来（没人替它映射 libc）。"
      echo "         解法：确认它是静态链接（那就把它从可执行资产里摘掉并核对探针），"
      echo "         或核对 android-configure / 交叉链接参数是否用了 NDK 工具链。"
      note_fail
      continue
    fi
    line="$line interp=$interp"
  else
    line="$line ${interp:+interp=$interp}${interp:-interp=无(非可执行资产)}"
  fi

  # ── 动态段能否取数 ─────────────────────────────────────────────────────
  # 判「静态产物」用 PT_DYNAMIC 在不在，不用「readelf -d 输出了什么」：readelf 对
  # 没有动态段的文件照样打印一行 "There is no dynamic section in this file."，
  # 拿输出判空会把「取数失败」和「真的没依赖」混成同一种情形。
  if [ -z "$has_dynamic" ]; then
    case " $DEP_SET " in
      *" $base "*)
        echo "  $tag —— 清单把它当依赖库，但它没有 PT_DYNAMIC（静态产物）。"
        echo "         依赖库必须是可动态链接的共享库（DT_NEEDED 的解析者），"
        echo "         静态可执行文件冒充它会在装机后起不来。"
        note_fail
        continue
        ;;
    esac
    echo "  [skip] $base —— 无 PT_DYNAMIC（静态产物），只判架构/对齐/解释器：$line"
    continue
  fi
  case "$dyn" in
    *Dynamic*) : ;;
    *)
      echo "  $tag —— 有 PT_DYNAMIC 却读不出动态段内容，取数失败而非「无依赖」。"
      note_fail
      continue
      ;;
  esac

  # ── 4) 依赖闭环 ────────────────────────────────────────────────────────
  if [ -z "$needed" ]; then
    echo "  [ok]   $base —— 动态段里没有 DT_NEEDED（无外部依赖）：$line"
    continue
  fi
  missing=""
  local_deps=""
  n_needed=0
  for lib in $needed; do
    n_needed=$(( n_needed + 1 ))
    if [ "$lib" = "$base" ]; then continue; fi
    case "$SYSTEM_LIBS" in
      *" $lib "*) continue ;;
    esac
    case "$BUNDLED" in
      *" $lib "*) local_deps="$local_deps $lib" ;;
      *) missing="$missing $lib" ;;
    esac
  done
  if [ -n "$missing" ]; then
    echo "  $tag —— 这些依赖既不在系统白名单、也没随包投放:$missing"
    echo "         白名单只住 $DEPS_FILE；装到真机上报 'cannot locate symbol'。"
    printf '%s\n' "$dyn" | { grep -E "NEEDED" || true; } | sed 's/^/         /'
    note_fail
    continue
  fi
  line="$line needed=$(printf '%s' "$needed" | wc -w)项闭环"

  # ── 5) 同目录依赖能否自解析 ────────────────────────────────────────────
  if [ -z "$local_deps" ]; then
    echo "  [ok]   $base —— 不依赖同目录随包库：$line"
    continue
  fi
  case "$runpath" in
    *'$ORIGIN'*)
      echo "  [ok]   $base —— 可自解析同目录依赖:$local_deps；$line RUNPATH=[$runpath]"
      ;;
    *)
      if [ -n "$rpath" ]; then
        echo "  $tag —— 只有 DT_RPATH=[$rpath]，bionic 忽略它。"
        echo "         链接须加 -Wl,--enable-new-dtags 才能产出 DT_RUNPATH。"
      else
        echo "  $tag —— 无 DT_RUNPATH，却依赖同目录随包库:$local_deps"
        echo "         空环境（载荷 run_code 起子进程）下必然 CANNOT LINK。"
      fi
      printf '%s\n' "$dyn" | { grep -E "RPATH|RUNPATH|NEEDED" || true; } | sed 's/^/         /'
      note_fail
      ;;
  esac
done

if [ "$fail" -ne 0 ]; then
  echo "==> [error] 有原生产物不满足「形态 + 空环境下自解析依赖」，拒绝。"
  exit 1
fi
if [ -n "$soft_degraded" ]; then
  echo "::warning title=能力降级::以下 soft 档位件形态/依赖不合格，本轮不判红：$soft_degraded"
fi
echo "==> [ok] 全部原生产物形态正确、可自解析同目录依赖"

#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
APK="${1:-}"
ABI="${2:-${ABI:-}}"
MODE="${3:-}"
if [ "$MODE" = "--abi" ]; then MODE="${3:-}"; fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "[error] 用法: bash $0 <apk> [abi] [--report]（apk 必须存在 —— 审计不许空跑）"
  exit 2
fi
case "$MODE" in
  --report) REPORT=1 ;;
  "") REPORT=0 ;;
  *) echo "[error] 未知模式 '$MODE'"; exit 2 ;;
esac
if [ "$REPORT" = "0" ] && [ -z "$ABI" ]; then
  echo "[error] 未给 ABI（第二个参数或 \$ABI）—— 条目名无从拼出，审计无法进行。"
  exit 2
fi
[ -n "$ABI" ] || ABI="arm64-v8a"

command -v unzip >/dev/null 2>&1 || { echo "[error] 找不到 unzip —— 无法审计就不得放行。"; exit 2; }
command -v zipinfo >/dev/null 2>&1 || { echo "[error] 找不到 zipinfo —— 无法审计就不得放行。"; exit 2; }
ZI_OUT="$(mktemp)"; trap 'rm -f "$ZI_OUT"' EXIT
zi_rc=0
zipinfo -1 "$APK" >"$ZI_OUT" 2>/dev/null || zi_rc=$?
if [ "$zi_rc" != "0" ] && [ "$(<"$ZI_OUT")" != "Empty zipfile." ]; then
  if [ "$REPORT" = "1" ]; then
    echo "  [FAIL] zipinfo 读不出条目清单（rc=$zi_rc）—— 不是合法 zip"
    exit 0
  fi
  echo "[error] zipinfo 读不出 $APK 的条目清单（rc=$zi_rc）—— 审计没发生，不放行。"
  exit 2
fi
mapfile -t LIST < "$ZI_OUT"
if [ "${#LIST[@]}" = "1" ] && [ "${LIST[0]}" = "Empty zipfile." ]; then LIST=(); fi
rm -f "$ZI_OUT"

has_exact() {
  local e
  for e in "${LIST[@]}"; do
    [ "$e" = "$1" ] && return 0
  done
  return 1
}
has_prefix() {
  local e
  for e in "${LIST[@]}"; do
    case "$e" in ("$1"*) case "$e" in (*/) ;; (*) return 0 ;; esac ;; esac
  done
  return 1
}

MISSING=""
DEGRADED=""
note_missing() {
  if [ "$REPORT" = "1" ]; then
    echo "  [MISSING] $1 —— $2"
  else
    echo "[error] APK 里缺少 $1"
    echo "        $2"
  fi
  MISSING="$MISSING $1"
}

note_degraded() {
  if [ "$REPORT" = "1" ]; then echo "  [未实现] $1 —— $2"; else echo "  [未实现] $1 —— $2"; fi
  DEGRADED="$DEGRADED $1"
}

echo "== APK: $APK ($(stat -c%s "$APK") 字节, ABI=$ABI) =="
echo "--- lib/ 下的条目 ---"
if [ "$REPORT" = "1" ]; then
  found=0
  for e in "${LIST[@]}"; do
    case "$e" in (lib/*) printf '  %s\n' "$e"; found=1 ;; esac
  done
  [ "$found" = "1" ] || echo "  (无 lib/ 条目 —— 严重异常)"
elif ! has_prefix 'lib/'; then
  echo "[error] 没有 lib/ 条目 —— 原生件一个都不在，审计无对象，不放行。"
  exit 1
fi

echo
echo "--- legacy 程序资产（必须为空）---"
if has_prefix 'assets/kernel'; then
  if [ "$REPORT" = "1" ]; then
    echo "  [FAIL] 含 assets/kernel —— ADR-0005 规定 Program 不随 APK 分发"
    zipinfo -1 "$APK" 2>/dev/null | { grep '^assets/kernel' || true; } | sed 's/^/    /'
  else
    echo "::error title=APK 含 Program 资产::ADR-0005 规定 Program 不随 APK 分发（只从 OTA 源安装）。"
    zipinfo -1 "$APK" 2>/dev/null | { grep '^assets/kernel' || true; }
    exit 1
  fi
else
  echo "[ok] APK 不含 legacy 程序资产（Program 经 OTA 安装）"
fi

echo
echo "--- 小体积原生件（已退役：改走标准件包）---"

echo "  （本段已退役，见下方 assets/pieces/ 那一节）"
echo

echo
echo "--- npm 归口（必须为空）---"
if has_prefix 'assets/npm/'; then
  if [ "$REPORT" = "1" ]; then
    echo "  [FAIL] 含 assets/npm —— npm 只由 C 层清单投放（scripts/recipes/build-component-npm.sh）"
    zipinfo -1 "$APK" 2>/dev/null | { grep '^assets/npm' || true; } | sed 's/^/    /'
  else
    echo "::error title=APK 里留着 npm::assets/npm 在包内 —— npm 应与 git/curl 同级走 C 层签名清单，随包那份是不随清单更新的第二事实源。"
    zipinfo -1 "$APK" 2>/dev/null | { grep '^assets/npm' || true; }
    exit 1
  fi
else
  echo "[ok] APK 内无 assets/npm（npm 走 C 清单）"
fi

echo
echo "--- native-assets.txt 逐资产 ---"
ASSETS="$(grep -v '^[[:space:]]*#' "$ROOT/.github/native-assets.txt" | grep -v '^[[:space:]]*$' | tr -d '\r' || true)"
if [ -z "$ASSETS" ]; then
  if [ "$REPORT" = "1" ]; then
    echo "  [FAIL] 清单为空 —— 本节一条都不会检查"
  else
    echo "[error] .github/native-assets.txt 里没有任何资产 —— 审计无意义，中止。"
    exit 1
  fi
fi
for a in $ASSETS; do
  has_exact "lib/${ABI}/$a" && echo "[ok] lib/${ABI}/$a" \
    || note_missing "lib/${ABI}/$a" "清单来源: .github/native-assets.txt（NativeAssetRegistry 的投影）"
done

echo
echo "--- 预装件包（assets/pieces/<id>.zip）· 装配器只读包 ---"

PIECE_BAD=0
PIECE_N=0
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
for p in $(printf '%s\n' "${LIST[@]}" | grep '^assets/pieces/.*\.zip$'); do
  name="$(basename "$p")"
  id="${name%%.zip}"
  PIECE_N=$((PIECE_N + 1))

  if ! has_exact "$p"; then
    echo "[error] 清单里有 $p 但 APK 里没有"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi

  rm -rf "${W:?}/x"; mkdir -p "$W/x"
  unzip -qq -o "$APK" "$p" -d "$W" 2>/dev/null
  pkg="$W/$p"
  if ! unzip -qq -o "$pkg" -d "$W/x" 2>/dev/null; then
    echo "[error] $id 的包解不开"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi

  if [ ! -f "$W/x/component-meta.json" ]; then
    echo "[error] $id 的包里没有 component-meta.json（装配器靠它认这件是什么）"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi

  mid="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).id||""))}catch(e){}})' < "$W/x/component-meta.json")"
  mver="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).version||""))}catch(e){}})' < "$W/x/component-meta.json")"
  ment="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).entry||""))}catch(e){}})' < "$W/x/component-meta.json")"

  [ "$mid" = "$id" ] || { echo "[error] $id 的说明里 id=$mid 对不上"; PIECE_BAD=$((PIECE_BAD + 1)); continue; }
  [ -n "$mver" ] || { echo "[error] $id 的说明里没有 version"; PIECE_BAD=$((PIECE_BAD + 1)); continue; }

  mkind=""
  if [ -f "$W/x/piece-shape.json" ]; then
    mkind="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).kind||""))}catch(e){}})' < "$W/x/piece-shape.json")"
  fi
  if [ -z "$ment" ] && [ "$mkind" != "library" ]; then
    echo "[error] $id 的说明里没有 entry（而它的形态是 ${mkind:-未知}）"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi
  if [ -z "$ment" ]; then
    echo "  · $id 是纯库件（kind=library）=> 没有程序那一侧 => entry 合法地是空"
  fi

  if [ -n "$ment" ] && [ ! -f "$W/x/$ment" ] && [ ! -L "$W/x/$ment" ]; then
    echo "[error] $id 的包里没有 entry 指的文件 $ment"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi

  printf "  ✓ %-9s %-12s entry=%s\n" "$id" "$mver" "$ment"
done

pieces_rc=0
WANT_N="$(bash "$ROOT/scripts/toolchain/fetch-prebuilt-pieces.sh" --print-pieces 2>/dev/null)" || pieces_rc=$?
if [ "$pieces_rc" != "0" ] || [ -z "$WANT_N" ]; then
  echo "[error] 读不出该取几件（fetch-prebuilt-pieces.sh --print-pieces，rc=$pieces_rc）—— 这一格不能猜，缺字段就报缺，不填看起来对的值"
  exit 1
fi
if [ "$PIECE_N" != "$WANT_N" ]; then
  echo "[error] 预装件包 $PIECE_N 个，该取的件有 $WANT_N 个"
  PIECE_BAD=$((PIECE_BAD + 1))
fi
echo "  预装件包 $PIECE_N 个，该取的件 $WANT_N 个"
[ "$PIECE_BAD" = "0" ] || MISSING="$MISSING piece-pack($PIECE_BAD)"

echo
echo "--- 压缩方式（Stored=未压缩 / Defl=压缩）---"
unzip -v "$APK" | awk '$NF ~ /lib\// {print "  " $NF "  " $2 "  " $3 "  " $4}' || true

if [ "$REPORT" = "1" ]; then
  echo
  if [ -n "$MISSING" ]; then
    echo "  结果: ✗ 缺项:$MISSING（report 模式不判红，只列事实）"
  else
    echo "  结果: ✓ 全部条目在位$([ -n "$DEGRADED" ] && printf '（软降级:%s）' "$DEGRADED")"
  fi
  exit 0
fi

if [ -n "$MISSING" ]; then
  echo "==> [error] 审计不通过，缺项:$MISSING"
  echo "    这正是真机 'cannot locate symbol _ZTVNSt6__ndk119basic_ostringstream'"
  echo "    一类故障的直接原因。请检查 scripts/toolchain/build-node-android.sh 的打包段、"
  echo "    app/build.gradle.kts 的 jniLibs 配置与各 Stage 步骤。"
  exit 1
fi
echo "==> [ok] APK 原生件审计通过"$([ -n "$DEGRADED" ] && printf '（软降级:%s —— 能力未实现，不判红）' "$DEGRADED" || printf '（资产与 npm 全在包里，件走 assets/pieces/）')

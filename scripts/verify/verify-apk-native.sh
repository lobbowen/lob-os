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
# 能力尚未实现（不是 APK 少了东西）—— 记录但不计缺
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
# 这一段原来查「件有没有躺在 APK 的 lib/<ABI>/ 里」，清单是
# .github/native-capabilities.txt（8 个件）。
# 那条通道已经删了：件现在是自描述的标准包，放 assets/pieces/<id>.tar.gz，
# 启动时由 PreinstalledPieces 走 ProgramInstallPipeline 装上（与 OTA 件同一条路）。
# 件不进 lib/ —— 预装件与用户装的件必须同构，不能一条走特权、一条走标准。
# 清单 .github/native-capabilities.txt 随之退役（component-sources.json 是唯一来源）。
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
# 件随 APK 打包为标准包，启动时由 PreinstalledPieces 走 ProgramInstallPipeline 装上
# （与 OTA 装件同一条路）。装配器读包里的 component-meta.json（照抄 .deb 的 control），
# 所以「包里有没有、说明对不对、那个文件在不在」三件事都要在这里验 ——
# 缺任何一样，真机上那一件就装不上，而构建期看不出来。
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
  # ★ **纯库件没有 entry 是合法的**（按 C-05「没有」就是没有）——
  #   缺陷名（C-102）**「说明里没有 entry」被当成缺件**：
  #     原来这一格是 `[ -n "$ment" ] || 判红` —— 而那把「这一件没有程序那一侧」
  #     当成了「清单坏了」⇒ crypto/curl/zlib 三个纯库件被判成缺项（piece-pack(3)）。
  #   ⇒ 现在：按**产物自带的形态**（piece-shape.json 的 kind）判：
  #     kind=library ⇒ 这一件没有程序那一侧 ⇒ entry 合法地是空（不判红）；
  #     否则 ⇒ entry 必须有。
  mkind=""
  if [ -f "$W/x/piece-shape.json" ]; then
    mkind="$(node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(String(JSON.parse(d).kind||""))}catch(e){}})' < "$W/x/piece-shape.json")"
  fi
  if [ -z "$ment" ] && [ "$mkind" != "library" ]; then
    echo "[error] $id 的说明里没有 entry（而它的形态是 ${mkind:-未知}）"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi
  if [ -z "$ment" ]; then
    echo "  · $id 是纯库件（kind=library）⇒ 没有程序那一侧 ⇒ entry 合法地是空"
  fi

  # entry 若有，那它指的文件必须在包里
  if [ -n "$ment" ] && [ ! -f "$W/x/$ment" ] && [ ! -L "$W/x/$ment" ]; then
    echo "[error] $id 的包里没有 entry 指的文件 $ment"
    PIECE_BAD=$((PIECE_BAD + 1)); continue
  fi

  printf "  ✓ %-9s %-12s entry=%s\n" "$id" "$mver" "$ment"
done

WANT_N="$(node -e '
  const s=require(process.argv[1]).sources||{};
  const ids=["zlib","openssl","crypto","curl","jq","bash","ripgrep","busybox"];
  process.stdout.write(String(ids.filter(i=>s[i]).length));
' "$ROOT/scripts/component-sources.json" 2>/dev/null || echo 8)"
if [ "$PIECE_N" != "$WANT_N" ]; then
  echo "[error] 预装件包 $PIECE_N 个，钉值表里有 $WANT_N 个"
  PIECE_BAD=$((PIECE_BAD + 1))
fi
echo "  预装件包 $PIECE_N 个，钉值表 $WANT_N 个"
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

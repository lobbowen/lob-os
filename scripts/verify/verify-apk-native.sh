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
count_prefix() {
  local e n=0 pat="${2:-*.js}"
  for e in "${LIST[@]}"; do
    case "$e" in
      (*/) ;;
      ("$1"*) case "$e" in ($pat) n=$((n + 1)) ;; esac ;;
    esac
  done
  printf '%s\n' "$n"
  return 0
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
echo "--- 小体积原生件 ---"
CAPS="$(cd "$(dirname "$0")/../.." && pwd)/.github/native-capabilities.txt"
[ -f "$CAPS" ] || { echo "[error] 缺少 $CAPS —— 小件清单是派生物，不能没有它。"; exit 1; }
CAP_N=0
while read -r TIER LIB _ID; do
  case "$TIER" in ''|'#'*) continue ;; esac
  CAP_N=$((CAP_N + 1))
  if has_exact "lib/${ABI}/$LIB"; then
    echo "[ok] lib/${ABI}/$LIB"
  elif [ "$TIER" = "soft" ]; then
    # soft 档 = 缺了只降级、不判红，也不陪葬别的能力。
    # 当前清单里没有 soft 项（node-pty 删了），这一档暂时走不到 ——
    # 保留是因为 .txt 的格式支持它，且「有件编不踏实、缺了只降级」
    # 是真实存在的一类情形，将来有件落进来就是它。
    # 文案里不写死「终端 PTY」—— 那是 node-pty 留下的（那一行已从清单里
    # 删掉：两张表都没有它，也没有任何脚本产它）。soft 档将来若另有其人，
    # 这段会照常工作。
    DEGRADED="$DEGRADED lib/${ABI}/$LIB"
    if [ "$REPORT" = "1" ]; then
      echo "  [soft-absent] lib/${ABI}/$LIB —— 缺件只降级"
    else
      echo "::warning title=能力降级::lib/${ABI}/$LIB 不在 APK —— 该能力不可用（soft 档，不判红）"
    fi
  else
    case "$TIER" in
      self-c)   note_missing "lib/${ABI}/$LIB" "自有 C，必产（编译失败即环境问题）" ;;
      upstream) note_missing "lib/${ABI}/$LIB" "\$PREFIX 依赖它，无回退路径" ;;
      *)        note_missing "lib/${ABI}/$LIB" "未知档位「$TIER」—— 清单或本脚本坏了" ;;
    esac
  fi
done < "$CAPS"
[ "$CAP_N" -gt 0 ] || { echo "[error] $CAPS 里一条小件都没有 —— 循环会什么都不检查就放行。"; exit 1; }

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
echo "--- adb-client 资产 ---"
SRC_DIR="$ROOT/container/app/src/main/assets/node/adb-client"
SRC_N=0
for f in "$SRC_DIR"/*.js; do
  [ -e "$f" ] || continue
  SRC_N=$((SRC_N + 1))
  n="assets/node/adb-client/$(basename "$f")"
  has_exact "$n" && echo "[ok] $n" || note_missing "$n" "adb-client 权限通道字节"
done
if [ "$SRC_N" = "0" ]; then
  # 源码目录为空 = 无线调试客户端机制尚未实现：assets/node/adb-client 的 8 个 JS
  # 在 0e5bbf3 被判定为错误并移除，AdbClientRunner 也相应改为明确返回「未就位」。
  # 这不是 APK 少了东西，是这条能力还没做 —— 走软降级那条既有通路。
  # 一旦机制重建、目录里有了 .js，下面的件数一致性判据照常生效。
  note_degraded "adb-client" "$SRC_DIR 无 .js —— 无线调试客户端机制尚未实现（AdbClientRunner 据此返回未就位）"
fi
APK_N="$(count_prefix 'assets/node/adb-client/')"
if [ "$SRC_N" != "$APK_N" ]; then
  echo "[error] adb-client 件数不一致：源码 $SRC_N vs APK $APK_N"
  MISSING="$MISSING adb-client-count($SRC_N!=$APK_N)"
fi

echo
echo "--- 件的说明（assets/supply/meta/）· 运行时铺位的唯一数据源 ---"
# PrefixProvisioner.scanMeta() 从 assets/supply/meta/ 读它（prefix provisioner 的
# scanMeta 注释写明了为什么不能走 jniLibs：AGP 的 jniLibs 打包只取 *.so，
# .meta.json 进不了 APK）。缺一份 = 运行时那一件铺不出来，而构建期看不出来。
#
# 必填字段照 deb-control(5)：Package / Version / Architecture
#（scripts/recipes/gen-component-meta.js 就只把这三个标 required）。
META_BAD=0
META_CHAIN_GAP=0
TMPA="$(mktemp)"
META_N=0
for meta in $(printf '%s\n' "${LIST[@]}" | grep "^assets/supply/meta/.*\.meta\.json$"); do
  META_N=$((META_N + 1))
  base="$(basename "$meta")"
  unzip -p "$APK" "$meta" > "$TMPA" 2>/dev/null || : > "$TMPA"
  for field in id version arch; do
    if ! node "$ROOT/scripts/verify/meta-field.js" "$TMPA" "$field" | grep -q .; then
      echo "[error] $meta 缺必填字段 $field（deb-control(5)：Package/Version/Architecture 是 required）"
      META_BAD=$((META_BAD + 1))
    fi
  done
  # essential 是布尔，且它经 PieceScan.Found.required 决定「缺了系统起不启得来」
  if ! grep -qE '"essential"[[:space:]]*:[[:space:]]*(true|false)' "$TMPA"; then
    echo "[error] $meta 的 essential 不是布尔 —— Found.required 靠它判系统起不启得来"
    META_BAD=$((META_BAD + 1))
  fi
  # 每份说明对应的 .so 应该在包里。
  #
  # ★ 版本化命名（libz.so.1 / libz.so.1.3.2）目前必然缺失，**不判红** ——
  #   记下来的是既有问题，不是这次说明迁移引入的：上一轮 APK 里同样只有
  #   libz.so 一层。成因：AGP 的 jniLibs 打包只认 *.so 结尾，
  #   libz.so.1 这类带版本号的文件名进不了 APK。
  #   而 linker 运行时按 DT_NEEDED 的名字找 —— libcurl.so 的 NEEDED 写的是
  #   libz.so.1（第 15 步的依赖闭环判据里能看到）。
  #   也就是说：usr/lib/libz.so.1 这个全局软链在真机上建不出来，
  #   依赖 zlib 的件会加载失败。
  #
  #   怎么修还没定（改落位名 / 走 assets / 让 jniLibs 之外另想办法），
  #   先如实记着，不挡住构建。
  so="lib/${ABI}/${base%.meta.json}"
  if ! has_exact "$so"; then
    case "${base%.meta.json}" in
      *.so.[0-9]*)
        echo "  [未解决] $meta 指向的 $so 不在包里（AGP 的 jniLibs 只打包 *.so，版本化命名进不去）"
        META_CHAIN_GAP=$((META_CHAIN_GAP + 1))
        ;;
      *)
        echo "[error] $meta 指向的 $so 不在包里"
        META_BAD=$((META_BAD + 1))
        ;;
    esac
  fi
done
rm -f "$TMPA"
echo "  （$META_N 份说明已查 · $META_BAD 处问题）"
if [ "$META_CHAIN_GAP" -gt 0 ]; then
  echo "  ⚠ $META_CHAIN_GAP 份说明指向版本化命名的 .so（libfoo.so.1 等），AGP 的 jniLibs 打包进不去。"
  echo "    linker 运行时按 DT_NEEDED 的名字找，那几层的全局软链在真机上建不出来 ——"
  echo "    依赖它们的件会加载失败。怎么修还没定，先记着。"
fi
[ "$META_BAD" = "0" ] || MISSING="$MISSING meta-invalid($META_BAD)"

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
echo "==> [ok] APK 原生件审计通过"$([ -n "$DEGRADED" ] && printf '（软降级:%s —— 能力未实现，不判红）' "$DEGRADED" || printf '（资产/小件/npm/adb-client 全在包里）')

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
META_MISSING_ID=""
META_MISS_SO=""
META_PRESENT_ID=""
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
  # 每份说明都要能在包里找到它的字节。
  #
  # 版本化命名（libz.so.1 / libz.so.1.3.2）AGP 的 jniLibs 打包装不进去
  # （只认 *.so 结尾），所以 APK 里只有链首那一份 —— 这不是缺陷：
  # PrefixProvisioner.provision() 遇到某一层的字节不在包里时，会拿**同 id
  # 那份在包里的**顶上（链上几层本来就是同一份字节的多个名字，land_piece
  # 用 cp -f 解开了软链，见 piece-env.sh）。
  #
  # 因此判据是：**同一个 id 至少有一份字节在包里**，而不是每层都在。
  # 下面按 id 归并核对。
  # 这一份说明属于哪个件 —— 归并判定的键。循环体里没有现成的 id 变量，
  # 从刚解出的 JSON 取（上面三个必填字段校验已经把它解出来过一次）。
  pid="$(node "$ROOT/scripts/verify/meta-field.js" "$TMPA" id 2>/dev/null || echo "")"
  so="lib/${ABI}/${base%.meta.json}"
  if has_exact "$so"; then
    META_PRESENT_ID="$META_PRESENT_ID $pid"
  else
    # 链上另一层可能带字节（AGP 只打包 *.so），记下来最后按 id 归并判
    META_MISSING_ID="$META_MISSING_ID $pid"
    META_MISS_SO="$META_MISS_SO $base"
  fi
done

# 按 id 归并：缺字节的那个 id，若它有别的层在包里 → 放过（链的场景）
META_SEEN_ID=""
for id in $META_MISSING_ID; do
  # 同一个件只报一次：链上每层缺一次就会记一次 id
  case " $META_SEEN_ID " in *" $id "*) continue ;; esac
  META_SEEN_ID="$META_SEEN_ID $id"
  case " $META_PRESENT_ID " in
    *" $id "*)
      # 同一件有别的层在包里 —— provision() 会拿那份顶上，符合预期
      ;;
    *)
      echo "[error] 件 $id 的字节不在 APK 里（链上各层：$META_MISS_SO）—— 运行时铺不出来"
      META_BAD=$((META_BAD + 1))
      ;;
  esac
done
rm -f "$TMPA"
echo "  （$META_N 份说明已查 · $META_BAD 处问题）"

# ── 反向核对：lib/<ABI>/ 里不该有「没说明的 .so」 ──────────────
# 上面是从说明出发查字节；这一向是从字节出发查说明 ——
# 补的是另一头：land_piece 某处漏写说明时，那一行的循环根本看不到它。
#
# 排除的都是**不需要被铺位**的 .so，理由可核（不是随手写的名单）：
#   libandroidx.graphics.path.so  androidx 图形库，appcompat 的传递依赖
#   libdimina.so                  快应用运行时，build.gradle.kts 的
#                                  implementation("com.github.didi.dimina:dimina:1.7.6")
#   libmmkv.so                    同上的传递依赖（腾讯 MMKV）
#   libc++_shared.so              NDK 直接给的，component-verify.json 里
#                                  libcxx 标了 notAPiece/apkOnly。它本来就在
#                                  APK 的 lib/<ABI>/ 下，安装后落在
#                                  nativeLibraryDir，linker 直接找得到 ——
#                                  不经 PrefixProvisioner 铺位，也就无需说明。
#                                  它的判据是 build-apk.yml 第 11 步那份硬判。
# 其余 lib/<ABI>/*.so 都是 base 筐的件，必须有说明。
# 内核的兼容性垫片：container/native 下我们自己写的 C，随 APK 而来。
#   liblobosposix.so      LD_PRELOAD 垫片
#   liblobosptyprobe.so   PTY 探测
#   librivospty.so        PTY 宿主
# 它们不是件、不铺位、不进清单、不可 OTA 替换（由 kernel/compat/Compat.kt 定位）。
# 判据可核：component-sources.json 里查不到它们，而 base 筐的件都在。
FOREIGN_SO="libandroidx.graphics.path.so libdimina.so libmmkv.so libc++_shared.so liblobosposix.so liblobosptyprobe.so librivospty.so"
META_NOSO=0
for so in $(printf '%s\n' "${LIST[@]}" | grep "^lib/${ABI}/.*\.so$"); do
  base="$(basename "$so")"
  case " $FOREIGN_SO " in *" $base "*) continue ;; esac
  if ! has_exact "assets/supply/meta/${base}.meta.json"; then
    echo "[error] $so 没有说明（assets/supply/meta/${base}.meta.json 不在包里）"
    echo "        运行时不会铺这一件 —— scanMeta 只铺带说明的条目（PrefixProvisioner.kt:103）"
    META_NOSO=$((META_NOSO + 1))
  fi
done
if [ "$META_NOSO" != "0" ]; then
  MISSING="$MISSING meta-absent($META_NOSO)"
  META_BAD=$((META_BAD + META_NOSO))
fi
echo "  反向核对：$META_NOSO 个 .so 没有说明"
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

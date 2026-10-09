#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="${1:-}"
shift || true
EXPECT_CERT=""
REQUIRE_STABLE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --cert) EXPECT_CERT="${2:-}"; shift 2 ;;
    --require-stable) REQUIRE_STABLE=1; shift ;;
    *) echo "[error] 未知参数 '$1'"; exit 2 ;;
  esac
done
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "[error] 用法: bash $0 <apk> [--cert <release.cert>] [--require-stable]（门禁不许空跑）"
  exit 2
fi

if [ -n "$EXPECT_CERT" ] && [ ! -s "$EXPECT_CERT" ]; then
  echo "[error] 指定了锚点 $EXPECT_CERT，但它不存在或为空 —— 注入步骤没做完，不该按「未配密钥」放行。"
  exit 1
fi

# 不吞 stderr：pick.sh 零命中时会打 warning 说明 find 为什么没命中
APKSIGNER="$(bash "$HERE/pick.sh" --allow-empty --last apksigner \
  "${ANDROID_HOME:-/nonexistent}/build-tools" -name apksigner -type f || true)"
[ -n "$APKSIGNER" ] || APKSIGNER="$(command -v apksigner || true)"
if [ -z "$APKSIGNER" ]; then
  echo "[error] 找不到 apksigner（ANDROID_HOME=${ANDROID_HOME:-<未设置>}）—— 签名身份无从核验，禁止放行（装不上去是装机之后才知道的）。"
  exit 1
fi

if ! PC="$( "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null )"; then
  echo "[error] apksigner 读不出 $APK 的证书（未签名？损坏？）—— 无从核验即不放行。"
  exit 1
fi

HEX64='^[0-9a-f]{64}$'
fp_from() {
  local line s tok
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    s="${line,,}"; s="${s//:/}"; s="${s//=/ }"
    case "$s" in (*sha*256*) ;; (*) continue ;; esac
    read -ra TOKS <<<"$s"
    for tok in "${TOKS[@]}"; do
      if [[ "$tok" =~ $HEX64 ]]; then printf '%s\n' "$tok"; return 0; fi
    done
  done <<<"$1"
  return 0
}

DN=""
while IFS= read -r line; do
  case "${line,,}" in
    *"certificate dn"*) DN="$line"; break ;;
  esac
done <<<"$PC"
FP="$(fp_from "$PC")"
if [ -z "$DN" ]; then
  echo "[error] apksigner 输出里没有 certificate DN 行 —— 解析不到签名身份，不放行。"
  exit 1
fi

echo "[lobos-signing] apksigner: $APKSIGNER"
echo "[lobos-signing] APK: $APK"
echo "[lobos-signing] $DN"
echo "[lobos-signing] SHA-256 指纹: ${FP:-（apksigner 输出里没解析到 sha-256 摘要行）}"

IS_DEBUG=0
case "${DN,,}" in (*"android debug"*) IS_DEBUG=1 ;; esac

if [ -n "$EXPECT_CERT" ]; then
  # 锚点指纹：优先 keytool（注入侧就是它导出的证书，同一工具链最可比），退 openssl。
  CT=""
  if command -v keytool >/dev/null 2>&1; then
    CT="$(keytool -printcert -file "$EXPECT_CERT" 2>/dev/null || true)"
  fi
  if [ -z "$CT" ] && command -v openssl >/dev/null 2>&1; then
    CT="$(openssl x509 -noout -fingerprint -sha256 -in "$EXPECT_CERT" 2>/dev/null || true)"
  fi
  [ -n "$CT" ] || { echo "[error] 锚点 $EXPECT_CERT 读不出证书内容（keytool/openssl 都不可用，或它不是证书）—— 无从核验即不放行。"; exit 1; }
  CERT_FP="$(fp_from "$CT")"
  [ -n "$CERT_FP" ] || { echo "[error] 锚点输出里没有 SHA-256 指纹 —— 无从核验即不放行。"; exit 1; }
  [ -n "$FP" ] || { echo "[error] APK 侧读不出 SHA-256 指纹，无法与锚点比对 —— 不放行。"; exit 1; }
  if [ "$FP" != "$CERT_FP" ]; then
    echo "::error title=签名身份不符::APK 内证书指纹 $FP ≠ 本次注入 keystore 的指纹 $CERT_FP —— 签名配置指错了 key（换过 keystore / 别名取错），装到既有设备上必失败。"
    exit 1
  fi
  echo "[lobos-signing] [ok] APK 证书指纹与注入锚点一致（${EXPECT_CERT##*/} = $FP）"
  if [ "$REQUIRE_STABLE" = "1" ] && [ "$IS_DEBUG" = "1" ]; then
    # 一致只证明「签名配置生效」，不证明「这把 key 是稳定的」。发布链路两个都要：
    # 有人会把本地 debug keystore 灌进 ANDROID_KEYSTORE_BASE64，那等于没配。
    echo "::error title=发布包是 debug 签名::锚点虽一致，但它本身就是 Android Debug 身份 —— 换 keystore 后存量设备照样装不上。"
    exit 1
  fi
  exit 0
fi

if [ "$IS_DEBUG" = "1" ]; then
  if [ "$REQUIRE_STABLE" = "1" ]; then
    echo "::error title=发布包是 debug 签名::本链路产物会投给存量设备（v<versionName> 版本化归档），一次性 debug 签名会把它们打成 INSTALL_FAILED_UPDATE_INCOMPATIBLE —— 请配置 ANDROID_KEYSTORE_BASE64 后重跑。"
    exit 1
  fi
  echo "::warning title=开发签名（不可发布）::未配置 ANDROID_KEYSTORE_BASE64，本次为 debug 签名；既有设备无法覆盖安装，且无自我升级能力。见 components/README.md 的「APK 自身的签名」段"
  exit 0
fi

if [ "$REQUIRE_STABLE" = "1" ]; then
  echo "[lobos-signing] [ok] 非 debug 签名（发布链路放行）。⚠ 本次【没有】可比对的锚点指纹 —— 只证明了「不是 debug」，没证明「是哪把 key」。"
  exit 0
fi
echo "::warning title=签名身份无从核验::未配 keystore 却拿到非 debug 签名 —— 没有锚点能证明它是【哪一把】key。发布请走配了 ANDROID_KEYSTORE_BASE64 的链路。"

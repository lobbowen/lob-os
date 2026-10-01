#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALIAS="${1:-lobos}"
DAYS="${2:-10000}"
KS="$ROOT/keys/release.keystore"
PROPS="$ROOT/keys/keystore.properties"

mkdir -p "$ROOT/keys"

if [ -f "$KS" ]; then
  echo "[keygen-apk] [error] 已存在: $KS" >&2
  echo "[keygen-apk]         若确实要重建，先手工删掉它。" >&2
  echo "[keygen-apk]         ⚠ 覆盖一个已用于发布的 keystore，等于放弃" >&2
  echo "[keygen-apk]           给已装设备升级的能力（Android 无回退机制）。" >&2
  exit 1
fi

gen_pw() { head -c 32 /dev/urandom | base64 | tr -d '\n=+/' | cut -c1-40; }

STOREPASS="$(gen_pw)"
KEYPASS="$STOREPASS"

echo "[keygen-apk] 正在生成 keystore…"
if command -v keytool >/dev/null 2>&1; then
  keytool -genkeypair \
    -v \
    -keystore "$KS" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity "$DAYS" \
    -storetype PKCS12 \
    -storepass "$STOREPASS" \
    -keypass "$KEYPASS" \
    -dname "CN=Lob OS, OU=Container, O=LobOS, L=, ST=, C=CN" \
    >/dev/null 2>&1
elif command -v openssl >/dev/null 2>&1; then
  TMPD="$(mktemp -d)"
  trap 'rm -rf "$TMPD"' EXIT
  openssl req -x509 -newkey rsa:4096 -keyout "$TMPD/key.pem" -out "$TMPD/cert.pem" \
    -days "$DAYS" -nodes -subj "/CN=Lob OS/OU=Container/O=LobOS/C=CN" >/dev/null 2>&1
  openssl pkcs12 -export -in "$TMPD/cert.pem" -inkey "$TMPD/key.pem" \
    -name "$ALIAS" -out "$KS" -passout "pass:$STOREPASS" >/dev/null 2>&1
  echo "[keygen-apk] （openssl 退路生成 PKCS12；如需 keytool 版请装 JRE 后重跑——但一旦发布，keystore 不可更换）"
else
  echo "[keygen-apk] [error] keytool 与 openssl 都不可用，无法生成 keystore" >&2
  exit 1
fi

chmod 600 "$KS"

cat > "$PROPS" <<EOF
# APK 签名密码（由 scripts/keygen-android-keystore.sh 生成）
# 与 keys/ota-private.pem 一样属于机密，绝不入库（keys/ 整体 gitignored）。
# CI 请用 secret，不要提交这个文件。
LOBOS_KEYSTORE_PASSWORD=$STOREPASS
LOBOS_KEY_ALIAS=$ALIAS
LOBOS_KEY_PASSWORD=$KEYPASS
EOF
chmod 600 "$PROPS"

echo "[keygen-apk] 完成"
echo "  keystore : $KS"
echo "  别名     : $ALIAS"
echo "  有效期   : $DAYS 天"
echo "  属性文件 : $PROPS"
echo
echo "  指纹（把它记下来，用于核对 CI 产物是否为同一签名）："
if command -v keytool >/dev/null 2>&1; then
  keytool -list -v -keystore "$KS" -storepass "$STOREPASS" 2>/dev/null \
    | grep -E "SHA1:|SHA256:" | sed 's/^/    /'
else
  openssl pkcs12 -in "$KS" -passin "pass:$STOREPASS" -nokeys -clcerts 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 | sed 's/^/    /'
fi
echo
echo "  用法："
echo "    source <(sed 's/^/export /' $PROPS) && ./gradlew assembleRelease"

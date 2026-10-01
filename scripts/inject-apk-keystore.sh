#!/usr/bin/env bash
set -euo pipefail

DEST="${1:-keys}"
KS="$DEST/release.keystore"
CERT="$DEST/release.cert"

if [ -z "${KS_B64:-}" ]; then
  echo "[lobos-signing] 未配置 ANDROID_KEYSTORE_BASE64 —— 本次产物将是 AGP 现场生成的一次性 debug 签名。"
  echo "[lobos-signing] 后果：指纹每次都不同 ⇒ 新包装到已装设备上会 INSTALL_FAILED_UPDATE_INCOMPATIBLE。"
  echo "[lobos-signing] 发布轮（推 os-release-* tag 的那一轮）据此判红；构建校验轮放行。"
  exit 10
fi
[ -n "${KS_PASS:-}" ] \
  || { echo "[error] ANDROID_KEYSTORE_BASE64 已配置但 ANDROID_KEYSTORE_PASSWORD 为空 —— 配坏了，不是没配。"; exit 2; }

mkdir -p "$DEST"
umask 077
if ! printf '%s' "$KS_B64" | base64 -d > "$KS" 2>/dev/null; then
  rm -f "$KS"
  echo "[error] ANDROID_KEYSTORE_BASE64 解码失败（内容被截断或不是 base64）—— 不是「没配密钥」，是配坏了。"
  exit 2
fi
[ -s "$KS" ] || { echo "[error] 解码后是 0 字节 —— 同上，配坏了。"; exit 2; }

command -v keytool >/dev/null 2>&1 \
  || { echo "[error] keytool 不可用 —— 无法核验注入结果，禁止继续。"; exit 2; }

ALIAS="${KS_ALIAS:-lobos}"
KEYPASS="${KS_KEYPASS:-$KS_PASS}"
export KS_PASS KS_KEYPASS="$KEYPASS"
if ! keytool -list -keystore "$KS" -storepass:env KS_PASS >/dev/null 2>&1; then
  echo "[error] keystore 读不出条目（ANDROID_KEYSTORE_PASSWORD 不对或文件损坏）。"
  exit 2
fi
if ! keytool -exportcert -rfc -keystore "$KS" -storepass:env KS_PASS \
         -alias "$ALIAS" -keypass:env KS_KEYPASS -file "$CERT" >/dev/null 2>&1; then
  echo "[error] 别名 '$ALIAS' 的证书导不出来（ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD 不匹配）。"
  exit 2
fi
[ -s "$CERT" ] || { echo "[error] 导出的 $CERT 是空的 —— 锚点不可用，禁止继续。"; exit 2; }
PEM_TXT="$(<"$CERT")"
case "$PEM_TXT" in (*"-----BEGIN CERTIFICATE-----"*) ;; (*) echo "[error] 导出的 $CERT 不是 PEM 证书 —— 锚点不可用，禁止继续。"; exit 2 ;; esac

while IFS= read -r ln; do
  case "${ln,,}" in (*"ingerprint"*) echo "[lobos-signing] keystore 证书 $ln" ;; esac
done <<<"$(keytool -printcert -file "$CERT" 2>/dev/null || true)"

# gradle 读这三个变量决定 signingConfig（container/app/build.gradle.kts:93-103，
# 名字要与它逐字对齐）；第四个 LOBOS_APK_CERT_FILE 是给下游签名身份门禁取锚点用的。
# 一律走 GITHUB_ENV，不把口令写进步骤命令行。
if [ -n "${GITHUB_ENV:-}" ]; then
  {
    echo "LOBOS_KEYSTORE_PASSWORD=$KS_PASS"
    echo "LOBOS_KEY_ALIAS=$ALIAS"
    echo "LOBOS_KEY_PASSWORD=$KEYPASS"
    echo "LOBOS_APK_CERT_FILE=$CERT"
  } >> "$GITHUB_ENV"
fi
echo "[lobos-signing] [ok] keystore 已注入并核验：$KS（别名 $ALIAS，锚点 $CERT）"

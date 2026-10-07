#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/keys"
PRIV="$ROOT/keys/ota-private.pem"
PUB="$ROOT/keys/ota-public.pem"
ANCHOR="$ROOT/container/app/src/main/assets/supply/component-public.pem"

if [ -f "$PRIV" ]; then
  echo "[keygen] 私钥已存在: $PRIV （跳过生成，保留现有密钥）"
else
  openssl genpkey -algorithm ed25519 -out "$PRIV"
  chmod 600 "$PRIV"
  echo "[keygen] 已生成私钥: $PRIV"
fi

openssl pkey -in "$PRIV" -pubout -out "$PUB"
mkdir -p "$(dirname "$ANCHOR")"
cp "$PUB" "$ANCHOR"
echo "[keygen] 已生成公钥: $PUB"
echo "[keygen] 公钥锚点已写入（焊进 APK）: $ANCHOR"
echo "[keygen] 注意：私钥仅用于签名程序包，切勿提交；CI 用 secret 注入。"

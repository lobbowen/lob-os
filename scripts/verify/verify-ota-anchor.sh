#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANCHOR="$SCRIPT_DIR/../../container/app/src/main/assets/supply/component-public.pem"
PRIVATE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --anchor)  [ $# -ge 2 ] || { echo "[error] --anchor 缺值"; exit 2; }; ANCHOR="$2"; shift 2 ;;
    --private) [ $# -ge 2 ] || { echo "[error] --private 缺值"; exit 2; }; PRIVATE="$2"; shift 2 ;;
    -*)        echo "[error] 不认识的选项：$1"; echo "[error] 用法: bash $0 [--anchor F] [--private F]"; exit 2 ;;
    *)         echo "[error] 不接受位置参数：$1（本宿主只查锚点，不查包）"; exit 2 ;;
  esac
done

OPENSSL="${OPENSSL:-openssl}"
command -v "$OPENSSL" >/dev/null 2>&1 || { echo "[error] 找不到 openssl —— 无法校验锚点就不得放行。"; exit 2; }

if [ ! -s "$ANCHOR" ]; then
  echo "[error] OTA 公钥锚点缺失或为空：$ANCHOR"
  echo "        设备端 program-verify.js 拿它验签；没有它，任何程序包都装不上。"
  echo "        请先 commit 该文件，或用 secrets.OTA_PUBLIC_KEY 覆盖写入。"
  exit 1
fi

norm_pub() {
  if [ "$2" = pubin ]; then
    "$OPENSSL" pkey -pubin -in "$1" -pubout 2>/dev/null
  else
    "$OPENSSL" pkey -in "$1" -pubout 2>/dev/null
  fi
}
ANCHOR_PEM="$(norm_pub "$ANCHOR" pubin || true)"
if [ -z "$ANCHOR_PEM" ]; then
  echo "[error] 锚点不是一把可解析的公钥：$ANCHOR"
  echo "        （此前这一步只打 ::warning:: 就继续出包，等于把坏锚点焊进 APK。）"
  "$OPENSSL" pkey -pubin -in "$ANCHOR" -noout 2>&1 | sed 's/^/         /' || true
  exit 1
fi
algo_head="$("$OPENSSL" pkey -pubin -in "$ANCHOR" -text -noout 2>/dev/null | { grep -m1 -i 'Public-Key' || true; })"
case "$algo_head" in
  *ED25519*|*Ed25519*|*ed25519*) : ;;
  *)
    echo "[error] 锚点算法不是 Ed25519：${algo_head:-读不出算法名}"
    echo "        设备端验签用 ed25519；焊一把 RSA 进去，所有程序包都 signature-invalid。"
    echo "        「openssl pkey -pubin 退码 0」只证明它是**某把**公钥，不证明这把能用。"
    exit 1
    ;;
esac
anchor_fp="$("$OPENSSL" pkey -pubin -in "$ANCHOR" -outform DER 2>/dev/null | "$OPENSSL" dgst -sha256 | awk '{print $NF}' || true)"
[ -n "$anchor_fp" ] || { echo "[error] 锚点指纹取不出来（openssl 版本行为变了？）—— 无从核验即不放行。"; exit 2; }
echo "[ok]   锚点是一把有效的 Ed25519 公钥：$ANCHOR"
echo "       指纹 sha256(DER)=$anchor_fp"

if [ -n "$PRIVATE" ]; then
  if [ ! -s "$PRIVATE" ]; then
    echo "[error] 要求配对校验，但私钥文件缺失或为空：$PRIVATE"
    echo "        取不到私钥就一条判据都跑不了，拒绝签名。"
    exit 2
  fi
  derived="$(norm_pub "$PRIVATE" pkey || true)"
  if [ -z "$derived" ]; then
    echo "[error] 私钥读不出公钥：$PRIVATE（不是 PKCS8 ed25519 私钥？权限/编码问题？）"
    exit 2
  fi
  priv_algo="$(printf '%s\n' "$derived" >/dev/null; "$OPENSSL" pkey -in "$PRIVATE" -text -noout 2>&1 | { grep -m1 -iE 'Private-Key|ED25519' || true; })"
  case "$priv_algo" in
    *ED25519*|*Ed25519*|*ed25519*) : ;;
    *)
      echo "[error] 私钥算法不是 Ed25519：${priv_algo:-读不出算法名}"
      echo "        container/engine/src/sign.js 按 ed25519 签名；算法不符签出的东西设备验不过。"
      exit 1
      ;;
  esac
  priv_fp="$(printf '%s\n' "$derived" | "$OPENSSL" pkey -pubin -pubout -outform DER 2>/dev/null | "$OPENSSL" dgst -sha256 | awk '{print $NF}' || true)"
  [ -n "$priv_fp" ] || { echo "[error] 私钥派生公钥的指纹取不出来 —— 无从核验即不放行。"; exit 2; }
  if [ "$derived" != "$ANCHOR_PEM" ]; then
    echo "[error] 私钥与 APK 锚点**不配对**：私钥 $PRIVATE 派生的公钥指纹 $priv_fp ≠ 锚点指纹 $anchor_fp"
    echo "        后果：这一步签出的程序包，所有设备都会判 signature-invalid —— OTA 静默死亡，"
    echo "        而 CI 全绿。要么把新公钥重焊进 $ANCHOR 并重发 APK，要么换回配对的私钥 secret。"
    echo "        （比对在两边都规范化为 SPKI PEM 之后进行，所以与空白/行尾/是否 secret 写入无关。）"
    exit 1
  fi
  echo "[ok]   配对成立：私钥派生公钥 == 锚点（$anchor_fp）"
fi

echo "==> [ok] OTA 锚点判据全部通过"

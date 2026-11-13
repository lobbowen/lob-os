#!/usr/bin/env bash
set -euo pipefail

die() { echo "::error title=源码钉值不合::$*" >&2; exit 2; }

HERE=$(dirname "$0")
ROOT_DIR=$(cd "$HERE/../.." && pwd)
TABLE="$ROOT_DIR/scripts/component-sources.json"

OUT=""
WANT=""
VER=""
URLS=()
VERSION_FILE=""

if [ "${1:-}" = "--list-keys" ]; then

  node -e '
    const t=require(process.argv[1]);
    const pat=new RegExp(process.argv[2]||".*");
    process.stdout.write(Object.keys(t.sources||{}).filter(k=>pat.test(k)).join("\n"));
  ' "$TABLE" "${2:-.*}"
  exit 0
fi

if [ "${1:-}" = "--pin" ]; then
  KEY="${2:-}"
  OUT="${3:-}"
  [ -n "$KEY" ] && [ -n "$OUT" ] || die "用法: $0 --pin <键> <落点>（键见 $TABLE）"
  shift 3
  while [ $# -gt 0 ]; do
    case "$1" in
      --version-file) VERSION_FILE="${2:-}"; [ -n "$VERSION_FILE" ] || die "--version-file 后面没给落点"; shift 2 ;;
      *) die "--pin 这一档只认 --version-file，读到的是：$1" ;;
    esac
  done
  if ! META="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const s = (tab.sources || {})[process.argv[2]];
    if (!s) { console.error("键 " + process.argv[2] + " 不在钉值表里（现有: " + Object.keys(tab.sources || {}).join(", ") + "）"); process.exit(1); }
    if (!/^[0-9a-f]{64}$/.test(String(s.sha256))) { console.error("键 " + process.argv[2] + " 的 sha256 不是 64 位小写十六进制: " + String(s.sha256)); process.exit(1); }
    if (!s.version || !String(s.version).trim()) { console.error("键 " + process.argv[2] + " 没有 version 格"); process.exit(1); }
    if (!Array.isArray(s.urls) || s.urls.length === 0) { console.error("键 " + process.argv[2] + " 的 urls 是空的"); process.exit(1); }
    //  缺陷名（C-102）**那 sha256 那一格**逐字**记**的是那**内容**那一个数、那**它**逐字**比**的是那**下载**下来**那一个** tar。
    // 那**成因**：那 tar 那**字节**逐字**每次**换（那**它**们**逐字是**那**两**件**事**：「那**tar**是**什么**」与「那**内容**是**什么**」）。
    // 那**后果**逐字那 `--pin` 那一族逐字**永远红**（那**它**们**逐字是**那**两**件**事**：「那**红**的是**什么**」与「那**为什么**」））。
    // 那**处置**：那**内容**那一个数逐字**另**一**格**记**（那 contentSha256 **那一格**）、
    // 那**它**逐字**两**个**读法**都**留**着**（那**它们**逐字是**那**两**件**事**：「那**读**的**是**什么**」与「那**比**的**是**什么**」）。
    const cs = String(s.contentSha256 || "").trim();
    // 那**内容**那一个数逐字**走**那**自己**那一行（那**它**逐字**不**跟**那 urls **混**在**一**起**）。
    process.stdout.write(String(s.sha256) + "\n" + String(s.version) + "\n" + cs + "\n" + s.urls.join("\n") + "\n");
  ' "$TABLE" "$KEY")"; then
    die "钉值表这一格读不通：$KEY"
  fi
  { IFS= read -r WANT; IFS= read -r VER; IFS= read -r CONTENT_WANT || true; mapfile -t URLS; } <<< "$META"
  [ -n "$CONTENT_WANT" ] || CONTENT_WANT=""
elif [ "${1:-}" = "--time-base" ] || [ "${1:-}" = "--source-date-epoch" ]; then

  if ! TB="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.buildTimeEpoch;
    if (!Number.isInteger(v) || v <= 0) { console.error("buildTimeEpoch 必须是正整数秒（现在: " + JSON.stringify(v) + "）"); process.exit(1); }
    if (v * 1000 >= Date.now()) { console.error("buildTimeEpoch=" + v + " 不早于现在，钉不住墙钟"); process.exit(1); }
    process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 buildTimeEpoch 这一格读不通"
  fi
  echo "$TB"
  exit 0
elif [ "${1:-}" = "--ndk" ]; then

  if ! ND="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.ndkVersion;
    if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(String(v))) { console.error("ndkVersion 不是 x.y.z 形态（读到 " + JSON.stringify(v) + "）"); process.exit(1); }
    process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 ndkVersion 这一格读不通"
  fi
  echo "$ND"
  exit 0
elif [ "${1:-}" = "--llvm" ]; then

  if ! LV="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const v = tab.llvmVersion;
    if (v === undefined || v === null || String(v).trim() === "") process.stdout.write("");
    else if (!/^[0-9]+(\.[0-9]+){0,2}$/.test(String(v))) {
      console.error("llvmVersion 形态非法（读到 " + JSON.stringify(v) + "）：应为 20 / 20.1 / 20.1.8 之一");
      process.exit(1);
    } else process.stdout.write(String(v));
  ' "$TABLE")"; then
    die "钉值表的 llvmVersion 这一格读不通"
  fi
  echo "$LV"
  exit 0
elif [ "${1:-}" = "--src-version" ]; then

  KEY="${2:?用法: $0 --src-version <键>（键见 $TABLE）}"
  if ! SV="$(node -e '
    const path = require("node:path");
    let tab;
    try { tab = require(path.resolve(process.argv[1])); } catch (e) { console.error("钉值表读不出: " + e.message); process.exit(1); }
    const s = (tab.sources || {})[process.argv[2]];
    if (!s || typeof s.version !== "string" || !s.version.trim()) {
      console.error("sources." + process.argv[2] + " 没有 version 格（键是否拼错？现有: " + Object.keys(tab.sources || {}).join(", ") + "）");
      process.exit(1);
    }
    process.stdout.write(s.version);
  ' "$TABLE" "$KEY")"; then
    die "钉值表的 sources.$KEY 读不通"
  fi
  echo "$SV"
  exit 0
elif [ $# -ge 3 ]; then
  OUT="${1:-}"
  WANT="${2:-}"
  shift 2
  URLS=("$@")
else
  die "用法: $0 --pin <键> <落点>  或  $0 --time-base  或  $0 --ndk  或  $0 --llvm  或  $0 <落点> <期望 sha256> <url> [url...]（参数少一个都不算数）"
fi

[ -n "$OUT" ] && [ -n "$WANT" ] && [ "${#URLS[@]}" -gt 0 ] \
  || die "落点/钉值/来源三样少一样（--pin 那档由表供给后两样）"
[[ "$WANT" =~ ^[0-9a-f]{64}$ ]] || die "钉值不是 64 位小写十六进制 sha256：$WANT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

  f="$TMP/download"
if [ "${#URLS[@]}" -gt 1 ]; then

  _all_txt=1
  for u in "${URLS[@]}"; do
    case "$u" in *"?format=TEXT") : ;; *) _all_txt=0 ;; esac
  done
  if [ "$_all_txt" = 1 ]; then
    _stage="$TMP/kernel-files"
    rm -rf "$_stage" && mkdir -p "$_stage"
    _paths=""
    for u in "${URLS[@]}"; do
      _p="${u%%\?*}"
      _rel="${_p#*+/}"
      _rel="${_rel#*/}"
      _out="$_stage/$_rel"
      mkdir -p "$(dirname "$_out")"
      _raw="$TMP/kernel-files.raw"
      _ok=0
      for _try in 1 2 3 4 5; do
        if curl -fsSL --http1.1 --max-time 900 "$u" -o "$_raw" 2>/dev/null; then

          if [ -s "$_raw" ] && base64 -d < "$_raw" > "$_out" 2>/dev/null && [ -s "$_out" ]; then _ok=1; break; fi
        fi
        sleep 2
      done
      if [ "$_ok" != 1 ]; then
        echo "[fetch-pinned] 逐文件那一族取不到：$u"
        exit 1
      fi
      _paths="$_paths$_rel\n"
    done

    if command -v python3 >/dev/null 2>&1; then
      printf '%b' "$_paths" > "$TMP/kernel-files.paths"
      python3 scripts/toolchain/kernel-files-tar.py "$_stage" "$TMP/kernel-files.paths" > "$f"
    else
      echo "[fetch-pinned] 逐文件那一族要合成那确定性 tar、那 python3 逐字**没有**"
      exit 1
    fi
    got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [ "$got" != "$WANT" ]; then
      echo "[fetch-pinned] 逐文件那一族合成出来的不合钉值：实得 $got（期望 $WANT）"
      exit 1
    fi
    mkdir -p "$(dirname "$OUT")"
    mv "$f" "$OUT"
    if [ -n "$VERSION_FILE" ]; then
      mkdir -p "$(dirname "$VERSION_FILE")"
      printf '%s\n' "$VER" > "$VERSION_FILE"
      echo "[fetch-pinned] 件版本格 $VERSION_FILE = $VER（这一格由这次取数写，与钉值是同一个事实）"
    fi
    echo "[fetch-pinned] 校验通过（逐文件那一族合成）：$(basename "$OUT") 钉值表 version=${VER:-未名} sha256=$got"
    exit 0
  fi
fi
for url in "${URLS[@]}"; do

  case "$url" in
    https://*) : ;;
    *) die "来源不是 https（file:// 也不行 —— curl 禁用了 file 协议，配了必然失败）: $url" ;;
  esac

  if ! curl -fsSL --http1.1 --max-time 900 "$url" -o "$f"; then
    echo "[fetch-pinned] 下载失败，换下一条来源：$url"
    continue
  fi
  got="$(sha256sum "$f" | cut -d' ' -f1)"

  if [ -n "$CONTENT_WANT" ]; then
    _cdir="$(dirname "$f")"
    _cstage="$_cdir/content-$"
    rm -rf "$_cstage"; mkdir -p "$_cstage"
    if ! tar xzf "$f" -C "$_cstage" 2>/dev/null; then
      echo "[fetch-pinned] 来源 $url 解不开（内容钉值那一族要解包再比）: $url"
      rm -rf "$_cstage"
      continue
    fi

    cgot="$(cd "$_cstage" && find . -type f -print0 2>/dev/null | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | cut -d' ' -f1)"
    rm -rf "$_cstage"
    if [ "$cgot" != "$CONTENT_WANT" ]; then
      echo "[fetch-pinned] 来源 $url 的内容不合钉值：实得 $cgot（期望 $CONTENT_WANT）—— 这不是同一批内容，换下一条"
      continue
    fi
    echo "[fetch-pinned] 内容钉值校验通过：$url（内容 $cgot；那 tar 那字节逐字每次换、那**它**逐字**不**钉**它**）"
  elif [ "$got" != "$WANT" ]; then
    echo "[fetch-pinned] 来源 $url 不合钉值：实得 $got（期望 $WANT）—— 这不是同一批字节，换下一条"
    continue
  fi
  mkdir -p "$(dirname "$OUT")"
  mv "$f" "$OUT"
  if [ -n "$VERSION_FILE" ]; then
    mkdir -p "$(dirname "$VERSION_FILE")"
    printf '%s\n' "$VER" > "$VERSION_FILE"
    echo "[fetch-pinned] 件版本格 $VERSION_FILE = $VER（这一格由这次取数写，与钉值是同一个事实）"
  fi
  echo "[fetch-pinned] 校验通过：$(basename "$OUT") 钉值表 version=${VER:-未名} sha256=$got 来源=$url"
  exit 0
done

die "所有来源都不合钉值 $WANT（共 ${#URLS[@]} 条）—— 宁可不编译，也不许用没有身份的源码出件。"

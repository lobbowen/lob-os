#!/usr/bin/env bash
set -euo pipefail

die() { echo "::error title=源码钉值不合::$*" >&2; exit 2; }

HERE=$(dirname "$0")
ROOT_DIR=$(cd "$HERE/.." && pwd)
TABLE="$ROOT_DIR/scripts/userland-sources.json"

OUT=""
WANT=""
VER=""
URLS=()
VERSION_FILE=""

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
    process.stdout.write(String(s.sha256) + "\n" + String(s.version) + "\n" + s.urls.join("\n") + "\n");
  ' "$TABLE" "$KEY")"; then
    die "钉值表这一格读不通：$KEY"
  fi
  { IFS= read -r WANT; IFS= read -r VER; mapfile -t URLS; } <<< "$META"
elif [ "${1:-}" = "--time-base" ]; then
  # 件里嵌的构建时间基准也住这张表，而表的读者必须只有本脚本一个（⑦ 那条判据）。
  # 校验形状与「必须早于现在」都在这里判：钉在未来等于没钉（墙钟还没走到，重建每次都取 time()）。
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
  # 交叉编译用的 NDK 版本也住这张表，表的读者仍然只有本脚本（⑦ 那条判据）。
  # 这里**只取不判**：NDK 不是下载来的源码，「实际用的那版等不等于钉值」由 build-userland 的
  # 「定位 NDK」步在 runner 上判（那里才有两侧读数可比）。
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
elif [ $# -ge 3 ]; then
  OUT="${1:-}"
  WANT="${2:-}"
  shift 2
  URLS=("$@")
else
  die "用法: $0 --pin <键> <落点>  或  $0 --time-base  或  $0 --ndk  或  $0 <落点> <期望 sha256> <url> [url...]（参数少一个都不算数）"
fi

[ -n "$OUT" ] && [ -n "$WANT" ] && [ "${#URLS[@]}" -gt 0 ] \
  || die "落点/钉值/来源三样少一样（--pin 那档由表供给后两样）"
[[ "$WANT" =~ ^[0-9a-f]{64}$ ]] || die "钉值不是 64 位小写十六进制 sha256：$WANT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for url in "${URLS[@]}"; do
  case "$url" in https://*|file://*) ;; *) die "来源既不是 https 也不是 file：$url" ;; esac
  f="$TMP/download"
  if ! curl -fsSL --max-time 900 "$url" -o "$f"; then
    echo "[fetch-pinned] 下载失败，换下一条来源：$url"
    continue
  fi
  got="$(sha256sum "$f" | cut -d' ' -f1)"
  if [ "$got" != "$WANT" ]; then
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

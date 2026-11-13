#!/usr/bin/env bash

set -uo pipefail

PROBE_DIR=""
if [ "${1:-}" = "--probe-dir" ]; then
  PROBE_DIR="$2"; ASSET="$3"; WANT_ENTRY="${4:-}"; EXTRA_FILES="${5:-}"
else
  TAG="${1:?需要 tag}"; ASSET="${2:?需要资产名}"; WANT_ENTRY="${3:-}"; EXTRA_FILES="${4:-}"
fi

FMT_SH="$(cd "$(dirname "$0")" && pwd)/fmt.sh"
[ -f "$FMT_SH" ] && . "$FMT_SH" || true

read_meta_entry() {
  case "$(fmt_of "$1")" in
    zip) unzip -p "$1" "component-meta.json" 2>/dev/null ;;
    *) tar -xzf "$1" -O ./component-meta.json 2>/dev/null ;;
  esac
}

verify_local() {
  local f="$1" listing vl entry want
  [ -s "$f" ] || return 1

  case "$(fmt_of "$f")" in
    gzip|tar.gz)  listing="$(tar -tzf "$f" 2>/dev/null)" ;;
    zip)          listing="$(unzip -l "$f" 2>/dev/null | awk 'NF>=4 && $1 ~ /^[0-9]+$/ {print $4}')" ;;
    *) listing="$(grep -ao 'component-meta\.json' "$f" 2>/dev/null)" ;;
  esac
  [ -n "$listing" ] || return 1

  printf '%s\n' "$listing" | grep -qE '(^|/)component-meta\.json$' || return 1

  meta_prov="$(read_meta_entry "$f" | node -e '
    let d = "";
    process.stdin.on("data", c => d += c).on("end", () => {
      try { const m = JSON.parse(d); process.stdout.write(String(m.entryProvenance || "")); } catch (e) {}
    });
  ' 2>/dev/null)"
  if [ -z "$meta_prov" ]; then

    [ -n "$WANT_ENTRY" ] && return 1
  fi
  if [ -n "$WANT_ENTRY" ]; then
    entry="$(read_meta_entry "$f" | node -e '
      let d = "";
      process.stdin.on("data", c => d += c).on("end", () => {
        try { process.stdout.write((JSON.parse(d).entry) || ""); } catch (e) {}
      });
    ' 2>/dev/null)"
    [ "$entry" = "$WANT_ENTRY" ] || return 1
  fi

  if [ "$(fmt_of "$f")" = "zip" ]; then
    vl="$(unzip -l "$f" 2>/dev/null | awk 'NF>=4 && $1 ~ /^[0-9]+$/ {print "- " $4}' | sed 's#^./##')"
  else
    vl="$(tar -tzvf "$f" 2>/dev/null | awk 'NF>=6 {t=$1; p=$6; sub(/^\.\//,"",p); print t" "p}')"
  fi

  if [ -n "$vl" ]; then
    for want in $WANT_ENTRY $EXTRA_FILES; do
      printf '%s\n' "$vl" | awk -v w="$want" '
        { t=$1; p=$2;
          if ((substr(t,1,1)=="-" || substr(t,1,1)=="l") && p==w) { print "hit"; exit } }
      ' | grep -q hit || return 1
    done
    return 0
  fi

  for want in $WANT_ENTRY $EXTRA_FILES; do
    printf '%s\n' "$listing" | sed 's#^\./##' | grep -qx "$want" || return 1
  done
  return 0
}

if [ -n "$PROBE_DIR" ]; then
  verify_local "$PROBE_DIR/$ASSET"
  exit $?
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

if ! gh release download "$TAG" --repo "${GITHUB_REPOSITORY:-lobbowen/lob-os}" \
     --pattern "$ASSET" --dir "$TMP" >/dev/null 2>&1; then
  echo "  [miss] Release $TAG 里没有 $ASSET"
  exit 1
fi

if ! verify_local "$TMP/$ASSET"; then
  echo "  [miss] $ASSET 在，但内容不对（缺 component-meta.json / entry 不符 / 缺那个路径）—— 需要重新固化"
  exit 1
fi

echo "  [ok] $ASSET 可复用"
exit 0

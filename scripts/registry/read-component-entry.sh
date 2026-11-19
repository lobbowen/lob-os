#!/usr/bin/env bash

set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

WHY_ONLY=0
if [ "${1:-}" = "--why" ]; then
  WHY_ONLY=1
  shift
fi
key="${1:?usage: read-component-entry.sh [--why] <件名>}"
DIST_DIR="${OUT:-$ROOT/dist}"
case "$DIST_DIR" in /*) ;; *) DIST_DIR="$ROOT/$DIST_DIR" ;; esac

why_pure_library() { printf 'pure-library\n'; }
why_entry_empty()  { printf 'entry-empty\n'; }
why_no_artifact()  { printf 'no-artifact\n'; }
why_found()        { printf 'entry-found\n'; }

why_say() {
  [ "$WHY_ONLY" -eq 1 ] || return 0
  printf '%s' "$1"
  exit 0
}

if [ -d "$DIST_DIR" ]; then
  e="$(node "$ROOT/scripts/recipes/gen-component-meta.js" --print-entry "$key" "$DIST_DIR" 2>/dev/null || true)"
  if [ -n "$e" ]; then why_say "$(why_found)"; printf '%s\n' "$e"; exit 0; fi

  if [ -d "$DIST_DIR/lib" ] || [ -d "$DIST_DIR/bin" ]; then
    why_say "$(why_pure_library)"
    printf '\n'; exit 0
  fi
fi

for z in "$DIST_DIR"/component-"$key"-*.zip; do
  [ -f "$z" ] || continue
  e="$(unzip -p "$z" component-meta.json 2>/dev/null | node -e '
    let d = "";
    process.stdin.on("data", c => d += c); process.stdin.on("end", () => {
      try { process.stdout.write(String((JSON.parse(d).entry) || "")); } catch (e) {}
    });
  ' 2>/dev/null)"
  if [ -n "$e" ]; then why_say "$(why_found)"; printf '%s\n' "$e"; exit 0; fi

  if unzip -p "$z" component-meta.json 2>/dev/null | grep -q '"entry"'; then
    why_say "$(why_entry_empty)"
    printf '\n'; exit 0
  fi
done

why_say "$(why_no_artifact)"
printf '\n'
exit 0

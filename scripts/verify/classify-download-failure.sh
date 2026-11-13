#!/usr/bin/env bash

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

classify_download_failure() {
  local evid="$1"
  case "$evid" in
    *"Egress is over the account limit"*|*"rate limit"*|*"secondary rate"*|*"API rate limit exceeded"*)
      echo "ratelimit" ;;
    *"Bad credentials"*|*"authentication"*|*"requires authentication"*|*"Bad credentials"*)
      echo "auth" ;;
    *"HTTP 404"*|*"Not Found"*|*"not found"*|*"BlobNotFound"*)
      echo "notfound" ;;
    *)

      echo "unknown" ;;
  esac
}

http_of() {
  local evid="$1" h
  h="$(printf '%s' "$evid" | sed -n 's/.*HTTP \([0-9][0-9][0-9]\).*/\1/p' | head -1)"
  [ -n "$h" ] || h="none"
  printf '%s\n' "$h"
}

classify_by_headers() {
  local hdr="$1"
  local remaining retry
  remaining="$(printf '%s' "$hdr" | tr -d '\r' | sed -n 's/^[Xx]-[Rr]atelimit-[Rr]emaining: *\([0-9][0-9]*\).*/\1/p' | head -1)"
  retry="$(printf '%s' "$hdr" | tr -d '\r' | sed -n 's/^[Rr]etry-[Aa]fter: *\([0-9][0-9]*\).*/\1/p' | head -1)"
  if [ "$remaining" = "0" ]; then echo "ratelimit"; return 0; fi
  if [ -n "$retry" ]; then echo "ratelimit"; return 0; fi
  if [ -n "$remaining" ]; then echo "ok"; return 0; fi
  echo "unknown"
  return 0
}

report() {
  local f="$1" evid kind http
  evid="$(tr -d '\r' < "$f" 2>/dev/null | head -5)"
  kind="$(classify_download_failure "$evid")"
  http="$(http_of "$evid")"
  printf '== %s ==\n' "$f"
  printf '  响应体逐字=%s\n' "${evid:-（空）}"
  printf '  HTTP 那一档=%s\n' "$http"
  printf '  那一档=%s\n' "$kind"
  printf '\n'
}

if [ "${1:-}" = "--headers" ]; then

  hdr="$(tr -d '\r' 2>/dev/null | head -40)"
  classify_by_headers "$hdr"
  exit 0
fi

if [ "${1:-}" = "--kind" ]; then

  evid="$(tr -d '\r' 2>/dev/null | head -5)"
  classify_download_failure "$evid"
  exit 0
fi

if [ "${1:-}" = "--selftest" ]; then
  W="$(mktemp -d)"
  trap 'rm -rf "$W"' EXIT
  printf 'error downloading component-node: HTTP 503: 503 Egress is over the account limit.\n' > "$W/503.txt"
  printf '{"message":"Bad credentials","documentation_url":"https://docs.github.com/rest"}\n' > "$W/401.txt"
  printf 'HTTP 404: Not Found (https://api.github.com/repos/x/actions/artifacts/1)\n' > "$W/404.txt"
  printf 'HTTP 403: Forbidden\n' > "$W/403.txt"
  printf '\n' > "$W/empty.txt"
  printf 'error downloading component-x: HTTP 500: Internal Server Error\n' > "$W/500.txt"

  RC=0
  check() {
    local f="$1" want="$2" got
    got="$(classify_download_failure "$(tr -d '\r' < "$f" | head -5)")"
    printf '  %-12s 期望=%-10s 实得=%-10s %s\n' "$(basename "$f")" "$want" "$got" \
      "$([ "$got" = "$want" ] && echo OK || echo MISMATCH)"
    [ "$got" = "$want" ] || RC=1
  }
  echo "== 四档判别 =="
  check "$W/503.txt" ratelimit
  check "$W/401.txt" auth
  check "$W/404.txt" notfound
  check "$W/403.txt" unknown
  check "$W/empty.txt" unknown
  check "$W/500.txt" unknown
  echo ""
  echo "== HTTP 那一档逐字从响应体取 =="
  for f in "$W/503.txt" "$W/404.txt" "$W/500.txt"; do
    printf '  %-12s HTTP=%s\n' "$(basename "$f")" "$(http_of "$(tr -d '\r' < "$f" | head -5)")"
  done
  echo ""
  echo "== 阳性对照：那一族判别逐字能区分（不是全落 unknown）=="
  for f in "$W"/*.txt; do printf '  %-12s -> %s\n' "$(basename "$f")" "$(classify_download_failure "$(tr -d '\r' < "$f" | head -5)")"; done
  echo ""
  echo "== 响应头那一档（判据逐字落在请求上）=="
  printf 'HTTP/1.1 403 Forbidden\r\nx-ratelimit-limit: 5000\r\nx-ratelimit-remaining: 0\r\nretry-after: 60\r\n' > "$W/h-rl.txt"
  printf 'HTTP/1.1 404 Not Found\r\nx-github-request-id: ABC:123\r\n' > "$W/h-404.txt"
  printf 'HTTP/1.1 200 OK\r\nx-ratelimit-limit: 5000\r\nx-ratelimit-remaining: 4307\r\n' > "$W/h-ok.txt"
  printf 'HTTP/1.1 500 Internal Server Error\r\n' > "$W/h-500.txt"
  for f in "$W"/h-*.txt; do
    printf '  %-12s -> %s\n' "$(basename "$f")" "$(classify_by_headers "$(tr -d '\r' < "$f" | head -40)")"
  done
  echo "  （ratelimit=配额用光或服务端让你等 / ok=配额还有 / unknown=那响应头没给可判的）"
  echo ""
  echo "自测退出码=$RC"
  exit $RC
fi

[ "$#" -ge 1 ] || { echo "用法: $0 <响应体文件> [更多文件…] | --selftest"; exit 2; }
for f in "$@"; do report "$f"; done

#!/usr/bin/env bash
# 族六 · 取件那一次下载被拒时的四档判别（取证 + 阳性对照）。
#
# 它**只判那响应体**（=> 而那四档逐字是：服务端限流 / 鉴权不对 / 资源不在 / 成因未知），
# 而**不猜**（=> 而按 C-05「缺字段就报缺、不许猜填」、
# => 而那一档逐字是「成因未知」是正当取值、=> 而不是猜出来的成因）。
#
# 用法：
#   bash scripts/verify/classify-download-failure.sh <响应体文件> [更多文件…]
#   bash scripts/verify/classify-download-failure.sh --selftest
#
# 而它判别的那一档逐字与 scripts/toolchain/fetch-component-artifacts.sh
# 那一族逐字**同一套口径**（=> 而按 C-104「判别源必须唯一」，
# => 而那脚本逐字**调**这一件、=> 而不另写一套）。
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$HERE/../.." && pwd)"

# 那一档判别（**唯一**判别源）：响应体逐字 -> 那一档。
#   => 而它逐字**只**按那**响应体里的原话**判、=> 而**不**按 HTTP 码猜成因
#     （=> 而那 HTTP 503 可能是限流、=> 而也可能是别的、=> 而那逐字得看原话）。
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
      #  => 而那一档逐字是「成因未知」（=> 而**不是**猜一个成因）。
      echo "unknown" ;;
  esac
}

# 那 HTTP 那一档逐字**从**那响应体里**取**（=> 而不猜）。
http_of() {
  local evid="$1" h
  h="$(printf '%s' "$evid" | sed -n 's/.*HTTP \([0-9][0-9][0-9]\).*/\1/p' | head -1)"
  [ -n "$h" ] || h="none"
  printf '%s\n' "$h"
}

# 那**响应头**那一档（=> 而「资源不在」与「限流」怎么区分、=> 而那要靠响应头）。
#   => 而那判据逐字落在**请求**上（=> 而按 C-993 的第二件：
#     => 而「检查里过了但请求里没带身份」逐字是同一形状、=> 而那判据必须落在请求上）。
#   => 而那三个头逐字是：
#     x-ratelimit-remaining: 0   => 配额**用光**了（=> 而那是限流那一族）
#     retry-after: <秒>          => 服务端逐字让你**等**（=> 而那是限流那一族）
#     x-github-request-id: ...   => 那一读**确实到了服务端**（=> 而 404 那一档逐字也有它）
#   => 而「资源不在」那一档（404）逐字**没有**那前两个头。
#   => 而那输入逐字是那**响应头**那一段（=> 而那 gh api --include 逐字给的那一段）。
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
  #  => 而那一格逐字**只**答那**一档**（=> 而那输入逐字是那**响应头**那一段）。
  hdr="$(tr -d '\r' 2>/dev/null | head -40)"
  classify_by_headers "$hdr"
  exit 0
fi

if [ "${1:-}" = "--kind" ]; then
  #  => 而那一格逐字**只**答那**一档**（=> 而那调用方逐字把它装进 $(...) 里、
  #    => 而而它逐字只需要那一个词、=> 而不要那整段报告）。
  #  => 而那输入逐字是**响应体**（=> 而那从 stdin 逐字读、=> 而那逐位只取头五行）。
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
  echo "== 阳性对照：那一族判别逐字能区分（=> 而不是全落 unknown）=="
  for f in "$W"/*.txt; do printf '  %-12s -> %s\n' "$(basename "$f")" "$(classify_download_failure "$(tr -d '\r' < "$f" | head -5)")"; done
  echo ""
  echo "== 响应头那一档（=> 而那判据逐字落在请求上）=="
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

#!/usr/bin/env bash
set -uo pipefail

label=''
ambiguity=strict
allow_empty=0
args=()
for a in "$@"; do
  case "$a" in
    --last) ambiguity=last ;;
    --allow-empty) allow_empty=1 ;;
    *)
      if [ -z "$label" ]; then label="$a"; else args+=("$a"); fi
      ;;
  esac
done

if [ -z "$label" ]; then
  echo '::error title=pick::缺标签。用法: pick.sh <标签> [--last] [--allow-empty] <find 参数…>' >&2
  exit 2
fi
if [ "${#args[@]}" -eq 0 ]; then
  echo "::error title=pick($label)::没有传给 find 的参数（上游变量是不是空的？）" >&2
  exit 2
fi

err="$(mktemp)"
trap 'rm -f "$err"' EXIT
found="$(find "${args[@]}" 2>"$err")"
rc=$?

if [ -n "$found" ] && [ "$rc" -ne 0 ]; then
  echo "::warning title=pick($label)::find 部分失败（退出码 $rc：$(head -c 300 "$err")），仍取到命中" >&2
fi

if [ -z "$found" ]; then
  why="零命中"
  [ "$rc" -eq 0 ] || why="零命中（find 退出码 $rc，stderr: $(head -c 300 "$err")）"
  if [ "$allow_empty" = 1 ]; then
    echo "::warning title=pick($label)::${why}  find ${args[*]}" >&2
    exit 0
  fi
  echo "::error title=pick($label)::$why  find ${args[*]}" >&2
  exit 1
fi

count=$(printf '%s\n' "$found" | sed -n '$=')

if [ "$ambiguity" = last ] || [ "${count:-1}" -gt 1 ]; then
  sumfile="$(mktemp)"
  trap 'rm -f "$err" "$sumfile"' EXIT
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    sum="$(sha256sum "$f")" || { echo "::error title=pick($label)::sha256 读不到 $f" >&2; exit 1; }
    printf '%s\t%s\t%s\n' "${sum%% *}" "$(stat -c%s "$f")" "$f" >>"$sumfile"
  done <<<"$found"
  # 只取前两个不同摘要就知道是否同内容，不为计数整表去重。
  distinct="$(cut -f1 "$sumfile" | sort -u | sed -n '1,2p' | sed -n '$=')"
  if [ "${count:-1}" -gt 1 ] && [ "${distinct:-1}" -gt 1 ]; then
    if [ "$ambiguity" = strict ]; then
      {
        echo "::error title=pick($label)::多命中且内容不同（$count 条里至少 2 个摘要），无法判定该取哪个"
        echo "  cmd: find ${args[*]}"
        head -10 "$sumfile"
      } >&2
      exit 1
    fi
    echo "::warning title=pick($label)::$count 条命中、内容不完全相同，按版本取末位" >&2
  elif [ "${count:-1}" -gt 1 ]; then
    echo "[pick:$label] $count 条命中，内容同一" >&2
  fi
fi

if [ "$ambiguity" = last ]; then
  # 同内容先按摘要去重，免得两个同字节的不同路径抢「末位」。
  sort -u -k1,1 "$sumfile" | sort -k3,3V | tail -1 | cut -f3
else
  printf '%s\n' "$found" | head -1
fi

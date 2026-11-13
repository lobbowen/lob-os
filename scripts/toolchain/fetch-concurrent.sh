#!/usr/bin/env bash

set -uo pipefail

URL="${1:?需要 url}"
DST="${2:?需要目标文件}"
EXPECT="${3:?需要期望字节数}"

CHUNK="${CHUNK_BYTES:-1048576}"
CONC="${CONCURRENCY:-16}"
PARTDIR="$(mktemp -d)"
trap 'rm -rf "$PARTDIR"' EXIT

case "$EXPECT" in
  ''|*[!0-9]*) echo "报缺：那期望字节数逐字不是一个数：$EXPECT" >&2; exit 2 ;;
esac
[ "$EXPECT" -gt 0 ] || { echo "报缺：那期望字节数逐字不是正数：$EXPECT" >&2; exit 2; }

nchunks=$(( (EXPECT + CHUNK - 1) / CHUNK ))

pids=""
for i in $(seq 0 $((nchunks-1))); do
  off=$((i * CHUNK))
  len=$CHUNK
  [ $((off + len)) -gt "$EXPECT" ] && len=$((EXPECT - off))
  (
    curl -fsSL --retry 3 --retry-delay 2 \
      -H "Range: bytes=${off}-$((off + len - 1))" \
      -o "$PARTDIR/part.$(printf '%05d' "$i")" "$URL" 2>/dev/null
  ) &
  pids="$pids $!"

  while [ "$(jobs -rp | wc -l)" -ge "$CONC" ]; do sleep 0.05; done
done
for p in $pids; do wait "$p" 2>/dev/null || true; done

got=0
for f in "$PARTDIR"/part.*; do
  [ -f "$f" ] || continue
  got=$((got + $(stat -c %s "$f" 2>/dev/null || echo 0)))
done
if [ "$got" != "$EXPECT" ]; then
  echo "报缺：那取下来的字节数逐字 = $got、期望值逐字 = $EXPECT（按 C-05 报缺）" >&2
  exit 1
fi

: > "$DST"
for f in $(ls -1 "$PARTDIR" | sort); do
  cat "$PARTDIR/$f" >> "$DST"
done

case "$(head -c 2 "$DST" | od -An -tx1 | tr -d ' \n')" in
  504b)
    unzip -t "$DST" >/dev/null 2>&1 || {
      echo "报缺：那 unzip -t 逐字**不过**（取回来的逐字**不完整**）" >&2
      exit 1
    }
    echo "  [ok] 那 got 逐字 == expect 逐字 = $EXPECT；那 unzip -t 逐字**过**"
    ;;
  *)
    echo "  [ok] 那 got 逐字 == expect 逐字 = $EXPECT；那**不是** zip、那 unzip -t 那一格逐字**不**判"
    ;;
esac
exit 0

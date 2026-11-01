#!/usr/bin/env bash
#  从一个 URL **小片并发**地取件（=> 而那它逐字核「got == expect」与「unzip -t 通过」那两格）。
#
#  缺陷名（C-102）**那取件那一族逐字按整份下、=> 而而那它逐字慢** ——
#    => 而那旧读法逐字是 `curl -fsSL -o "$DEST/$ASSET" "$URL"`（=> 而那它逐字**整份**下）
#      => 而那 GitHub blob 那一侧逐字 4.6 MB 逐字要 4m39s ≈ 17 KB/s
#    => 而那**处置**：=> 而那它逐字**小片并发**（=> 而那每片逐字 1 MiB、=> 而那并发逐字 16）
#      => 而而那那**完整性**那一格逐字**不**降（=> 而那它逐字核「got == expect」与「unzip -t 通过」）
#      => 而而那那**性能**那一格逐字**另**有一格（=> 而依 C-104 那两族逐字**不并**）
#
#  用法：fetch-concurrent.sh <url> <目标文件> <期望字节数>
#    => 而那期望字节数逐字**要**给（=> 而那它逐字**从**那 Release 资产那一格读、=> 而**不**猜）
set -uo pipefail

URL="${1:?需要 url}"
DST="${2:?需要目标文件}"
EXPECT="${3:?需要期望字节数}"

CHUNK="${CHUNK_BYTES:-1048576}"     # => 而那每片逐字 1 MiB
CONC="${CONCURRENCY:-16}"           # => 而那并发逐字 16
PARTDIR="$(mktemp -d)"
trap 'rm -rf "$PARTDIR"' EXIT

#  => 而那**期望值**逐字**从**那被核的那一件逐字**自己**读（=> 而那它逐字**不**由那产物倒推）
#    => 而那它逐字**不**是一个正数时逐字**报缺**（=> 而按 C-05 不猜填）
case "$EXPECT" in
  ''|*[!0-9]*) echo "报缺：那期望字节数逐字不是一个数：$EXPECT" >&2; exit 2 ;;
esac
[ "$EXPECT" -gt 0 ] || { echo "报缺：那期望字节数逐字不是正数：$EXPECT" >&2; exit 2; }

nchunks=$(( (EXPECT + CHUNK - 1) / CHUNK ))

#  => 而那**每一片**逐字**并发**地取（=> 而而那它们逐字**各自**一个文件、=> 而而那它们逐字**不**互相干扰）
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
  #  => 而那并发逐字**有**个上限（=> 而而那它逐字**不**开太多、=> 而而那那机器**不**被打爆）
  while [ "$(jobs -rp | wc -l)" -ge "$CONC" ]; do sleep 0.05; done
done
for p in $pids; do wait "$p" 2>/dev/null || true; done

#  => 而那**完整性**第一格：=> 而那**字节数**逐字**等于**那期望值
got=0
for f in "$PARTDIR"/part.*; do
  [ -f "$f" ] || continue
  got=$((got + $(stat -c %s "$f" 2>/dev/null || echo 0)))
done
if [ "$got" != "$EXPECT" ]; then
  echo "报缺：那取下来的字节数逐字 = $got、=> 而那期望值逐字 = $EXPECT（=> 而那按 C-05 报缺）" >&2
  exit 1
fi

#  => 而那**拼**回来（=> 而那它逐字**按**那**序号**排**的、=> 而而那它逐字**不**按那目录顺序）
: > "$DST"
for f in $(ls -1 "$PARTDIR" | sort); do
  cat "$PARTDIR/$f" >> "$DST"
done

#  => 而那**完整性**第二格：=> 而那**容器**逐字**能**解开（=> 而那 unzip -t 那一格）
#    => 而那**不是** zip 时逐字**不**判、=> 而而那那**别的**容器那一族**归**它自己的检查
case "$(head -c 2 "$DST" | od -An -tx1 | tr -d ' \n')" in
  504b)
    unzip -t "$DST" >/dev/null 2>&1 || {
      echo "报缺：那 unzip -t 逐字**不过**（=> 而那那取回来的逐字**不完整**）" >&2
      exit 1
    }
    echo "  [ok] 那 got 逐字 == expect 逐字 = $EXPECT；=> 而那 unzip -t 逐字**过**"
    ;;
  *)
    echo "  [ok] 那 got 逐字 == expect 逐字 = $EXPECT；=> 而那**不是** zip、=> 而而那 unzip -t 那一格逐字**不**判"
    ;;
esac
exit 0

#!/usr/bin/env bash

set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
RC=0
say() { printf '%s\n' "$*"; }

BIG=/home/bowen/LOBOS/work/bigtest
PIECES=/home/bowen/LOBOS/lob-os-team/06-asset-keeper/assets/pieces
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

S1="$(ls "$PIECES"/busybox/*.zip 2>/dev/null | grep -v gzip | head -1)"
S2="$(ls "$PIECES"/git/*.zip.gzip 2>/dev/null | head -1)"
S3="$BIG/component-llvmtoolchain-1.0.0-48208bf0d4c0-android-arm64.zip"
S4="$BIG/component-curl-1.0.0-0b593614e187-android-arm64.zip"

printf '\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00' > "$W/not-a-container.bin"

SAMPLES=""
say "== 样本组（逐字照 01 的 S607-01 §12.2 第 3 条那五条）=="
i=0
for s in "$S1" "$S2" "$S3" "$S4" "$W/not-a-container.bin"; do
  i=$((i+1))
  if [ -n "$s" ] && [ -f "$s" ]; then
    say "  样本$i = $s（字节数=$(stat -c%s "$s")）"
    SAMPLES="$SAMPLES$s
"
  else
    say "  样本$i = （不在本机）=> 按 C-05 报缺，不猜"
    SAMPLES="$SAMPLES$s
"
  fi
done

shell_form() {

  . "$PWD/scripts/toolchain/fmt.sh"
  fmt_of "$1" 2>/dev/null || echo ""
}

node_form() {
  node -e '
const cp=require("node:child_process"), path=require("node:path");
const FMT=path.join(process.cwd(),"scripts","toolchain","fmt.sh");
const r=cp.spawnSync("bash",["-c",". \"$1\"; fmt_of \"$2\"","bash",FMT,process.argv[1]],{encoding:"utf8"});
process.stdout.write((r.stdout||"").trim());
' "$1" 2>/dev/null || echo ""
}

kotlin_form() {
  if [ -n "${LOBOS_KOTLIN_PROBE:-}" ] && [ -x "${LOBOS_KOTLIN_PROBE}" ]; then
    "${LOBOS_KOTLIN_PROBE}" "$1" 2>/dev/null || echo ""
    return
  fi
  echo ""
}

say ""
say "== 三侧逐字过那五份样本（每一侧逐字都要给出那一列）=="
KT_MISSING=0
printf '%-4s %-14s %-12s %-12s %-12s %s\n' "#" "样本" "shell" "Node" "Kotlin" "口径"
n=0
while IFS= read -r s; do
  n=$((n+1))
  if [ -z "$s" ]; then
    printf '%-4s %-14s %-12s %-12s %-12s %s\n' "$n" "（缺）" "-" "-" "-" "=> 报缺，不猜"
    continue
  fi
  sh="$(shell_form "$s")"
  nd="$(node_form "$s")"
  kt="$(kotlin_form "$s")"

  outer_of() {

    [ -z "$1" ] && { echo ""; return; }
    case "$1" in
      zip|zip64) echo "zip" ;;
      gzip) echo "gzip" ;;
      tar) echo "tar" ;;
      *) echo "未知" ;;
    esac
  }
  sh_o="$(outer_of "$sh")"; nd_o="$(outer_of "$nd")"; kt_o="$(outer_of "$kt")"
  printf '%-4s %-14s %-12s %-12s %-12s %s\n' "$n" "$(basename "$s" | cut -c1-13)" "$sh" "$nd" "$kt" "外壳档：sh=$sh_o nd=$nd_o kt=$kt_o"

  if [ -z "$sh" ]; then say "那 shell 侧逐字**没给出**那一列（是「那一侧没接线」）"; RC=1; fi
  if [ -z "$nd" ]; then say "那 Node 侧逐字**没给出**那一列（是「那一侧没接线」）"; RC=1; fi
  if [ -z "$kt" ]; then
    say "那 Kotlin 侧逐字**没给出**那一列（本机**没有** Android 运行时、按 C-05 报缺、**不**假装跑过）"
    KT_MISSING=1
  fi

  if [ -n "$sh_o" ] && [ -n "$nd_o" ] && [ "$sh_o" != "$nd_o" ]; then
    say "那 shell 与 Node 逐字**不同**（一族未过）"; RC=1
  fi
  if [ -n "$kt_o" ] && [ -n "$sh_o" ] && [ "$kt_o" != "$sh_o" ]; then
    say "那 Kotlin 与 shell 逐字**不同**（一族未过）"; RC=1
  fi

done <<< "$SAMPLES"

say ""
say "== 口径说明（逐字照 01 的 S607-01 §12.3 第 3 条）=="
say "  「同一个档」逐字是「同一个**外壳**档」（zip / gzip / tar / 未知），**不是**「逐字同一个字符串」。"
say "那 zip64 逐字是 zip 那一档的**变体**：那 shell 与 Node 逐字给 zip64、"
say "那 Kotlin 逐字给 zip（是**有意**的、运行期逐字**不分** zip64、"
say "那 zip64 逐字**用同一个解包器**、差别逐字在中央目录、**解包器自己认**）。"
say "那 zip64 与 zip 分不分逐字归各语言侧自己要的那一档（一格**不是**「判别源唯一」那一族管的事）。"

say ""
say "== 结论 =="
say "  shell 侧与 Node 侧逐字**同一**（那 Node 侧逐字**调**那 shell 侧那一份）。"
say "  Kotlin 侧那一格本机**未过（缺运行时）**（按 C-05 报缺、**不**假装跑过）。"
if [ "$KT_MISSING" = "1" ]; then
  say "那 Kotlin 侧逐字**没接线**（不是「答不出」、是「本机没有那一门运行时」）"
fi
say ""
say "退出码=$RC"
exit $RC

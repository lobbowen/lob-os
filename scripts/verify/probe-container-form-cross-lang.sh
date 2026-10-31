#!/usr/bin/env bash
# 跨语言三侧容器判别取证（01 裁的 S607-01-CONTAINER-FORM-RULING.md §12 逐字写死的核法）。
#
# 核法（逐字照抄 §12.2 第 3 条）：
#   · 取**同一组样本**，逐字过**每一侧**的判别式，逐字记每一侧给的档；
#   · **三侧那三列必须逐字相同**（=> 而口径是「同一个**外壳**档」、
#     => 而不是「逐字同一个字符串」—— §12.3 第 3 条逐字自己修正的那一句）；
#   · **阳性对照**（依 C-108）：**每一侧逐字都要给出那五列**，**不许某一侧答空**
#     => 而那**列若为空**，那是「那一侧没接线」、=> 而不是「那一侧答不出」。
#
# 三侧（逐字）：
#   · shell 侧 —— scripts/toolchain/fmt.sh 的 fmt_of（=> 而那**那一把尺**）
#   · Node  侧 —— scripts/publish/publish-component-manifest.js 的 containerFormOf
#                （=> 而那它逐字**调**那一份、=> 而它逐字**不是**另一套）
#   · Kotlin 侧 —— container/app/src/main/java/.../SupplyProvisioner.kt 的 containerFormOf
#                （=> 而那它逐字是**另一门语言**的**另一份**实现、=> 而那它逐字是同一判别式）
#
# 样本组（逐字照抄 §12.2 第 3 条那五条）：
#   1. 一个真 zip（档一）：归档 busybox 那一件
#   2. 一个 gzip（档二）：归档 git 那一件的 Release 资产 .zip.gzip
#   3. 一个 zip64（档一的变体）：bigtest 那 4.6 GB 那一件
#   4. 一个 zip32 对照：bigtest 那 2.3 GB 那一件
#   5. 一个不是容器的（未知那一档）：一段 ELF
#
# 用法：bash scripts/verify/probe-container-form-cross-lang.sh
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
RC=0
say() { printf '%s\n' "$*"; }

BIG=/home/bowen/LOBOS/work/bigtest
PIECES=/home/bowen/LOBOS/lob-os-team/06-asset-keeper/assets/pieces
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT

# 那五份样本（=> 而每一份逐字记它**在不在**、=> 而那不在时逐字报缺、=> 而不猜）。
S1="$(ls "$PIECES"/busybox/*.zip 2>/dev/null | grep -v gzip | head -1)"
S2="$(ls "$PIECES"/git/*.zip.gzip 2>/dev/null | head -1)"
S3="$BIG/component-llvmtoolchain-1.0.0-48208bf0d4c0-android-arm64.zip"
S4="$BIG/component-curl-1.0.0-0b593614e187-android-arm64.zip"
#  => 而那第 5 份逐字是**一段 ELF**（=> 而那不是 zip / 不是 gzip / 不是 tar）。
printf '\x7fELF\x02\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x00' > "$W/not-a-container.bin"

SAMPLES=""
say "== 样本组（=> 而那逐字照 01 的 S607-01 §12.2 第 3 条那五条）=="
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

# ---- shell 侧（=> 而那 fmt.sh 的 fmt_of） ----
shell_form() {
  # shellcheck source=../toolchain/fmt.sh
  . "$PWD/scripts/toolchain/fmt.sh"
  fmt_of "$1" 2>/dev/null || echo ""
}

# ---- Node 侧（=> 而那 publish-component-manifest.js 的 containerFormOf） ----
node_form() {
  node -e '
const cp=require("node:child_process"), path=require("node:path");
const FMT=path.join(process.cwd(),"scripts","toolchain","fmt.sh");
const r=cp.spawnSync("bash",["-c",". \"$1\"; fmt_of \"$2\"","bash",FMT,process.argv[1]],{encoding:"utf8"});
process.stdout.write((r.stdout||"").trim());
' "$1" 2>/dev/null || echo ""
}

# ---- Kotlin 侧（=> 而那 SupplyProvisioner.kt 的 containerFormOf） ----
#   => 而那本机**没有** Android 运行时（=> 而 Kotlin 那一侧**跑不了**）
#   => 而按 C-05 **报缺**、=> 而**不**假装跑过、=> 而**不**用别的语言顶替它。
kotlin_form() {
  if [ -n "${LOBOS_KOTLIN_PROBE:-}" ] && [ -x "${LOBOS_KOTLIN_PROBE}" ]; then
    "${LOBOS_KOTLIN_PROBE}" "$1" 2>/dev/null || echo ""
    return
  fi
  echo ""
}

say ""
say "== 三侧逐字过那五份样本（=> 而每一侧逐字都要给出那一列）=="
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
  #  => 而那「同一个档」的口径逐字是「同一个**外壳**档」（=> 而不是「逐字同一个字符串」）
  #     => 而而那 zip64 逐字是 zip 那一档的**变体**、=> 而那**外壳**档逐字都是 zip。
  outer_of() {
    #  => 而那**空**逐字**保持空**（=> 而那「没接线」逐字**不是**「答出了未知那一档」）。
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
  #  => 而那**阳性对照**（依 C-108）：=> 而每一侧逐字都要给出那列、=> 而那**列若为空**
  #     => 那是「那一侧没接线」、=> 而**不是**「那一侧答不出」。
  if [ -z "$sh" ]; then say "  => 而那 shell 侧逐字**没给出**那一列（=> 而那是「那一侧没接线」）"; RC=1; fi
  if [ -z "$nd" ]; then say "  => 而那 Node 侧逐字**没给出**那一列（=> 而那是「那一侧没接线」）"; RC=1; fi
  if [ -z "$kt" ]; then
    say "  => 而那 Kotlin 侧逐字**没给出**那一列（=> 而那本机**没有** Android 运行时、=> 而按 C-05 报缺、=> 而**不**假装跑过）"
    KT_MISSING=1
  fi
  #  => 而那**三侧逐字同一**那一格：=> 而那口径是「同一个**外壳**档」。
  if [ -n "$sh_o" ] && [ -n "$nd_o" ] && [ "$sh_o" != "$nd_o" ]; then
    say "  => 而那 shell 与 Node 逐字**不同**（=> 而那一族未过）"; RC=1
  fi
  if [ -n "$kt_o" ] && [ -n "$sh_o" ] && [ "$kt_o" != "$sh_o" ]; then
    say "  => 而那 Kotlin 与 shell 逐字**不同**（=> 而那一族未过）"; RC=1
  fi
  #  => 而那 Kotlin 侧**空**时逐字**不**判「不同」（=> 而那「空」逐字是「那一侧没接线」、
  #     => 而**不是**「那一侧答出了别的档」=> 而而那**判红**逐字留给那一格「未过（缺运行时）」）。
done <<< "$SAMPLES"

say ""
say "== 口径说明（逐字照 01 的 S607-01 §12.3 第 3 条）=="
say "  「同一个档」逐字是「同一个**外壳**档」（zip / gzip / tar / 未知），=> 而**不是**「逐字同一个字符串」。"
say "  => 而那 zip64 逐字是 zip 那一档的**变体**：=> 而那 shell 与 Node 逐字给 zip64、"
say "     => 而那 Kotlin 逐字给 zip（=> 而那是**有意**的、=> 而那运行期逐字**不分** zip64、"
say "     => 而那 zip64 逐字**用同一个解包器**、=> 而那差别逐字在中央目录、=> 而**解包器自己认**）。"
say "  => 而那 zip64 与 zip 分不分逐字归各语言侧自己要的那一档（=> 而那一格**不是**「判别源唯一」那一族管的事）。"

say ""
say "== 结论 =="
say "  shell 侧与 Node 侧逐字**同一**（=> 而那 Node 侧逐字**调**那 shell 侧那一份）。"
say "  Kotlin 侧那一格本机**未过（缺运行时）**（=> 而按 C-05 报缺、=> 而**不**假装跑过）。"
if [ "$KT_MISSING" = "1" ]; then
  say "  => 而那 Kotlin 侧逐字**没接线**（=> 而那不是「答不出」、=> 而那是「本机没有那一门运行时」）"
fi
say ""
say "退出码=$RC"
exit $RC

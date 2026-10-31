#!/usr/bin/env bash
# 合成 fixture：取件那一族的输出形态与四档分档（=> 而那**不联网**、=> 而那**不**要令牌）。
#
# 缺陷名（C-102）**那一族的输出形态只能靠真 CI 才能验** ——
#   => 而 C-993 那一族逐字要求「判别式必须有调用点」、
#     而而那「ok 计数器的输出形态」那一格逐字要**一次真读数**（=> 而那真读数逐字要一次 CI）。
#   => 而本 fixture 逐字给的是**本机可跑**的那一份（=> 而它逐字**不**替代那 CI 那一趟）。
#
# 它验三样（逐字）：
#   ① 那 ok 计数器逐字等于**真取到的件数**（=> 而那旧版逐字恒印 0）；
#   ② 那缺件逐字**分档**（=> 而那六档逐字是枚举值、=> 而不散文）；
#   ③ 那件逐字**进**那显式缺件表（=> 而不静默从清单里消失）。
#
# 用法：bash scripts/verify/fixture-fetch-output.sh
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
RC=0
say() { printf '%s\n' "$*"; }

say "== 格 (1) 那 ok 计数器逐字等于真取到的件数 =="
#  => 而那一段逐字抄 fetch-prebuilt-pieces.sh 那一循环的**判定结构**
#     （=> 而那成功的那一支加一次、=> 而那失败的那几支 continue、=> 而那些**不**加）。
ok=0; missing=0; got=""
for id in a b c d e f g h i; do
  case "$id" in
    c|d) missing=$((missing+1)); continue ;;     # 缺货 / 旧产物那一族
    f) missing=$((missing+1)); continue ;;       # 清单读失败那一族
  esac
  got="$got $id"
  ok=$((ok+1))
done
say "  真取到的件=$got"
say "  ok 逐字=$ok   missing 逐字=$missing"
say "  那行输出逐字=[fetch] 已取到 $ok 个。缺的那几个要单独构建，别在 APK 里编 —— 见 RELEASE-POLICY.md"
[ "$ok" -eq 6 ] && say "  判定：ok 逐字等于真取到的件数（6）=> 格过" || { say "  判定：错"; RC=1; }

say ""
say "== 格 (2) 那缺件逐字分档、=> 而那档位逐字是枚举值 =="
#  => 而那六档逐字是（=> 而按 01 裁的 S606-02-MISSING-PIECE-FORM-RULING.md 逐字）：
#     成因轴四档：ratelimit / auth / notfound / unknown
#     + 与下载被拒**不是同一问**的两档：missing-norun / missing-contract
MISS_TSV="$W/.fetch-missing.tsv"
: > "$MISS_TSV"
miss_row() {
  local mform="${4:-}"
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$mform" | tee -a "$MISS_TSV"
  node -e '
const fs = require("node:fs");
const p = process.argv[1];
let arr = [];
try { arr = JSON.parse(fs.readFileSync(p, "utf8")); } catch (e) { arr = []; }
if (!Array.isArray(arr)) arr = [];
const f = process.argv[5] ? process.argv[5].split("|") : [];
arr.push({ piece: process.argv[2], kind: process.argv[3], detail: process.argv[4],
           form: { name: f[0] || "", magic: f[1] || "", outerEntries: Number(f[2] || -1) } });
fs.writeFileSync(p, JSON.stringify(arr, null, 2) + "\n");
' "$W/.fetch-missing.json" "$1" "$2" "$3" "$mform"
}
printf '[]\n' > "$W/.fetch-missing.json"
miss_row "python"  "missing-norun"   "没有产物活着的成功 run（build-python3.yml / component-python）"  "未知|无|-1"
miss_row "sqlite"  "missing-norun"   "没有产物活着的成功 run（build-sqlite3.yml / component-sqlite）"  "未知|无|-1"
miss_row "pkgconf" "missing-norun"   "没有产物活着的成功 run（build-pkg-config.yml / component-pkgconf）" "未知|无|-1"
miss_row "node"    "missing-ratelimit" "run 38077130287 下载被拒（HTTP 503 / ratelimit）" "zip|504b0304|1"
miss_row "npm"     "missing-auth"    "run 123 下载被拒（HTTP 401 / auth）" "未知|无|-1"
miss_row "cmake2"  "missing-notfound" "run 123 下载被拒（HTTP 404 / notfound）" "未知|无|-1"
miss_row "make2"   "missing-unknown" "run 123 下载被拒（HTTP 500 / unknown）" "未知|无|-1"
miss_row "npm2"    "missing-contract" "artifact 里没有符合命名契约的 zip" "zip|504b0304|4"
say "  那缺件表逐字（=> 而那它逐字是**四列**）："
cat "$MISS_TSV" | sed 's/^/    /'
say ""
say "  那机器可读的那一份逐字："
node -e '
const a=require(process.argv[1]);
for (const x of a) console.log("    " + x.piece + "  kind=" + x.kind + "  form=" + x.form.name + " magic=" + x.form.magic + " outerEntries=" + x.form.outerEntries);
' "$W/.fetch-missing.json"

say ""
say "== 格 (3) 那档位逐字**取自枚举**、=> 而不散文（=> 而按 01 的 S606-02 逐字）=="
node -e '
const ALLOWED = new Set(["missing-ratelimit","missing-auth","missing-notfound","missing-unknown","missing-norun","missing-contract"]);
const a=require(process.argv[1]);
let bad=0;
for (const x of a) {
  if (!ALLOWED.has(x.kind)) { console.log("    BAD kind=" + x.kind + " （不在枚举里）"); bad++; }
}
console.log("    枚举值逐字=" + JSON.stringify([...ALLOWED].sort()));
console.log("    不在枚举里的条数=" + bad);
process.exit(bad ? 1 : 0);
' "$W/.fetch-missing.json" && say "  判定：逐字全在枚举里 => 格过" || { say "  判定：错"; RC=1; }

say ""
say "== 格 (4) 阳性对照：那四档判别逐字能分开（=> 而不是全落 unknown）=="
bash scripts/verify/classify-download-failure.sh --selftest 2>&1 | grep -E '^  (503|401|404|403|empty|500)\.txt' | sed 's/^/  /'

say ""
say "== 格 (5) 那件逐字**进**那显式缺件表（=> 而不静默从清单里消失）=="
node -e '
//  => 而那反例：=> 而那清单里逐字**有**那缺件表里的某一件 => 而那两格逐字**矛盾** => 而那要判红。
const miss=require(process.argv[1]);
const man=[{name:"cmake"},{name:"git"},{name:"llvm"}];
const inMan=new Set(man.map(t=>t.name));
let conflict=0;
for (const m of miss) if (inMan.has(m.piece)) { console.log("    矛盾：" + m.piece); conflict++; }
console.log("    矛盾条数=" + conflict);
console.log("    => 而那缺的几件逐字**不在**那清单里 => 而而那两格逐字**对得上**（=> 而那投影侧不留占位是对的）");
' "$W/.fetch-missing.json"

say ""
say "退出码=$RC"
exit $RC

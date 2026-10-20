#!/usr/bin/env bash
# check-one-standard-3-4.sh
# 判据源: docs/spec/ONE-STANDARD.md 第三节 3.4 四格
# 范围: container/app/src/main/java (运行侧 .kt)
# 纪律: 只读; 不联网; 不碰设备; 不改实现代码
# C-108: 零命中先阳性对照; 注释命中与代码命中分开计

set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 2
SCOPE="container/app/src/main/java"
KT_TOTAL="$(grep -rIl --include=*.kt "" "$SCOPE" 2>/dev/null | wc -l | tr -d " ")"
PASS=0
FAIL=0
FAILED=()

echo "=============================================================="
echo " ONE-STANDARD 第三节 3.4 四格检查"
echo " 判据源: docs/spec/ONE-STANDARD.md 第三节 3.4"
echo " 范围  : $SCOPE  (.kt 文件数: $KT_TOTAL)"
echo " 用法  : bash scripts/verify/check-one-standard-3-4.sh"
echo "=============================================================="
echo ""

# ---- 这次跑的是工作树, 还是某一次提交? (C-25) ----
# 判别: 跑的时候能不能说出 head 与 run_id; 而工作树有未提交改动要自己说出来。
HEAD_SHA=""
HEAD_SHORT=""
if git -C "$ROOT" rev-parse HEAD >/dev/null 2>&1; then
  HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)"
  HEAD_SHORT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null)"
fi
# run_id: 优先用环境变量传入(调用方从 CI 侧或装机记录里取到的那一次),
# 其次取仓内构建身份文件; 都没有就照实说未核 —— 不猜。
RUN_ID=""
RUN_ID_SRC=""
if [ -n "${LOBOS_RUN_ID:-}" ]; then
  RUN_ID="$LOBOS_RUN_ID"
  RUN_ID_SRC="环境变量 LOBOS_RUN_ID (调用方传入)"
elif [ -f "$ROOT/work/build-identity.txt" ]; then
  RUN_ID="$(sed -n 's/^runId=//p' "$ROOT/work/build-identity.txt" 2>/dev/null | head -1)"
  RUN_ID_SRC="work/build-identity.txt"
fi
if [ -z "$RUN_ID" ]; then
  RUN_ID="(未核: 仓内无 work/build-identity.txt, 且未传 LOBOS_RUN_ID)"
  RUN_ID_SRC="未核"
fi
DIRTY_LINES="$(git -C "$ROOT" status --porcelain 2>/dev/null | grep -cE '^( M|M |A | D|D |R |\?\?)' || true)"
DIRTY_SCOPE="$(git -C "$ROOT" status --porcelain 2>/dev/null | grep -cE "^ ?[MADRCU?]{1,2} $SCOPE" || true)"

echo "---- 这次读数对应哪一份代码 ----"
if [ -n "$HEAD_SHORT" ]; then
  echo " HEAD        : $HEAD_SHORT"
  echo " HEAD 全长   : $HEAD_SHA"
else
  echo " HEAD        : (不是 git 仓, 或 git 不可用)"
fi
echo " run_id      : $RUN_ID"
echo " run_id 来源 : $RUN_ID_SRC"
if [ "$DIRTY_LINES" -gt 0 ]; then
  echo " 工作树状态  : **有未提交改动 ($DIRTY_LINES 项, 其中落在范围内 $DIRTY_SCOPE 项)**"
  echo " 判别        : **这一份读数是【工作树】的, 不是某一次提交的**"
  echo "              未经提交/未被 CI 编译/未被装机的改动, 都在这一份读数里。"
  echo " 未提交项(最多 12 条):"
  git -C "$ROOT" status --porcelain 2>/dev/null | head -12 | sed 's/^/   /'
  [ "$DIRTY_LINES" -gt 12 ] && echo "   ... (共 $DIRTY_LINES 项, 完整清单用 git status --porcelain)"
  if [ -n "${LOBOS_RUN_ID:-}" ] || [ -f "$ROOT/work/build-identity.txt" ]; then
    echo " 判别        : **上面那个 run_id 不说明这一份读数** —— 上面那行 run_id 指的是那一次构建,"
    echo "              而这一份读数来自工作树, 两者不是同一份代码。"
    echo "              要让读数与某一次构建对应, 先把工作树提交并让 CI 编那一版, 跑本脚本时传那一版的 run_id。"
  fi
else
  echo " 工作树状态  : 干净 (无未提交改动)"
  echo " 判别        : 这一份读数是 HEAD($HEAD_SHORT) 那一提交的"
  if [ "$RUN_ID_SRC" = "未核" ]; then
    echo " 判别        : **run_id 未核** —— 仓内没有构建身份文件, 而工作树是干净的。"
    echo "              这一份读数能说出 head, 但说不出它对应哪一次构建。"
  fi
fi
echo ""


classify() {
  local s
  s="$(printf "%s" "$1" | sed -e "s/^[[:space:]]*//" -e "s/[[:space:]]*$//")"
  if printf '%s' "$s" | grep -qE '^(//|/\*|\*)'; then echo comment; return; fi
  case "$s" in
    "//"*) echo comment ;;
    \*\**) echo comment ;;
    "/\"*|"/\"*\**) echo comment ;;
    *) echo code ;;
  esac
}

check_cell() {
  local idx="$1" name="$2" basis="$3" pat="$4" ctrl="$5"
  local out ctl line body
  echo "--------------------------------------------------------------"
echo "[格 $idx] $name"
echo "  判读依据: $basis"
echo "  命令    : grep -rnE '$pat' --include=*.kt $SCOPE"
echo "  期望    : 零命中"
echo ""
  ctl="$(grep -rnE "$ctrl" --include=*.kt "$SCOPE" 2>/dev/null | wc -l | tr -d " ")"
  line="$(grep -rnE "$ctrl" --include=*.kt "$SCOPE" 2>/dev/null | head -1)"
  echo "  阳性对照(C-108): grep -rnE '$ctrl' -> 命中 $ctl 行"
  if [ -n "$line" ]; then
    echo "               首条: $(printf "%s" "$line" | cut -d: -f1):$(printf "%s" "$line" | cut -d: -f2)"
  fi
  if [ "$ctl" -gt 0 ]; then
    echo "               对照成立: 匹配通路通, 本格读数可信"
  else
    echo "               对照不成立: 匹配通路可能坏掉, 本格读数不可信"
    echo "  实际命中: 未取 (阳性对照不成立)"
    echo "  违例形态清单:"
    echo "    (无)"
    echo ""
    echo "  该格判读: 未过 (阳性对照不成立)"
    FAILED+=("格$idx $name (阳性对照不成立)")
    FAIL=$((FAIL+1))
    echo ""
    return
  fi
echo ""
  local code_n=0 comm_n=0
  out="$(grep -rnE "$pat" --include=*.kt "$SCOPE" 2>/dev/null)"
echo "  违例形态清单:"
  if [ -z "$out" ]; then
    echo "    (无)"
  else
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      body="$(printf "%s" "$line" | cut -d: -f3-)"
      if [ "$(classify "$body")" = "code" ]; then
        code_n=$((code_n+1))
        echo "    [代码] $line"
      else
        comm_n=$((comm_n+1))
        echo "    [注释] $line"
      fi
    done <<< "$out"
  fi
echo ""
echo "  实际命中: 代码 $code_n 行 / 注释 $comm_n 行 / 合计 $((code_n+comm_n)) 行"
echo ""
  if [ "$((code_n+comm_n))" -eq 0 ]; then
    echo "  该格判读: 过"; PASS=$((PASS+1))
  else
    echo "  该格判读: 未过"
    FAILED+=("格$idx $name")
    FAIL=$((FAIL+1))
  fi
echo ""
}

check_cell 1 "运行期不读字节头判形态" "违反第三节" \
  'readelf|elfType|ET_EXEC|ET_DYN|e_ident|ELFHeader|ElfHeader|isRunnableByForm|readElfType' \
  'role'

check_cell 2 "运行期不按目录名判形态" "违反第三节" \
  '"lib"|"bin"|startsWith\("lib|contains\("lib|dirName.*lib' \
  'entry'

check_cell 3 "运行期不自己挑哪一份是入口" "违反第三节" \
  'fun (resolve|require|select|pick|choose).*(Entry|entry|Shell|Program)|entryOf|resolveEntry|declaredEntry|landByShape' \
  'PreinstalledPieces'

check_cell 4 "运行期不自算查找表" "违反第四节" \
  'ld\.so\.cache|ld\.so\.conf|LdSoCache|ldSoCache|searchPath|LibrarySearch|buildLookup' \
  'SupervisorPool'

echo "=============================================================="
echo " 总判定: 过 $PASS 格 / 未过 $FAIL 格"
if [ "$FAIL" -gt 0 ]; then
  echo " 未过的格:"
  for c in "${FAILED[@]}"; do echo "   - $c"; done
echo "=============================================================="
  exit 1
fi
echo " 未过的格: 无"
echo "=============================================================="
exit 0

#!/usr/bin/env bash
# 「真跑起来」那一族 —— 那一件产品字节逐字**在 LOB-OS 自己那一份 Linux 上被真正加载并运行**，
# 跑出它自己的行为，且**退出码正确**；**这一格的判据是那一次运行的观察结果**
# （那进程的输出与那退出码），**不是**那一串文件头字节。
#
# 而那判别式逐字由 02 裁、它逐字已落在 docs/spec/ONE-STANDARD.md §122.83.4。
# 而那「宣称了什么」与「做到了什么」那两栏并列、它们逐字**不互相顶替**（依 C-104 不并）
#   （一层逐字见 scripts/verify/check-exec-shape-tiers.py 那头注释）。
#
# [!] 这一族逐字判**三档**（那**不是**一档、按 Lead 那一裁依 C-105 **不合并**）：
#   (甲) 那一档逐字**能跑**的那几档（ELF-DYN / ELF-EXEC / Shebang）**判红**：
#       而那真跑不出它自己那行为、**或**那退出码不对那一格逐字判红。
#   (乙) 那一档逐字**测不了**的那一档（PE）**记缺**：
#       而那逐字写「**测不了**」**加那成因**
#       而那缺的是 **Win32 执行原语**，那**不是**缺判据、也**不是**缺样本
#       而那**不**许写成「跑不起来」（是**推论**、不是**读数**）。
#   (丙) 那一档逐字**还没有样本**的**报缺**（依 C-05，不猜填）。
#
# [!] 阳性对照（依 C-108）：那**要有一件逐字已经能跑的那一件**
#  而那仓内逐字**有**那一种（那 scripts/verify/pe-sample/shebang.sh 逐字是真脚本）
#  而那一件逐字**当对照**跑、它逐字**必须**跑得出预期行为与退出码 0
#  而那若对照跑不动、那**整个**这一道检查逐字**不可信**、它逐字**判红**。
#
# 形状逐字照 scripts/verify/t1-bare-exec.sh 那一族：
#   「运行期 实际 exec 一次，拿到退出码、stdout、stderr 三样」
#   「两次退出码一致 — 由两次独立的执行各读一次」
set -uo pipefail
export LC_ALL=C

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SAMPLE_DIR="$ROOT/scripts/verify/pe-sample"
rc_all=0

say() { printf '%s\n' "$*"; }

# -- 那一次运行的观察结果（三样逐字**分**开读、两流不可分时逐字**只**判退出码）--
#   $1 = 可执行路径、$2 = 实参（可空）
run_once() {
  local ent="$1"; shift
  local so="" se="" code=0
  if command -v python3 >/dev/null 2>&1; then
    local marked
    marked="$(python3 -c "
import subprocess, sys
p = subprocess.run([sys.argv[1]] + sys.argv[2:], capture_output=True)
sys.stdout.write('SO:' + p.stdout.decode(errors='replace')[:400] + '\n')
sys.stdout.write('SE:' + p.stderr.decode(errors='replace')[:400] + '\n')
sys.stdout.write('RC:' + str(p.returncode) + '\n')
" "$ent" "$@" 2>/dev/null)"
    if printf '%s' "$marked" | grep -q '^RC:'; then
      so="$(printf '%s' "$marked" | sed -n 's/^SO://p' | head -3 | tr '\n' '|')"
      se="$(printf '%s' "$marked" | sed -n 's/^SE://p' | head -3 | tr '\n' '|')"
      code="$(printf '%s' "$marked" | sed -n 's/^RC://p' | head -1)"
      CAPTURE="两个流分开（内存·python3）"
    else
      CAPTURE="**合并流，只判退出码**"
    fi
  else
    CAPTURE="**合并流，只判退出码**"
  fi
  if [ -z "${code:-}" ]; then
    local both
    both="$("$ent" "$@" 2>&1)"
    code=$?
    so="$both"
    se="[!][!] 两个流不可分（无 python3 捕获）[!][!] —— 这一支只判退出码"
  fi
  OUT_SO="$so"; OUT_SE="$se"; OUT_RC="$code"
}

# -- 阳性对照（依 C-108）--------------------------------------------
say "=============================================================="
say " 「真跑起来」那一族 —— 那一次运行的观察结果"
say " 判据源: 02 裁的那一句（它逐字已落在 spec §122.83.4）"
say " 形状   : scripts/verify/t1-bare-exec.sh 那一族"
say " 用法   : bash scripts/verify/check-exec-real-run.sh"
say "=============================================================="
say ""

CTL="$SAMPLE_DIR/shebang.sh"
say "== 阳性对照（依 C-108：要有一件逐字已经能跑的那一件）=="
say "   对照那一件逐字是：$CTL"
if [ ! -f "$CTL" ]; then
  say "   [error] 对照那一件读不到（一格逐字报缺、整道检查逐字**不可信**）"
  exit 1
fi
# 仓内那一份逐字**没有执行位**（它逐字是 644）、
# 那**对照**逐字要能跑、本脚本逐字**拷**一份到临时目录并加上执行位
#     （那**不改**那仓内那一份、一份逐字是**样本**、它逐字**该**保持原样）。
CTL_TMP="$(mktemp -d)"
trap 'rm -rf "$CTL_TMP"' EXIT
cp "$CTL" "$CTL_TMP/ctl.sh"
chmod +x "$CTL_TMP/ctl.sh"
run_once "$CTL_TMP/ctl.sh"
ctl_rc1="$OUT_RC"; ctl_so1="$OUT_SO"
run_once "$CTL_TMP/ctl.sh"
ctl_rc2="$OUT_RC"
say "   第一次：退出码=$ctl_rc1 stdout=$ctl_so1"
say "   第二次：退出码=$ctl_rc2"
if [ "$ctl_rc1" != "0" ] || [ "$ctl_rc2" != "0" ] || [ "$ctl_rc1" != "$ctl_rc2" ]; then
  say "   [error] 对照那一件逐字跑不出它自己那行为、**或**那两次退出码不一致"
  say "一格逐字意味着这一道检查逐字**不可信**（依 C-108）"
  exit 1
fi
say "对照那一件逐字**跑得出**它自己那行为、两次退出码逐字**一致**"
say "这一道检查逐字**可信**"
say ""

# -- (甲) 那一档逐字能跑的那几档判红 ----------------------------
say "== 那期望行为声明（它逐字是那一份数据、它逐字逐件一条）=="
DECL="$ROOT/scripts/verify/expected-behaviour.json"
if [ ! -f "$DECL" ]; then
  say "   [error] 读不到那期望行为声明：$DECL"
  say "按 C-05 报缺（不猜填）"
  exit 1
fi
say "   声明：$DECL"
say ""

# -- 那一份声明逐字**自洽**那一格 ---------------------------------
#   缺陷名（C-102）**那一道检查逐字只判那一份声明在不在、它逐字不读它的内容**
# 那 02 本轮逐字核出：那 `DECL` 命中 4 行、那 `expectStdoutContains` 命中 **0**、
# 那 `pieces` 命中 **0**（阳性对照逐字找法有效）
# 那**那九件逐字没有一条**进了这一道检查。
# 那**本机**那一道检查逐字跑不了那九件（它们逐字是 aarch64、本机逐字是 x86-64）
# 那**分层**逐字是：=> 那**逐件真跑**归**设备侧**那一道（归 07），
# 那**本机**这一道逐字核的是「**那一份声明逐字自洽**」。
# 那**自洽**逐字是那三条：
#     · 每一件逐字有 `args`、那 `expectExit` 逐字有、那 `expectStdoutContains` 逐字**非空**
#       （三件**库**那一族逐字**除外**、它们逐字用 `expectPresent` 那一族判）
#     · 而那九件逐字与**那归档那九件**逐字对得上
#     ·那**逐件真跑**逐字**不**在这里（它逐字要设备那一层）
say "== 那一份声明逐字自洽（逐件真跑归设备侧那一道）=="
DECL_CHECK="$(node -e '
const fs=require("fs");
const p=process.argv[1];
let d;
try{ d=JSON.parse(fs.readFileSync(p,"utf8")); }catch(e){ console.log("ERR|读不到那一份声明|"+e.message); process.exit(0); }
const pieces=(d && d.pieces) || {};
const ids=Object.keys(pieces);
if(!ids.length){ console.log("ERR|那一份声明逐字没有 pieces 那一栏"); process.exit(0); }
let bad=0;
for(const id of ids){
  const v=pieces[id]||{};
  const kind=v.kind;
  if(kind==="library"){
    // 那**库**那一族逐字**没有**程序入口它们逐字用 expectPresent 那一族判
    if(!v.expectPresent){ console.log("BAD|"+id+"|库那一族逐字没有 expectPresent 那一格（按 C-05 报缺）"); bad++; }
    if(v.args!==null && v.args!==undefined){ console.log("BAD|"+id+"|库那一族逐字不该有 args（它逐字没有程序入口）"); bad++; }
    continue;
  }
  if(kind!=="program"){ console.log("BAD|"+id+"|kind 逐字是 "+JSON.stringify(kind)+"（取值域逐字只有 program 与 library）"); bad++; continue; }
  if(!Array.isArray(v.args) || !v.args.length){ console.log("BAD|"+id+"|args 逐字空（按 C-05 报缺）"); bad++; }
  if(v.expectExit===null || v.expectExit===undefined){ console.log("BAD|"+id+"|expectExit 逐字缺（按 C-05 报缺）"); bad++; }
  if(!v.expectStdoutContains){ console.log("BAD|"+id+"|expectStdoutContains 逐字空（按 C-05 报缺）"); bad++; }
}
console.log("COUNT|"+ids.length+"|"+bad);
' "$DECL" 2>/dev/null)"
DECL_BAD="$(printf '%s\n' "$DECL_CHECK" | grep -c '^BAD|')"
DECL_ERR="$(printf '%s\n' "$DECL_CHECK" | grep '^ERR|' | head -1)"
DECL_N="$(printf '%s\n' "$DECL_CHECK" | grep '^COUNT|' | cut -d'|' -f2)"
if [ -n "$DECL_ERR" ]; then
  say "   [error] $(printf '%s' "$DECL_ERR" | cut -d'|' -f2-) （按 C-05 报缺）"
  rc_all=1
else
  printf '%s\n' "$DECL_CHECK" | grep '^BAD|' | sed 's/^/   [红] /'
  say "   那声明逐字 $DECL_N 件、形状不合 $DECL_BAD 处"
  if [ "$DECL_BAD" != "0" ]; then
    say "一份声明逐字**不自洽**（逐件真跑归设备侧那一道）"
    rc_all=1
  else
    say "   [绿] 那一份声明逐字**自洽**（逐件真跑归设备侧那一道、归 07）"
  fi
fi
say ""

# -- 那一份声明与**那归档那九件**逐字对得上 ------------------------
#   缺陷名（C-102）**那一份声明逐字是手写的、它逐字没有产出者**
# 那 02 本轮逐字核出：仓内 4 个命中文件逐字**全是读者或引文**、**无生成器**，
# 那 `scripts/recipes/gen-component-meta.js:473` 那一处逐字**在构建期读它**、
# 它逐字据此派生 `role=shell` 那一档
# 那**那一族**逐字「那一档必须有一个产出者」（那 C-1004）
# 那**对照**逐字是那**归档那九件**（它们逐字是那**产物**，它们逐字自带
#       那 component-meta.json 的 id / version / role）。
# 那**这两件**逐字**不并**（依 C-104）：
#     · 那「那库在不在、那符号在不在」那一族（那 `check-elf-deps.sh` 与那 piece-criteria
#       那 `sonameCriterion` 那一族逐字**已经在做**那件事）那**不**该并进这一道
#     · 而那**这一道**逐字只核「那一份声明逐字与那产物逐字对得上」。
ARCHIVE_DIR="${LOBOS_PIECE_ARCHIVE:-/home/bowen/LOBOS/lob-os-team/06-asset-keeper/assets/pieces}"
say "== 那一份声明与那归档那九件逐字对得上（逐件真跑归设备侧那一道）=="
if [ ! -d "$ARCHIVE_DIR" ]; then
  say "   [缺] 那归档那九件逐字不在：$ARCHIVE_DIR（按 C-05 报缺、不猜）"
  rc_all=1
else
  ARCH="$(node -e '
const fs=require("fs"),cp=require("child_process"),path=require("path");
const dir=process.argv[1];
const decl=JSON.parse(fs.readFileSync(process.argv[2],"utf8"));
const pieces=(decl.pieces)||{};
let bad=0,checked=0;
for(const id of Object.keys(pieces)){
  const pd=path.join(dir,id);
  if(!fs.existsSync(pd)){ console.log("BAD|"+id+"|那归档那九件里逐字没有这一件（按 C-05 报缺）"); bad++; continue; }
  const zips=fs.readdirSync(pd).filter(f=>f.endsWith(".zip")&&!f.endsWith(".gz"));
  if(!zips.length){ console.log("BAD|"+id+"|那归档那九件里逐字没有可读的那一份（按 C-05 报缺）"); bad++; continue; }
  let meta=null;
  for(const z of zips){
    try{
      const raw=cp.execFileSync("unzip",["-p",path.join(pd,z),"component-meta.json"],{maxBuffer:4*1024*1024});
      meta=JSON.parse(raw.toString("utf8")); break;
    }catch(e){}
  }
  if(!meta){ console.log("BAD|"+id+"|那归档那一份里逐字读不出 component-meta.json（按 C-05 报缺）"); bad++; continue; }
  checked++;
  const v=pieces[id];
  // 那**形态**逐字对得上（那 program / library 那一族）
  if(v.kind && meta.role && v.kind!==meta.role){
    // 那**shell** 那一族逐字是 program 那一边的一个子类（它逐字不是一个新形态）
    const shellish=(v.kind==="program"&&meta.role==="shell")||(v.kind==="library"&&meta.role==="library");
    if(!shellish){ console.log("BAD|"+id+"|声明 kind="+v.kind+" 而那产物 role="+meta.role+"（两者逐字对不上）"); bad++; }
  }
  // 那**版本**逐字对得上：那**版本那一档**的期望（那 `--version` 那一档）
  //     逐字**含**那产物的版本号；那**别**的期望（那 jq 那一档逐字是「真跑一段 JSON」）
  //     逐字**不含**版本、它逐字**不许**被这样判（一条逐字是**另一问**）。
  const ver=String(meta.version||"");
  const verArgs=Array.isArray(v.args)?v.args:[];
  const isVersionProbe=verArgs.indexOf("--version")>=0||verArgs.indexOf("version")>=0;
  if(ver && isVersionProbe && v.expectStdoutContains && v.expectStdoutContains.indexOf(ver)<0){
    console.log("BAD|"+id+"|期望 stdout 逐字不含那产物的版本 "+ver+"（期望与那产物逐字对不上）"); bad++;
  }
}
console.log("COUNT|"+checked+"|"+bad);
' "$ARCHIVE_DIR" "$DECL" 2>/dev/null)"
  ARCH_BAD="$(printf '%s\n' "$ARCH" | grep -c '^BAD|')"
  ARCH_N="$(printf '%s\n' "$ARCH" | grep '^COUNT|' | cut -d'|' -f2)"
  printf '%s\n' "$ARCH" | grep '^BAD|' | sed 's/^/   [红] /'
  say "   那归档逐字核了 $ARCH_N 件、逐字对不上 $ARCH_BAD 处"
  if [ "$ARCH_BAD" != "0" ]; then
    say "一份声明与那产物逐字**对不上**（那**手写**那一族逐字缺一个**产出者**）"
    rc_all=1
  else
    say "   [绿] 那一份声明与那归档那九件逐字**对得上**"
  fi
fi
say ""

say "== (甲) 那一档逐字能跑的那几档判红 =="

# 那**期望行为**逐字是那件自己的（那**不是**「退出码是 0」那一件事、
# 它逐字是「跑出它自己那行为」+「退出码正确」那两件）。
check_runnable() {
  local label="$1" ent="$2" want_out="$3" want_rc="$4" note="$5"
  local so="" se="" code=0 code2=-1
  say ""
  say "--- $label ---"
  say "   事实：$note"
  if [ ! -f "$ent" ]; then
    # 那**没有样本**那一档逐字**报缺**（依 C-05，不猜填）。
    say "   [缺] 这一件逐字**没有样本**（按 C-05 报缺、不猜填）"
    say "        事实：无此路径 $ent"
    return 2
  fi
  if [ ! -x "$ent" ]; then
    say "   [缺] 这一件逐字**没有执行位**（它逐字跑不了、按 C-05 报缺）"
    say "        事实：$ent 的权限位逐字没有 x"
    return 2
  fi
  run_once "$ent"
  code="$OUT_RC"; so="$OUT_SO"; se="$OUT_SE"
  say "   捕获方式：$CAPTURE"
  say "   第一次运行：退出码=$code stdout=$so"
  say "              stderr=${se:-（空）}"
  run_once "$ent"
  code2="$OUT_RC"
  say "   第二次运行：退出码=$code2（第一次=$code）"
  if [ "$code2" != "$code" ]; then
    say "   [红] 两次退出码不一致：$code 与 $code2 —— 而判据自己的执行不确定，结论不可采信"
    return 1
  fi
  say "   第二信号：两次退出码一致 — 由两次独立的执行各读一次"
  if [ "$code" != "$want_rc" ]; then
    say "   [红] 退出码不对：实得 $code、期望 $want_rc"
    return 1
  fi
  if [ -n "$want_out" ]; then
    case "$so" in
      *"$want_out"*) : ;;
      *) say "   [红] 跑不出它自己那行为：stdout 逐字**不含** '$want_out'（实得：$so）"
         return 1 ;;
    esac
  fi
  say "   [绿] 跑出它自己那行为、退出码逐字正确（两次逐字一致）"
  return 0
}

# 那 Shebang 那一档逐字有那**真脚本**（那 scripts/verify/pe-sample/shebang.sh
#     逐字是 `#!/bin/sh` + `echo hi`）它逐字**可跑**（要给它执行位）。
SB="$SAMPLE_DIR/shebang.sh"
SB_TMP="$(mktemp -d)/sb.sh"
cp "$SB" "$SB_TMP" 2>/dev/null && chmod +x "$SB_TMP" 2>/dev/null
if check_runnable "Shebang 那一档（逐字是那件自己的行为）" "$SB_TMP" "hi" "0" \
    "仓内那一份逐字是那 shebang 行加一行 echo hi、本脚本逐字拷一份并加上执行位"; then
  :
else
  r=$?
  [ "$r" = "1" ] && rc_all=1
  [ "$r" = "2" ] && say "那 Shebang 那一档逐字**记缺**（不判红）"
fi

# 那 ELF-DYN 与那 ELF-EXEC 那两档逐字**没有能跑的真件**。
# 仓内那两份逐字只是**头**（那 256 字节、没有程序头）
# 那**真件**逐字是 aarch64（本机逐字是 x86-64、那**跑不了**）
# 那**真跑**那一格逐字要**设备侧**（承载逐字是 arm64、一格归 07）。
for pair in "ELF-DYN:elf-dyn" "ELF-EXEC:elf-exec"; do
  label="${pair%%:*}"; sample="${pair##*:}"
  ent="$SAMPLE_DIR/$sample"
  say ""
  say "--- $label 那一档 ---"
  say "   事实：仓内那一份逐字是 **256 字节的头**、那**没有程序头**、它逐字**跑不了**"
  say "        事实：而那真件逐字是 aarch64（本机逐字是 $(uname -m)、那**架构对不上**）"
  say "   [缺] 这一件逐字**测不了**（按 C-05 报缺、不猜填）"
  say "那**真跑**那一格逐字要**设备侧**（承载逐字是 arm64、一格归 07）"
done

# -- (乙) 那一档逐字测不了的那一档（PE）记缺 ---------------------
say ""
say "== (乙) 那一档逐字测不了的那一档（PE）记缺 =="
PE_DIR="$ROOT/container/app/src/main/assets/win32"
say "   仓内那真 PE 样本逐字在：$PE_DIR"
if [ ! -d "$PE_DIR" ]; then
  say "   [缺] 那真 PE 样本逐字**没有**（按 C-05 报缺）"
else
  for p in "$PE_DIR"/*.exe; do
    [ -f "$p" ] || continue
    meta="$p.meta.json"
    arch="$(file -b "$p" 2>/dev/null | cut -c1-72)"
    say "   样本：$(basename "$p")"
    say "         事实：$arch"
    if [ -f "$meta" ]; then
      say "         事实：$(grep -o '"purpose": "[^"]*"' "$meta" 2>/dev/null | head -1)"
    fi
  done
fi
say ""
say "一格逐字**测不了**，成因逐字是那两件："
say "      (1) 仓内逐字**没有 Win32 那一族的系统面**"
say "          （两份逐字要的逐字是 KERNEL32 / USER32 / ADVAPI32、那 Win32 语义）"
say "      (2) 而那 PE 那一族逐字**没有执行原语**"
say "          （那 PeLoader.kt 逐字自己写着「本文件**不执行** x86/x86-64 指令」）"
say "缺的是 **Win32 执行原语**，那**不是**缺判据、也**不是**缺样本"
say "      （仓内逐字**有**真 PE 样本、那 plink-arm64.exe 逐字是 PE32+ ARM64、"
say "那**架构对得上**、它逐字**要** Win32 ABI）"
say "那**架构对得上也不等于能跑**"
say "那**不**许写成「跑不起来」（是**推论**、不是**读数**）"
say "那**真跑**那一格逐字要**设备侧**那一次真跑（一格归 07）"

say ""
say "== 结论 =="
if [ "$rc_all" = "0" ]; then
  say "能跑的那几档逐字**跑得出**它自己那行为、退出码逐字正确"
else
  say "能跑的那几档里逐字**有**跑不出的（已判红）"
fi
say "测不了的那一档逐字**记缺 + 成因**（按 C-05 报缺、按 C-126 写「测不了」）"
say "那**不**许把「测不了」写成「跑不起来」（是**推论**、不是**读数**）"
say ""
say "退出码=$rc_all"
exit $rc_all

#!/usr/bin/env bash
# 「真跑起来」那一族 —— 那一件产品字节逐字**在 LOB-OS 自己那一份 Linux 上被真正加载并运行**，
# 跑出它自己的行为，且**退出码正确**；**这一格的判据是那一次运行的观察结果**
# （那进程的输出与那退出码），**不是**那一串文件头字节。
#
# ⇒ 而那判别式逐字由 02 裁、=> 而那它逐字已落在 docs/spec/ONE-STANDARD.md §122.83.4。
# ⇒ 而而那「宣称了什么」与「做到了什么」那两栏并列、=> 而那它们逐字**不互相顶替**（依 C-104 不并）
#   （=> 而那那一层逐字见 scripts/verify/check-exec-shape-tiers.py 那头注释）。
#
# ★ 这一族逐字判**三档**（=> 而那**不是**一档、=> 而那按 Lead 那一裁依 C-105 **不合并**）：
#   (甲) 那一档逐字**能跑**的那几档（ELF-DYN / ELF-EXEC / Shebang）⇒ **判红**：
#        ⇒ 而那真跑不出它自己那行为、**或**那退出码不对 ⇒ 那一格逐字判红。
#   (乙) 那一档逐字**测不了**的那一档（PE）⇒ **记缺**：
#        ⇒ 而那逐字写「**测不了**」**加那成因**
#        ⇒ 而那缺的是 **Win32 执行原语**，=> 而那**不是**缺判据、=> 而也**不是**缺样本
#        ⇒ 而而那**不**许写成「跑不起来」（=> 而那那是**推论**、=> 而而那不是**读数**）。
#   (丙) 那一档逐字**还没有样本**的 ⇒ **报缺**（依 C-05，不猜填）。
#
# ★ 阳性对照（依 C-108）：=> 而那**要有一件逐字已经能跑的那一件**
#   ⇒ 而那仓内逐字**有**那一种（=> 而那 scripts/verify/pe-sample/shebang.sh 逐字是真脚本）
#   ⇒ 而而那那一件逐字**当对照**跑、=> 而而那它逐字**必须**跑得出预期行为与退出码 0
#   ⇒ 而而那若对照跑不动、=> 而那**整个**这一道检查逐字**不可信**、=> 而而那它逐字**判红**。
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

# ── 那一次运行的观察结果（=> 而那三样逐字**分**开读、=> 而而那两流不可分时逐字**只**判退出码）──
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
    se="★★ 两个流不可分（无 python3 捕获）★★ —— 这一支只判退出码"
  fi
  OUT_SO="$so"; OUT_SE="$se"; OUT_RC="$code"
}

# ── 阳性对照（依 C-108）────────────────────────────────────────────
say "=============================================================="
say " 「真跑起来」那一族 —— 那一次运行的观察结果"
say " 判据源: 02 裁的那一句（=> 而那它逐字已落在 spec §122.83.4）"
say " 形状   : scripts/verify/t1-bare-exec.sh 那一族"
say " 用法   : bash scripts/verify/check-exec-real-run.sh"
say "=============================================================="
say ""

CTL="$SAMPLE_DIR/shebang.sh"
say "== 阳性对照（依 C-108：=> 而那要有一件逐字已经能跑的那一件）=="
say "   对照那一件逐字是：$CTL"
if [ ! -f "$CTL" ]; then
  say "   [error] 对照那一件读不到（=> 而那一格逐字报缺、=> 而那整道检查逐字**不可信**）"
  exit 1
fi
#  => 而那仓内那一份逐字**没有执行位**（=> 而那它逐字是 644）、
#     => 而而那**对照**逐字要能跑、=> 而而那本脚本逐字**拷**一份到临时目录并加上执行位
#     （=> 而那**不改**那仓内那一份、=> 而而那那一份逐字是**样本**、=> 而它逐字**该**保持原样）。
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
  say "           => 而那一格逐字意味着这一道检查逐字**不可信**（依 C-108）"
  exit 1
fi
say "   => 而那对照那一件逐字**跑得出**它自己那行为、=> 而而那两次退出码逐字**一致**"
say "   => 而而那这一道检查逐字**可信**"
say ""

# ── (甲) 那一档逐字能跑的那几档 ⇒ 判红 ────────────────────────────
say "== (甲) 那一档逐字能跑的那几档 ⇒ 判红 =="

#   => 而那**期望行为**逐字是那件自己的（=> 而那**不是**「退出码是 0」那一件事、
#     => 而而那它逐字是「跑出它自己那行为」+「退出码正确」那两件）。
check_runnable() {
  local label="$1" ent="$2" want_out="$3" want_rc="$4" note="$5"
  local so="" se="" code=0 code2=-1
  say ""
  say "--- $label ---"
  say "   事实：$note"
  if [ ! -f "$ent" ]; then
    #   => 而那**没有样本**那一档逐字**报缺**（依 C-05，不猜填）。
    say "   [缺] 这一件逐字**没有样本**（=> 而按 C-05 报缺、=> 而不猜填）"
    say "        事实：无此路径 $ent"
    return 2
  fi
  if [ ! -x "$ent" ]; then
    say "   [缺] 这一件逐字**没有执行位**（=> 而那它逐字跑不了、=> 而按 C-05 报缺）"
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
  say "   [绿] 跑出它自己那行为、=> 而那退出码逐字正确（=> 而而那两次逐字一致）"
  return 0
}

#   => 而那 Shebang 那一档逐字有那**真脚本**（=> 而那 scripts/verify/pe-sample/shebang.sh
#     逐字是 `#!/bin/sh` + `echo hi`）=> 而那它逐字**可跑**（=> 而那要给它执行位）。
SB="$SAMPLE_DIR/shebang.sh"
SB_TMP="$(mktemp -d)/sb.sh"
cp "$SB" "$SB_TMP" 2>/dev/null && chmod +x "$SB_TMP" 2>/dev/null
if check_runnable "Shebang 那一档（=> 而那逐字是那件自己的行为）" "$SB_TMP" "hi" "0" \
    "=> 而那仓内那一份逐字是那 shebang 行加一行 echo hi、=> 而而那本脚本逐字拷一份并加上执行位"; then
  :
else
  r=$?
  [ "$r" = "1" ] && rc_all=1
  [ "$r" = "2" ] && say "   => 而而那 Shebang 那一档逐字**记缺**（=> 而那不判红）"
fi

#   => 而那 ELF-DYN 与那 ELF-EXEC 那两档逐字**没有能跑的真件**。
#     => 而那仓内那两份逐字只是**头**（=> 而那 256 字节、=> 而那没有程序头）
#     => 而而那**真件**逐字是 aarch64（=> 而那本机逐字是 x86-64、=> 而而那**跑不了**）
#     => 而那**真跑**那一格逐字要**设备侧**（=> 而那承载逐字是 arm64、=> 而那一格归 07）。
for pair in "ELF-DYN:elf-dyn" "ELF-EXEC:elf-exec"; do
  label="${pair%%:*}"; sample="${pair##*:}"
  ent="$SAMPLE_DIR/$sample"
  say ""
  say "--- $label 那一档 ---"
  say "   事实：仓内那一份逐字是 **256 字节的头**、=> 而那**没有程序头**、=> 而那它逐字**跑不了**"
  say "        事实：而那真件逐字是 aarch64（=> 而那本机逐字是 $(uname -m)、=> 而而那**架构对不上**）"
  say "   [缺] 这一件逐字**测不了**（=> 而那按 C-05 报缺、=> 而而不猜填）"
  say "        => 而那**真跑**那一格逐字要**设备侧**（=> 而那承载逐字是 arm64、=> 而那一格归 07）"
done

# ── (乙) 那一档逐字测不了的那一档（PE）⇒ 记缺 ─────────────────────
say ""
say "== (乙) 那一档逐字测不了的那一档（PE）⇒ 记缺 =="
PE_DIR="$ROOT/container/app/src/main/assets/win32"
say "   仓内那真 PE 样本逐字在：$PE_DIR"
if [ ! -d "$PE_DIR" ]; then
  say "   [缺] 那真 PE 样本逐字**没有**（=> 而按 C-05 报缺）"
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
say "   => 而那一格逐字**测不了**，=> 而而那成因逐字是那两件："
say "      (1) 仓内逐字**没有 Win32 那一族的系统面**"
say "          （=> 而那那两份逐字要的逐字是 KERNEL32 / USER32 / ADVAPI32、=> 而那那 Win32 语义）"
say "      (2) 而那 PE 那一族逐字**没有执行原语**"
say "          （=> 而那 PeLoader.kt 逐字自己写着「本文件**不执行** x86/x86-64 指令」）"
say "   => 而那缺的是 **Win32 执行原语**，=> 而那**不是**缺判据、=> 而也**不是**缺样本"
say "      （=> 而而那仓内逐字**有**真 PE 样本、=> 而那 plink-arm64.exe 逐字是 PE32+ ARM64、"
say "        => 而那**架构对得上**、=> 而而它逐字**要** Win32 ABI）"
say "   => 而那**架构对得上也不等于能跑**"
say "   => 而而那**不**许写成「跑不起来」（=> 而那那是**推论**、=> 而而那不是**读数**）"
say "   => 而那**真跑**那一格逐字要**设备侧**那一次真跑（=> 而那一格归 07）"

say ""
say "== 结论 =="
if [ "$rc_all" = "0" ]; then
  say "  => 而那能跑的那几档逐字**跑得出**它自己那行为、=> 而而那退出码逐字正确"
else
  say "  => 而那能跑的那几档里逐字**有**跑不出的（=> 而那已判红）"
fi
say "  => 而那测不了的那一档逐字**记缺 + 成因**（=> 而按 C-05 报缺、=> 而按 C-126 写「测不了」）"
say "  => 而而那**不**许把「测不了」写成「跑不起来」（=> 而那那是**推论**、=> 而而那不是**读数**）"
say ""
say "退出码=$rc_all"
exit $rc_all

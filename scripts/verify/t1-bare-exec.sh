#!/usr/bin/env bash
# T1 —— 裸名可执行性判据。对一个名字：送一行 → 读回 stdout 与 stderr → 判定。
#
# ★ 读的是两个流。只读 stdout 的判据会把「什么都没说」当成「说清了」——
#   而符号错那一行恰好在 stderr 上，只读 stdout 会假绿。
#
# ★ 判据只取事实，不取声明：
#   落位   files/usr/bin/<名> 在不在、软链还是实体、目标多大
#   权限   owner/group/other 三位有无 x
#   ELF    DT_NEEDED 列出哪些共享库（readelf 带 -W，不带会截断）
#   运行期 实际 exec 一次，拿到退出码、stdout、stderr 三样
#
# ★ 而它的失败原因里不会出现「我们认为」——每一条都指一个可核对的字节或一次读数。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
READELF="${READELF:-llvm-readelf}"
command -v "$READELF" >/dev/null 2>&1 || READELF=readelf

BIN="${LO_BIN:-${LO_FILES:-$ROOT/container/app/src/main/assets}/usr/bin}"
ENT_PREFIX="${LO_ENT_PREFIX:-}"
rc_all=0

for name in "$@"; do
  echo "=== $name"
  ent="$BIN/$ENT_PREFIX$name"
  if [ ! -e "$ent" ]; then
    echo "  问一 落位在不在：不在（$ent 不存在）"
    echo "  [红] 落位缺"
    echo "       事实：无此路径"
    rc_all=1; continue
  fi
  if [ -L "$ent" ]; then
    echo "  落位：软链 → $(readlink "$ent" 2>/dev/null || echo 不可读)"
  else
    echo "  落位：实体文件 $(wc -c < "$ent" 2>/dev/null || echo 0) 字节"
  fi
  if [ ! -f "$ent" ]; then
    echo "  问一 落位在不在：在（但目标是坏的）"
    echo "  [红] 落位的目标是坏的"
    echo "       事实：软链指向一个不存在的路径"
    rc_all=1; continue
  fi
  perm="$(ls -l "$ent" 2>/dev/null | cut -c1-10)"
  echo "  权限：$perm"
  case "$perm" in
    ?x*|???x*|????x*|?????x*) : ;;
    *) echo "  [红] 执行位缺：$perm"
       echo "       事实：owner/group/other 三位都没有 x"
       rc_all=1; continue ;;
  esac
  need="$("$READELF" -W -d "$ent" 2>/dev/null | sed -n "s/.*NEEDED.*\[\(.*\)\]/\1/p" | tr "\n" " ")"
  so=""
  se=""
  code=0
  SPLIT=0
  ERRF=""
  if [ -z "${T1_NOPY:-}" ] && command -v python3 >/dev/null 2>&1; then
    marked="$(python3 -c "import subprocess,sys; p=subprocess.run([sys.argv[1],'--version'],capture_output=True); sys.stdout.write('SO:'+p.stdout.decode(errors='replace')[:400]+'\n'+'SE:'+p.stderr.decode(errors='replace')[:400]+'\n'+'RC:'+str(p.returncode)+'\n')" "$ent" 2>/dev/null)"
    if printf "%s" "$marked" | grep -q "^RC:"; then
      so="$(printf "%s" "$marked" | sed -n "s/^SO://p" | head -3 | tr "\n" "|")"
      se="$(printf "%s" "$marked" | sed -n "s/^SE://p" | head -3 | tr "\n" "|")"
      code="$(printf "%s" "$marked" | sed -n "s/^RC://p" | head -1)"
      SPLIT=1
    fi
  fi
  if [ "$SPLIT" = "0" ]; then
    both="$("$ent" --version 2>&1)"
    code=$?
    so="$both"
    se="★★ 两个流不可分（无 python3 捕获）★★ —— 这一支只判退出码，"
    se="$se 不可用它判「它说了什么」"
  fi
  echo "  捕获方式：$( [ "$SPLIT" = 1 ] && echo "两个流分开（内存）" || echo "**合并流，只判退出码**" )"
  echo "  运行期 --version 退出码=$code"
  echo "  stdout：$(printf "%s" "$so" | head -2 | tr "\n" "|")"
  echo "  stderr：${se:-（空）}"
  echo "  问一 落位在不在：是在"
  if [ "$code" -eq 0 ]; then
    echo "  问二 能不能跑起来：能"
    echo "  [绿]"
  else
    echo "  问二 能不能跑起来：不能（退出码 $code）"
    echo "  [红] 落位在，而它跑不起来"
    rc_all=1
  fi
done
exit $rc_all
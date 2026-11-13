#!/usr/bin/env bash

set -uo pipefail

ZIP="${1:?用法: verify-piece-shape.sh <件包.zip> [期望的 kind]}"
WANT="${2:-}"

ZIP="${ZIP%.zip}.zip"
[ -f "$ZIP" ] || { echo "::error title=没有这个包::$ZIP 不在"; exit 1; }

W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
unzip -qq -o "$ZIP" -d "$W" 2>/dev/null || { echo "::error title=解不开::$ZIP"; exit 1; }

SHAPE="$W/piece-shape.json"
[ -f "$SHAPE" ] || {
  echo "::error title=包里没有形态文件::$ZIP 里没有 piece-shape.json —— 这个包不自带形态，运行期就得靠猜（判据不成立）"
  exit 1
}

FAIL=0
say() { echo "  $1"; }
bad() { echo "::error title=$1::$2"; FAIL=1; }

read_field() {
  local key="$1"
  if command -v node >/dev/null 2>&1; then
    node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{const j=JSON.parse(d);process.stdout.write(String(j[process.argv[1]]||""))}catch(e){}})' "$key" < "$SHAPE"
  else
    python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get(sys.argv[2],""))' "$SHAPE" "$key"
  fi
}

KIND="$(read_field kind)"
ENTRY="$(read_field entry)"
ENTRY_FORM="$(read_field entryForm)"
ENTRY_ROLE="$(read_field entryRole)"
say "kind=$KIND entry=$ENTRY entryForm=$ENTRY_FORM entryRole=$ENTRY_ROLE"

case "$KIND" in
  program|library|mixed) : ;;
  *) bad "形态取值域不对" "kind='$KIND' —— 取值域只有 program / library / mixed / unknown" ;;
esac

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../toolchain" && pwd)/elf-form.sh"

RECOMP=""
if [ -n "$ENTRY" ] && [ -e "$W/$ENTRY" ]; then
  f="$(elf_form "$W/$ENTRY")"
  RECOMP="$f"
  say "现场复算 entry 的形态：$f（形态文件写的是 $ENTRY_FORM）"
  [ "$f" = "$ENTRY_FORM" ] || bad "形态对不上" "包里的字节复算得 $f，而 piece-shape.json 写的是 $ENTRY_FORM"
fi

if [ "$KIND" = "library" ] || [ "$KIND" = "mixed" ]; then
  for so in "$W"/lib/*.so; do
    [ -e "$so" ] || continue
    real="$(readlink -f "$so")"
    [ -f "$real" ] || continue

    f="$(elf_form "$real")"
    if [ "$f" != "dyn-plain" ]; then
      bad "程序形态的字节在库目录下" "lib/$(basename "$so") 的形态是 $f（程序形态）—— 它该落 bin/（形态决定目录），不是 lib/"
      continue
    fi
    base="$(basename "$so")"
    root="$(elf_chain_root "$base")"
    chain="$(elf_chain_names "$W/lib" "$root")"
    n="$(printf '%s' "$chain" | wc -w)"
    say "lib/$base 的链：$chain（$n 段）"

    soname="$(elf_soname "$real")"
    if [ -z "$soname" ]; then

      bad "SONAME 读不到" "lib/$base 那份字节没有 DT_SONAME —— 它自述的 SONAME 是什么读不出来，报缺（不替它编）"
    elif [ "$soname" = "$root" ]; then
      say "  SONAME=$soname 与开发名同名 => 按那个形状就是 $n 段，自洽"
    else

      case " $chain " in
        *" $soname "*) say " SONAME=$soname 在链里，自洽" ;;
        *) bad "链与 SONAME 不自洽" "lib/$base 自述 SONAME=$soname，而链里没有这一层：$chain" ;;
      esac
    fi
  done
fi

if [ "$KIND" = "program" ] || [ "$KIND" = "mixed" ]; then
  for p in "$W"/bin/*; do
    [ -e "$p" ] || continue
    real="$(readlink -f "$p")"
    [ -f "$real" ] || continue
    f="$(elf_form "$real")"
    case "$f" in
      exec|dyn-interp) : ;;
      *) continue ;;
    esac
    mode="$(stat -c%a "$real")"
    case "$mode" in
      ?5?*|????5?) : ;;
      *) bad "程序那份字节没有 owner 执行位" "$(basename "$p") 的权限是 $mode —— 程序的那份字节在构建期就要带 owner 执行位" ;;
    esac
    say "bin/$(basename "$p") 形态=$f 权限=$mode（owner 执行位在）"
  done
fi

if [ -n "$WANT" ] && [ "$WANT" != "$KIND" ]; then
  bad "形态与期望不符" "期望 $WANT，实际 $KIND"
fi

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "::error title=形态判据不成立::$ZIP —— 包不自描述形态"
  exit 1
fi
echo ""
echo "[ok] $ZIP 形态自描述成立（kind=$KIND entryForm=$ENTRY_FORM）"

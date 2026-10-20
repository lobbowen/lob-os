#!/usr/bin/env bash
# 验一个件包的形态：只看包里的 piece-shape.json 与包里的字节，逐条对。
#
# 核心判别式（改完必须成立）：
#   **只看产物本身，不看任何外部表，能不能认出它是库还是程序。**
#
# 判法分两路：
#   路一（只读产物里的那份形态文件）：piece-shape.json 说它是什么
#   路二（只读产物里的字节）：现场用 readelf 复算，与形态文件对账
# 两路一致 ⇒ 这个包自描述成立。
#
# 用法：verify-piece-shape.sh <件包.zip> [期望的 kind]
#   期望的 kind 取值域：program / library / mixed
#
# 缺陷名（C-102）包的形态无人核对：
#   原来打包只验「entry 在不在」「清单里写的 bin/ 名字在不在」，
#   从来没有验过「这个包里那些字节到底是库还是程序」——
#   于是「件是库」还是「件是程序」全靠看目录名，运行期再猜一遍。
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

# 读形态文件（node 在 CI 上一定有；没有就退 python3）
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

# ── 路二：只读包里的字节，现场复算形态，与形态文件对账 ─────────
# 这一路**不看**形态文件 —— 它是独立复算，两路相等才算自描述成立。
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../toolchain" && pwd)/elf-form.sh"

RECOMP=""
if [ -n "$ENTRY" ] && [ -e "$W/$ENTRY" ]; then
  f="$(elf_form "$W/$ENTRY")"
  RECOMP="$f"
  say "现场复算 entry 的形态：$f（形态文件写的是 $ENTRY_FORM）"
  [ "$f" = "$ENTRY_FORM" ] || bad "形态对不上" "包里的字节复算得 $f，而 piece-shape.json 写的是 $ENTRY_FORM"
fi

# 库那一件：检查三段链（ldconfig(8) 的 libfoo.so -> libfoo.so.1 -> libfoo.so.1.12）
if [ "$KIND" = "library" ] || [ "$KIND" = "mixed" ]; then
  for so in "$W"/lib/*.so; do
    [ -e "$so" ] || continue
    real="$(readlink -f "$so")"
    [ -f "$real" ] || continue
    # 只对**库形态**（ET_DYN 无 PT_INTERP）的那份要求三段链。
    # 混合件（busybox/jq）lib/ 下那份是**程序形态**（ET_EXEC 或 ET_DYN+PT_INTERP），
    # 它不是共享库，没有链可言 —— 判据要分清这两件事。
    f="$(elf_form "$real")"
    [ "$f" = "dyn-plain" ] || { say "lib/$(basename "$so") 形态=$f（程序形态，不要求三段链）"; continue; }
    base="$(basename "$so")"
    root="$(elf_chain_root "$base")"
    chain="$(elf_chain_names "$W/lib" "$root")"
    n="$(printf '%s' "$chain" | wc -w)"
    say "lib/$base 的链：$chain（$n 段）"
    # ★ **判据换成「SONAME 与链是否自洽」**（01 今天裁的）——
    #   原来这一格写死了「段数 >= 3」，而那个 3 是我们自己加的一个数，不是 man 8 ldconfig 的要求：
    #   man 原文是「expects a certain pattern」（期待某种形状）与「may result in」（可能导致），
    #   而它那个示例里的「middle file」明确就是**那份字节自己说的 SONAME**。
    #   ⇒ 判别：这一份字节的 SONAME 与它的开发名同名吗？
    #      同名（libssl.so / libcrypto.so / libcurl.so）⇒ 按那个形状就是 1 段，**而这不是缺陷**
    #      不同名（libz.so -> libz.so.1）⇒ 中间那层必须是 SONAME，链里没有就是自相矛盾
    soname="$(elf_soname "$real")"
    if [ -z "$soname" ]; then
      # 读不到 SONAME ⇒ 那份字节没自说它叫什么 ⇒ 按 C-05 报缺，不替它编
      bad "SONAME 读不到" "lib/$base 那份字节没有 DT_SONAME —— 它自述的 SONAME 是什么读不出来，报缺（不替它编）"
    elif [ "$soname" = "$root" ]; then
      say "  SONAME=$soname 与开发名同名 ⇒ 按那个形状就是 $n 段，自洽"
    else
      # SONAME 与开发名不同名 ⇒ 链里必须有一层就叫 SONAME
      case " $chain " in
        *" $soname "*) say "  SONAME=$soname 在链里，自洽" ;;
        *) bad "链与 SONAME 不自洽" "lib/$base 自述 SONAME=$soname，而链里没有这一层：$chain" ;;
      esac
    fi
  done
fi

# 程序那一件：检查 owner 执行位（构建期就要带上）
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

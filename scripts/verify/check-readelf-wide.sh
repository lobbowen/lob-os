    case "$line" in *" -W"*|*"--wide"*) continue ;; esac
# C-05 形态声明：凡「查某符号是否存在」的地方，输出形态必须完整。
#
# 负例样本是真实故障，不是构造的（Lead 2026-10-16 本会话那次）：
#   $ readelf --dyn-syms    <file>   →  OSSL_CMP_ITAV_dup 计数 0
#   $ readelf --dyn-syms -W <file>   →  同一符号计数 1
# 同一个文件、同一个符号，只因为少了 -W 就报「不存在」，
# 而据此得出的结论是「打包把两次构建放进了一个包」——**一条由输出格式造成的假结论**。
#
# 判别形式：我用的工具，会不会因为输出格式而漏报？会（列宽截断/分页）⇒ 否定结论不可信。
#
# 而「查不到」与「查不到的方式不可靠」必须分开报告。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

# 需要 -W 的形态：readelf / nm / objdump 的「列符号」类子命令。
# nm 的等价开关是 -D（动态符号）或不加（全部）—— 它默认就不截断，所以不强制。
TOTAL=0
BAD=0
NM_MISSING=0

while read -r fl; do
  f="${fl%%:*}"
  ln="${fl#*:}"; ln="${ln%%:*}"
  [ -f "$f" ] || continue
  line="$(sed -n "${ln}p" "$f" 2>/dev/null)"
  case "$line" in
    *"#"*) continue ;;
    *READELF*|*readelf*) ;;
    *nm*|*NM*|*Nm*) ;;
    *) continue ;;
  esac
  case "$line" in
    *nm*|*NM*|*Nm*)
      case "$line" in
        *" -D"*|*"--dynamic"*) TOTAL=$((TOTAL+1)); continue ;;
        *) TOTAL=$((TOTAL+1)); BAD=$((BAD+1)); echo "::error title=nm 未带 -D::$f:$ln  加载器只看 .dynsym，不带 -D 的 nm 会把 .symtab 的也算进来"; echo "::error::  实测 libcrypto.so：nm 见 20263、nm -D 见 5895，差 15727 个是加载器看不到的" ;;
      esac
      continue ;;
  esac
  case "$line" in
    *" -W"*|*"--wide"*) TOTAL=$((TOTAL+1)); continue ;;
  esac
  TOTAL=$((TOTAL+1)); BAD=$((BAD+1))  echo "::error title=readelf 未带 -W::$f:$ln  这行若用于「某符号是否存在」的结论，其「0 处」不可信（列宽会截断）"
  echo "::error::  加 -W，或改用 nm -D —— 而「查不到」与「查不到的方式不可靠」必须分开报告"
done < <(grep -rnE "READELF|llvm-readelf|readelf" scripts/ 2>/dev/null | grep -E "\-d |\-s |--dyn-syms|\-l |\-h |\-V " | grep -v "check-readelf-wide" | head -200)

echo "[c05] 扫到用 readelf 查 ELF 的地方，未带 -W 的有 $BAD 处"
if [ "$TOTAL" -eq 0 ]; then
  echo "::error title=检查了 0 个对象::本判据未生效，状态是【未验证】而不是通过"
  exit 2
fi
[ "$BAD" -eq 0 ] || exit 1
echo "[ok] 用 readelf 查符号的地方都带了 -W"
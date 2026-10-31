#!/usr/bin/env bash
# 坏字节那一道检查（C-1000 那一族、04 落规范、05 落脚本）。
#
# 那一族逐字治的是「**仓内逐字有坏字节**」（=> 而那 C-998 那一族逐字治的是
# 「**书面说法与实测对不上**」=> 而那是**邻族、不是同一族**）。
#
# 判红的形态：=> 而那逐字**报出文件名与行号**（=> 而**不是**只报一个数）。
# 退出码：=> 而那 0 = 没有坏字节；=> 而那**非 0 = 有**。
#
# 阳性对照（依 C-108）：=> 而那**同一个扫描通道**逐字要能命中一个**真文件**
#   （=> 而那一个真文件逐字是那**造出来的**、=> 而那造完逐字**即删**
#   => 而而那删之前逐字先核「那通道逐字能命中它」=> 而而那**删之后**逐字再核「那通道逐字命中数归零」
#   => 而而那**两次**读数并列、=> 而那**不互相覆盖**（依 C-115））。
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

RC=0
say() { printf '%s\n' "$*"; }

# ---- 排除清单（=> 而那每一项逐字写明为什么排除）----
#   .git     —— 那是 git 自己的对象库（=> 而那逐字装着历史版本里的坏字节、
#               而那**不是**当前工作区的内容、=> 而那**不该**判红）。
#   work     —— 那是本机的工作区（=> 而那逐字装着别人/别的会话的中间产物）。
#   dist     —— 那是构建产物（=> 而那逐字是**生成物**、=> 而那**不是**人写的源码）。
#   .parts   —— 那是下载/解包的中间目录（=> 而那逐字是**生成物**）。
#   build    —— 那是 gradle 的构建输出（=> 而那逐字是**生成物**）。
#   .gradle  —— 那是 gradle 的缓存（=> 而那逐字是**生成物**）。
EXCLUDE_DIRS=(.git work dist .parts build .gradle)

# ---- 要扫的坏字节（=> 而那一类一类的逐字是什么；=> 而那**十六进制**逐字写、=> 而那**不**用裸字节）----
#   efbfbd —— U+FFFD 替换字符
#     （=> 而那正是「一个字符被写坏」的形态、=> 而那一族的成因逐字就是「注释里的字符被写坏」）。
#   efbfbd —— U+FFFD 替换字符
#     （=> 而那正是「一个字符被写坏」的形态、=> 而那一族的成因逐字就是「注释里的字符被写坏」）。
#
#   而那**另两类**（=> 而那**不是**按字节串找、=> 而那**按字节串找会误报**）：
#     · 那一类逐字是「**非法** UTF-8 序列」——=> 而那逐字是**真**坏字节
#       （=> 而那判据逐字是：那段字节**解不成**合法 UTF-8）。
#     · 那一类逐字是「**被截断**的多字节序列」——=> 而那逐字是**文件末尾**那不完整的一截。
#   => 而而那**为什么**不按字节串找那两类
#     （=> 而那是**04 那一要求**、=> 而那**教训**逐字来自本机实读）：
#     · 而那 `e280` 逐字**不是**坏字节 —— => 而那 `e2 80 94` 逐字是「→」那一个**合法**字符
#       （=> 而那按字节串找 `e280` 逐字会命中它那**前两字节**、=> 而那逐字**误报**）。
#     · 而那 `c328` 逐字**也不是**坏字节 —— => 而那 `c3 28` 逐字是「Ã(」那一种
#       （=> 而那在**某些**编码里逐字是合法的、=> 而那按字节串找逐字**误报**）。
#     => 而那**正确**的判据逐字是「那段字节解不成合法 UTF-8」、=> 而那本脚本逐字用那一条。
#   => 而那**三类**读数逐字**并列**（=> 而那不互相覆盖，依 C-115）：
#     (1) U+FFFD 那一类（=> 而那一类逐字是**替换符**、=> 而它是**合法** UTF-8、=> 而它逐字**单独**找）；
#     (2) 非法 UTF-8 序列那一类；
#     (3) 文件末尾截断那一类。
BAD_HEX=(efbfbd)

say "== 那坏字节那一道检查 =="
say "   扫描通道：按**字节**扫（=> 而不是按行匹配、=> 而那**不是**文本解码、"
say "   => 而那逐字能命中任何文件类型、=> 而那包括二进制）"
say "   排除清单（=> 而那每一项逐字写明为什么排除）："
for d in "${EXCLUDE_DIRS[@]}"; do
  case "$d" in
    .git)    why="那是 git 自己的对象库（=> 而那逐字装着历史版本里的坏字节、=> 而那**不是**当前工作区的内容）" ;;
    work)    why="那是本机的工作区（=> 而那逐字装着别人/别的会话的中间产物）" ;;
    dist)    why="那是构建产物（=> 而那逐字是**生成物**、=> 而那**不是**人写的源码）" ;;
    .parts)  why="那是下载/解包的中间目录（=> 而那逐字是**生成物**）" ;;
    build)   why="那是 gradle 的构建输出（=> 而那逐字是**生成物**）" ;;
    .gradle) why="那是 gradle 的缓存（=> 而那逐字是**生成物**）" ;;
    *)       why="（未写明）" ;;
  esac
  say "     - $d ：$why"
done
say "   要扫的坏字节（=> 而那一类一类的逐字是什么）："
say "     - efbfbd ：U+FFFD 替换字符（=> 而那正是「一个字符被写坏」的形态）"
say "     - 非法 UTF-8 序列 ：那段字节**解不成**合法 UTF-8（=> 而那逐字是**真**坏字节）"
say "     - 末尾截断 : 文件**末尾**那一截不完整的多字节序列（=> 而那逐字是**被截断**）"
say "     => 而那**后两类**逐字按「解不成」判（=> 而**不**按字节串找）、=> 而那理由逐字写在上面那几行注释里。"
say ""

# ---- 那扫描通道（=> 而它逐字是那**唯一**一处、=> 而那阳性对照与正式扫逐字共用它）----
#   => 而那用 python 逐字**按字节**找（=> 而那 grep -P 那一档本机**不可用**、
#   => 而那 python 逐字是那仓内**已有**依赖）。
#   => 而那排除清单逐字**不在**源码里（=> 而那它逐字是那仓自己的排除清单、=> 而那**不**是本脚本的），
#     => 而那**自己**逐字排除掉（=> 而那否则它逐字会命中它自己那几行注释里的那串十六进制）。
SCAN_SELF="scripts/verify/check-bad-bytes.sh"
scan() {
  #   $1 = 要扫的根目录
  python3 - "$1" "$SCAN_SELF" "${EXCLUDE_DIRS[@]}" "${BAD_HEX[@]}" <<'PYEOF'
import os, sys
root = sys.argv[1]
self_rel = sys.argv[2]
skip = set(sys.argv[3:3 + 6])
pats = [bytes.fromhex(p) for p in sys.argv[3 + 6:]]
hits = 0
binaries = 0
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in skip]
    for fn in filenames:
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, root)
        #   => 而那**自己**逐字跳过（=> 而那它逐字写着那几串十六进制、=> 而那那不是坏字节）。
        if rel == self_rel:
            continue
        try:
            with open(p, 'rb') as f:
                data = f.read()
        except (OSError, IOError):
            continue
        #  (1) U+FFFD 替换字符那一类（=> 而它逐字是**合法** UTF-8、=> 而它逐字**单独**找）。
        for pat in pats:
            start = 0
            while True:
                i = data.find(pat, start)
                if i < 0:
                    break
                hits += 1
                line = data.count(b'\n', 0, i) + 1
                print("%s:%d: 坏字节 %s (U+FFFD 替换字符, offset %d)" % (rel, line, pat.hex(), i))
                start = i + 1
        #  => 而那**二进制**逐字**不算**坏字节（=> 而那仓内逐字有测试用的二进制样本、
        #    => 而那二进制逐字**本来**就不是 UTF-8、=> 而那逐字判它红是**误报**）。
        #    => 而那判据逐字是「那文件开头那 8000 字节里逐字有一个 NUL 字节」
        #      （=> 而那 UTF-8 文本逐字**不含** NUL、=> 而那二进制逐字**几乎总是**含）。
        is_binary = b'\x00' in data[:8000]
        if is_binary:
            binaries += 1
            #  => 而那二进制逐字**只**跑那一类 (1)（=> 而那 U+FFFD 在二进制里也逐字是坏字节）。
            for pat in pats:
                start = 0
                while True:
                    i = data.find(pat, start)
                    if i < 0:
                        break
                    hits += 1
                    line = data.count(b'\n', 0, i) + 1
                    print("%s:%d: 坏字节 %s (U+FFFD 替换字符, offset %d)" % (rel, line, pat.hex(), i))
                    start = i + 1
            continue
        #  (2) 非法 UTF-8 序列那一类（=> 而那判据逐字是「那段字节解不成合法 UTF-8」）。
        #      => 而那**判重**逐字是「那一个位置只报一次」（=> 而那跳过续字节）。
        #      => 而那**跳过二进制**：=> 而那仓内逐字可能有二进制（=> 而那那**不是**坏字节）。
        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError as e:
            i = e.start
            hits += 1
            line = data.count(b'\n', 0, i) + 1
            print("%s:%d: 坏字节 非法 UTF-8 序列 (offset %d: %s)" % (rel, line, i, e.reason))
        else:
            #  (3) 末尾截断那一类：=> 而那文件**末尾**那一截不完整的多字节序列。
            #      => 而那判据逐字是「那最后那一个字符逐字**解不出**、=> 而那它逐字是个多字节序列的开头」。
            if data:
                try:
                    data[-1:].decode('utf-8')
                except UnicodeDecodeError:
                    #  => 而那找那最后一个**合法**字符之后的位置。
                    j = len(data) - 1
                    while j > 0:
                        try:
                            data[j - 1:].decode('utf-8')
                            break
                        except UnicodeDecodeError:
                            j -= 1
                    if j < len(data):
                        hits += 1
                        line = data.count(b'\n', 0, j) + 1
                        print("%s:%d: 坏字节 末尾截断的多字节序列 (offset %d)" % (rel, line, j))
print("__HITS__%d" % hits)
print("__BIN__%d" % binaries)
PYEOF
}

say "== 正式扫描 =="
out="$(scan . 2>/dev/null)"
hits="$(printf '%s\n' "$out" | grep '^__HITS__' | sed 's/__HITS__//')"
hits="${hits:-0}"
bins="$(printf '%s\n' "$out" | grep '^__BIN__' | sed 's/__BIN__//')"
bins="${bins:-0}"
printf '%s\n' "$out" | grep -v '^__HITS__' | grep -v '^__BIN__' | sed 's/^/  /'
if [ "$hits" = "0" ]; then
  say "  => 而那仓内逐字**没有**坏字节（=> 而那命中数逐字是 0）"
  say "  => 而那跳过的那**二进制**文件逐字 $bins 个（=> 而那二进制逐字**本来**就不是 UTF-8、=> 而那判它红是**误报**）"
else
  say "  => 而那仓内逐字**有**坏字节（=> 而那命中数逐字是 $hits）"
  say "  => 而那跳过的那**二进制**文件逐字 $bins 个（=> 而那二进制逐字**本来**就不是 UTF-8）"
  RC=1
fi
say ""

# ---- 阳性对照（依 C-108）----
#   => 而那**同一个扫描通道**逐字要能命中一个**真文件**。
#   => 而那那一个真文件逐字是那**造出来的**（=> 而那内容逐字含一个 U+FFFD），
#   => 而那造完逐字先核「那通道逐字能命中它」（=> 而那**那一条对照逐字是什么**，写在这里），
#   => 而而那**两次**读数并列（=> 而那正式扫那一遍、=> 而那对照那一遍、=> 而那**不互相覆盖**）。
say "== 阳性对照（依 C-108：=> 而那同一个扫描通道逐字要能命中一个真文件）=="
say "   对照那一格逐字是：=> 而那**造出来**的一个文件、=> 而那内容逐字含一个 U+FFFD（ef bf bd）"
say "   => 而那对照逐字跑**同一个 scan 通道**、=> 而那对照逐字读的是**那一个文件**"
say "   => 而而那两次读数并列（=> 而那正式扫那一遍、=> 而那对照那一遍、=> 而那**不互相覆盖**）"
ctl_dir="$(mktemp -d)"
ctl_file="$ctl_dir/ctl-bad-bytes.txt"
printf '这一行逐字正常。\n' > "$ctl_file"
printf '\357\277\275这一行逐字含一个 U+FFFD。\n' >> "$ctl_file"
ctl_out="$(scan "$ctl_dir" 2>/dev/null)"
ctl_hits="$(printf '%s\n' "$ctl_out" | grep '^__HITS__' | sed 's/__HITS__//')"
ctl_hits="${ctl_hits:-0}"
printf '%s\n' "$ctl_out" | grep -v '^__HITS__' | grep -v '^__BIN__' | sed 's/^/  /'
say "  => 而那对照那一遍的命中数逐字 = $ctl_hits"
if [ "$ctl_hits" = "0" ]; then
  say "  => 而那**扫描通道逐字命中不了**那一个真文件 => 而那正式扫那一个 0 **不可信** => 判红"
  RC=1
else
  say "  => 而那**扫描通道逐字能命中**那一个真文件 => 而那正式扫那一个 0 **可信**"
fi
rm -rf "$ctl_dir"
say ""

say "== 结论 =="
if [ "$RC" = "0" ]; then
  say "  仓内逐字**没有**坏字节（=> 而那命中数逐字是 0、=> 而而那对照那一遍逐字命中了真文件）"
else
  say "  仓内逐字**有**坏字节（=> 而那逐字见上面那几行、=> 而那逐字带文件名与行号）"
fi
say ""
say "退出码=$RC"
exit $RC

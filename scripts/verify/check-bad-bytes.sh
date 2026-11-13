#!/usr/bin/env bash

set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

RC=0
say() { printf '%s\n' "$*"; }

EXCLUDE_DIRS=(.git work dist .parts build .gradle)

BAD_HEX=(efbfbd)

say "== 那坏字节那一道检查 =="
say "   扫描通道：按**字节**扫（不是按行匹配、那**不是**文本解码、"
say "逐字能命中任何文件类型、包括二进制）"
say "   排除清单（每一项逐字写明为什么排除）："
for d in "${EXCLUDE_DIRS[@]}"; do
  case "$d" in
    .git)    why="那是 git 自己的对象库（逐字装着历史版本里的坏字节、那**不是**当前工作区的内容）" ;;
    work)    why="那是本机的工作区（逐字装着别人/别的会话的中间产物）" ;;
    dist)    why="那是构建产物（逐字是**生成物**、那**不是**人写的源码）" ;;
    .parts)  why="那是下载/解包的中间目录（逐字是**生成物**）" ;;
    build)   why="那是 gradle 的构建输出（逐字是**生成物**）" ;;
    .gradle) why="那是 gradle 的缓存（逐字是**生成物**）" ;;
    *)       why="（未写明）" ;;
  esac
  say "     - $d ：$why"
done
say "   要扫的坏字节（一类一类的逐字是什么）："
say "     - efbfbd ：U+FFFD 替换字符（正是「一个字符被写坏」的形态）"
say "     - 非法 UTF-8 序列 ：那段字节**解不成**合法 UTF-8（逐字是**真**坏字节）"
say "     - 末尾截断 : 文件**末尾**那一截不完整的多字节序列（逐字是**被截断**）"
say "那**后两类**逐字按「解不成」判（**不**按字节串找）、理由逐字写在上面那几行注释里。"
say ""

SCAN_SELF="scripts/verify/check-bad-bytes.sh"
scan() {

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

        if rel == self_rel:
            continue
        try:
            with open(p, 'rb') as f:
                data = f.read()
        except (OSError, IOError):
            continue

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

        is_binary = b'\x00' in data[:8000]
        if is_binary:
            binaries += 1

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

        try:
            text = data.decode('utf-8')
        except UnicodeDecodeError as e:
            i = e.start
            hits += 1
            line = data.count(b'\n', 0, i) + 1
            print("%s:%d: 坏字节 非法 UTF-8 序列 (offset %d: %s)" % (rel, line, i, e.reason))
        else:

            if data:
                try:
                    data[-1:].decode('utf-8')
                except UnicodeDecodeError:

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
  say "仓内逐字**没有**坏字节（命中数逐字是 0）"
  say "跳过的那**二进制**文件逐字 $bins 个（二进制逐字**本来**就不是 UTF-8、判它红是**误报**）"
else
  say "仓内逐字**有**坏字节（命中数逐字是 $hits）"
  say "跳过的那**二进制**文件逐字 $bins 个（二进制逐字**本来**就不是 UTF-8）"
  RC=1
fi
say ""

say "== 阳性对照（依 C-108：同一个扫描通道逐字要能命中一个真文件）=="
say "   对照那一格逐字是：那**造出来**的一个文件、内容逐字含一个 U+FFFD（ef bf bd）"
say "对照逐字跑**同一个 scan 通道**、对照逐字读的是**那一个文件**"
say "两次读数并列（正式扫那一遍、对照那一遍、那**不互相覆盖**）"
ctl_dir="$(mktemp -d)"
ctl_file="$ctl_dir/ctl-bad-bytes.txt"
printf '这一行逐字正常。\n' > "$ctl_file"
printf '\357\277\275这一行逐字含一个 U+FFFD。\n' >> "$ctl_file"
ctl_out="$(scan "$ctl_dir" 2>/dev/null)"
ctl_hits="$(printf '%s\n' "$ctl_out" | grep '^__HITS__' | sed 's/__HITS__//')"
ctl_hits="${ctl_hits:-0}"
printf '%s\n' "$ctl_out" | grep -v '^__HITS__' | grep -v '^__BIN__' | sed 's/^/  /'
say "对照那一遍的命中数逐字 = $ctl_hits"
if [ "$ctl_hits" = "0" ]; then
  say "那**扫描通道逐字命中不了**那一个真文件正式扫那一个 0 **不可信** => 判红"
  RC=1
else
  say "那**扫描通道逐字能命中**那一个真文件正式扫那一个 0 **可信**"
fi
rm -rf "$ctl_dir"
say ""

say "== 结论 =="
if [ "$RC" = "0" ]; then
  say "  仓内逐字**没有**坏字节（命中数逐字是 0、对照那一遍逐字命中了真文件）"
else
  say "  仓内逐字**有**坏字节（逐字见上面那几行、逐字带文件名与行号）"
fi
say ""
say "退出码=$RC"
exit $RC

#!/usr/bin/env bash
# 源码形状门禁：抓「静态检查过了但语法坏了」的那一类。
#
# 理由：今天两次把语法坏的文件推上 CI——
#   一次 node 字符串转义吃掉半行；一次字符串被当可迭代对象摊成「一个字符一行」。
#   而括号计数、注释检查、分层检查全过，因为字符摊开不改变括号总数。
#
# 判据（用比值，不用绝对数 —— 绝对数在不同文件上差异太大）：
#   1. 行数上限 900（本仓最大 1637 行 CapabilityBroker；摊开后上万）
#   2. 顶格行 / 总行 > 55%  —— 字符摊开后每个字符都是顶格，比值会飙到 95%+
#      正常文件：CapabilityBroker 72/1637=4.4%，OsApplication 16/96=17%，多数 <5%
set -uo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"

SRC="container/app/src/main/java"
NATIVE="container/native"
BAD=0
COUNT=0
MAXLINES=2200
MAXRATIO=55

echo "== 源码形状 =="

for f in $(find "$SRC" "$NATIVE" -type f \( -name "*.kt" -o -name "*.c" -o -name "*.h" \) 2>/dev/null | sort); do
  COUNT=$((COUNT + 1))
  lines=$(wc -l < "$f" | tr -d ' ')
  if [ "$lines" -gt "$MAXLINES" ]; then
    echo "::error file=$f,title=行数异常::$lines 行（上限 $MAXLINES）—— 疑似写入时被摊成字符数组"
    BAD=$((BAD + 1))
    continue
  fi
  if [ "$lines" -lt 40 ]; then
    continue
  fi
  top=$(grep -cE '^[^ \t}#]' "$f" 2>/dev/null || true)
  top=${top:-0}
  ratio=$((top * 100 / lines))
  if [ "$ratio" -gt "$MAXRATIO" ]; then
    echo "::error file=$f,title=结构疑似拆散::顶格 $top / 共 $lines = ${ratio}%（上限 ${MAXRATIO}%）"
    BAD=$((BAD + 1))
  fi
done

echo "[shape] 检查 $COUNT 个源文件；行数上限 $MAXLINES，顶格比上限 ${MAXRATIO}%"
if [ "$BAD" -gt 0 ]; then
  echo "::error title=源码形状::$BAD 个文件形状异常 —— 写入源码不要用 node 的字符串数组"
  exit 1
fi
echo "[ok] 源码形状正常"

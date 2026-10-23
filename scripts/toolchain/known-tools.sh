#!/usr/bin/env bash
# 「本脚本认的件名」—— 而它从 bucket_for 的 case 里**现算**（01 今天 S93 裁的：
#    那一处删掉，而它的内容改成从  现算）。
#
# 而它原来只是一份**抄本**（cache-key.sh 里那一句注释自己写着「从 deps_for 与
#   bucket_for 的 case 里抄出来的」），而它唯一的读者是那一句错误文案。
#   => 而它不是第三份真源（04 今天那条：算出来的它是上游，抄出来的它该被删掉并改成现算）
#
# 而取法：base 那一档的分支名就是「哪些项是件」那八项（04 今天给的取法），
#   而 case 的分支形如  a|b|c)   echo "筐" ;;  => 而 awk 取第一个 ) 之前的那些名字。
#
# 而它不写死任何名字：改了 case，这一份跟着变。
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cache_key="$here/cache-key.sh"

sed -n '/^bucket_for()/,/^}/p' "$cache_key" | awk '
  /echo "base"/ {
    if (match($0, /^[^()]*\)/)) {
      s = substr($0, 1, RSTART + RLENGTH - 2)
      gsub(/[|[:space:]]/, " ", s)
      printf "%s ", s
    }
  }
' | tr -s ' ' | sed 's/^ *//; s/ *$//'

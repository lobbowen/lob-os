#!/usr/bin/env bash
# 读那 ELF 的那 e_type 与那 PT_INTERP 那两个头，报那**实际形态**。
#
# 按 C-103：=> 而那**两边**逐字**各**答**一件**事**、=> 而而那**处置**逐字**不合并**。
#   => 而那**形态**这一族逐字**答**的是「那字节**是**哪一种**形态**」。
#   => 而那**设备上**现装**的**那一份**是**哪**一形**逐字**是**那**别**的**一件**事**（=> 而那换归 07）。
#
# 按 C-105：那**缺**的逐字**报缺**，不猜填。
# 用法：read-elf-form.sh <那 ELF>  =>  逐字**输出**那形态**那一族**的一个**名字**
#   取值域：exec / dyn-interp / dyn-plain / shebang / unknown
#
# 按 C-104：那**形态**那一族逐字**只有**一条路（=> 而那 elf-form.sh **就是**它**）。
set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
F="${1:?用法: read-elf-form.sh <那 ELF>}"

if [ ! -f "$F" ]; then
  echo "报缺：那 ELF 逐字不在 $F（=> 而按 C-105 报缺）" >&2
  exit 2
fi

#  => 而那**判读**逐字**走**那 elf-form.sh **那一族**（=> 而而它逐字**已经**有**那**判读**那一族**）。
#     => 而那 :408 那一行 = 「if [ "$rel" = "$entry" ]; then entry_form="$form"; entry_role="$role"; fi」
source "$HERE/../toolchain/elf-form.sh"
printf '%s\n' "$(elf_form "$F")"

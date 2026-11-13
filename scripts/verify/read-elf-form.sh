#!/usr/bin/env bash

set -euo pipefail
export LC_ALL=C

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
F="${1:?用法: read-elf-form.sh <那 ELF>}"

if [ ! -f "$F" ]; then
  echo "报缺：那 ELF 逐字不在 $F（按 C-105 报缺）" >&2
  exit 2
fi

source "$HERE/../toolchain/elf-form.sh"
printf '%s\n' "$(elf_form "$F")"

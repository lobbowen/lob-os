#!/usr/bin/env bash

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

#!/usr/bin/env bash
set -euo pipefail
HERE="$(dirname "$(readlink -f "${BASH_SOURCE[0]:-$0}")")"
export LOBOS_CXX=1
exec bash "$HERE/musl-cc.sh" "$@"

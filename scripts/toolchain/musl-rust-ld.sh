#!/usr/bin/env bash
set -euo pipefail
NDK="${LOBOS_NDK:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] || { echo "缺 NDK" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
exec "$TC/clang" --target=aarch64-linux-musl "$@"

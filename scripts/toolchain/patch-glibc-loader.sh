#!/usr/bin/env bash
# Patch glibc ld-linux to install a SIGSYS handler at exec-entry time.
#
# Problem: Android untrusted_app seccomp answers rseq(293) with SIGSYS; glibc
# 2.35+ calls rseq at startup -> signal 31 death. LD_PRELOAD cannot reach the
# loader because the loader itself is the exec target.
#
# Approach (prototype): embed a small position-independent shellcode into the
# loader's .eh_frame (inside the first RX LOAD), install SIGSYS handler via raw
# rt_sigaction, then branch back to the original entry.
#
# STATUS: prototype only. The entry-redirect branch has a PIE base + BTI issue
# (see docs/BASE-CAPABILITY-GAPS.md); an empty shellcode that only jumps back
# still faults, so the jump mechanism needs debugging (prefer patching in place
# at _dl_start with a conditional first-pass branch rather than redirecting
# e_entry). Kept as a concrete, self-contained starting point.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/container/native/loader-patch/entry.S"
LOADER="$ROOT/dist-glibc-runtime/lib/ld-linux-aarch64.so.1"
OUT="${1:-$ROOT/dist-glibc-runtime/lib/ld-linux-patched.so.1}"

[ -f "$SRC" ] || { echo "[patch-loader] 缺 shellcode 源码: $SRC" >&2; exit 1; }
[ -f "$LOADER" ] || { echo "[patch-loader] 缺 loader: $LOADER" >&2; exit 1; }

# Need an aarch64 assembler: prefer the downloaded glibc cross toolchain.
TC="${GLIBC_TC_PREFIX:-/tmp/glibc-toolchain/root}"
AS="$TC/usr/bin/aarch64-linux-gnu-as"
OBJCOPY="$TC/usr/bin/aarch64-linux-gnu-objcopy"
export LD_LIBRARY_PATH="$TC/usr/lib/x86_64-linux-gnu:$TC/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
if [ ! -x "$AS" ]; then
  echo "[patch-loader] 缺 aarch64 as（先取 glibc 交叉工具链，见 build-guest-sigsys.sh）" >&2
  exit 1
fi

OBJ=$(mktemp /tmp/loader-patch.XXXXXX.o)
BIN=$(mktemp /tmp/loader-patch.XXXXXX.bin)
trap 'rm -f "$OBJ" "$BIN"' EXIT
"$AS" -o "$OBJ" "$SRC"
"$OBJCOPY" -O binary --only-section=.text "$OBJ" "$BIN"
cp -f "$LOADER" "$OUT"

python3 - "$OUT" "$BIN" <<'PY'
import struct, sys
loader, scfile = sys.argv[1], sys.argv[2]
sc = open(scfile, 'rb').read()
PATCH_OFF = 0x23850   # .eh_frame start, inside first RX LOAD (file off == vaddr)
ORIG_ENTRY = 0x1b280
assert len(sc) <= 0x3b68, "shellcode bigger than .eh_frame"
assert PATCH_OFF + len(sc) <= 0x273b8, "shellcode past RX LOAD"
data = bytearray(open(loader, 'rb').read())
entry = struct.unpack_from('<Q', data, 0x18)[0]
assert entry == ORIG_ENTRY, f'unexpected entry {entry:#x}'
struct.pack_into('<Q', data, 0x18, PATCH_OFF)
data[PATCH_OFF:PATCH_OFF+len(sc)] = sc
# orig_entry_ptr literals: bti@0, installer..., movz@0x6c, movk@0x70, nop,nop, br
def movz(rd, imm):  return 0xd2800000 | ((imm & 0xffff) << 5) | rd
def movk(rd, imm, sh): return 0xf2a00000 | ((imm & 0xffff) << 5) | (sh << 21) | rd
if len(sc) >= 0x80:
    struct.pack_into('<I', data, PATCH_OFF+0x6c, movz(16, 0xb280))
    struct.pack_into('<I', data, PATCH_OFF+0x70, movk(16, 1, 1))
open(loader, 'wb').write(data)
print(f'[patch-loader] PROTOTYPE entry {entry:#x}->{PATCH_OFF:#x}, sc {len(sc)}B')
PY
chmod 755 "$OUT"
sha256sum "$OUT" | sed 's/^/  patched sha: /'
